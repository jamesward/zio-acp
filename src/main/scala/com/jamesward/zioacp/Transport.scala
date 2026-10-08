package com.jamesward.zioacp

import zio.*
import zio.stream.*

import java.io.{FileDescriptor, FileOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * A bidirectional channel of JSON-RPC messages.
 *
 * @param incoming
 *   incoming messages, one per element; ends when the peer closes its side
 * @param send
 *   sends one message; messages sent by one fiber are delivered in order
 * @param close
 *   closes the outgoing side, which tells the peer that nothing more will be sent
 */
final case class Transport(incoming: ZStream[Any, Throwable, String], send: String => Task[Unit], close: UIO[Unit])

/** How to start an agent as a subprocess. */
final case class AgentCommand(
  command: String,
  args: List[String] = Nil,
  env: Map[String, String] = Map.empty,
  cwd: Option[Path] = None,
)

object Transport:

  /** Lines of `in`, decoded as UTF-8, skipping blank lines. */
  def lines(in: InputStream): ZStream[Any, Throwable, String] =
    ZStream
      .fromInputStream(in, 64 * 1024)
      .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
      .filter(_.exists(!_.isWhitespace))

  def fromStreams(in: InputStream, out: OutputStream): UIO[Transport] =
    Semaphore.make(1).map: writeLock =>
      Transport(
        lines(in),
        message =>
          writeLock.withPermit:
            ZIO.attemptBlocking:
              out.write((message + "\n").getBytes(StandardCharsets.UTF_8))
              out.flush()
        ,
        writeLock.withPermit(ZIO.attemptBlocking(out.close())).ignoreLogged,
      )

  /**
   * The process's stdin and stdout, for an agent launched by a client. While the scope is open, `System.out` is pointed at
   * stderr so that stray prints from Java code can't corrupt the protocol stream.
   */
  val stdio: ZIO[Scope, Nothing, Transport] =
    for
      protocolOut <- ZIO.succeed(FileOutputStream(FileDescriptor.out))
      _ <- ZIO.acquireRelease(ZIO.succeed(java.lang.System.out).tap(_ => ZIO.succeed(java.lang.System.setOut(java.lang.System.err))))(
             original => ZIO.succeed(java.lang.System.setOut(original)),
           )
      transport <- fromStreams(java.lang.System.in, protocolOut)
    yield transport

  /**
   * Starts an agent subprocess and talks to it over its stdin and stdout. Each line it writes to stderr goes to `stderr`.
   * Closing the scope closes the agent's stdin, waits up to `exitTimeout` for it to exit, and then kills it.
   */
  def process(
    agent: AgentCommand,
    stderr: String => UIO[Unit] = line => ZIO.logInfo(s"agent stderr: $line"),
    exitTimeout: Duration = 5.seconds,
  ): ZIO[Scope, Throwable, Transport] =
    for
      process <- ZIO.acquireRelease(start(agent))(stop(_, exitTimeout))
      _ <- lines(process.getErrorStream.nn)
             .foreach(stderr)
             .catchAllCause(cause => ZIO.logDebugCause("agent stderr closed", cause))
             .forkScoped
      transport <- fromStreams(process.getInputStream.nn, process.getOutputStream.nn)
    yield transport

  private def start(agent: AgentCommand): Task[java.lang.Process] =
    ZIO.attemptBlocking:
      val builder = ProcessBuilder((agent.command :: agent.args).asJava)
      agent.cwd.foreach(dir => builder.directory(dir.toFile))
      builder.environment().nn.putAll(agent.env.asJava)
      builder.start().nn

  private def stop(process: java.lang.Process, exitTimeout: Duration): UIO[Unit] =
    val awaitExit = ZIO.attemptBlockingInterrupt(process.waitFor()).unit
    (ZIO.attemptBlocking(process.getOutputStream.nn.close()) *>
      awaitExit.timeout(exitTimeout).flatMap:
        case Some(_) => ZIO.unit
        case None    =>
          ZIO.logWarning(s"agent ${process.pid()} did not exit within $exitTimeout of its stdin closing; destroying it") *>
            ZIO.succeed(process.destroy()) *>
            awaitExit.timeout(exitTimeout).flatMap(exited => ZIO.succeed(process.destroyForcibly()).when(exited.isEmpty).unit)
    ).ignoreLogged

  /** Two transports connected to each other in memory. */
  val pipe: UIO[(Transport, Transport)] =
    for
      aToB <- Queue.unbounded[Option[String]]
      bToA <- Queue.unbounded[Option[String]]
    yield (queues(bToA, aToB), queues(aToB, bToA))

  private def queues(in: Queue[Option[String]], out: Queue[Option[String]]): Transport =
    Transport(ZStream.fromQueue(in).collectWhileSome, message => out.offer(Some(message)).unit, out.offer(None).unit)
