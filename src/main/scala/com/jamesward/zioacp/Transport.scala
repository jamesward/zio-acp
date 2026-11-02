package com.jamesward.zioacp

import zio.*
import zio.stream.*

import zio.process.{Command, Process, ProcessInput}

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Path

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
    detached(in)
      .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
      .filter(_.exists(!_.isWhitespace))

  /**
   * `in`'s bytes, read on a daemon thread of their own. A read blocked in native code (stdin with nothing to read)
   * can't be interrupted, so a stream reading it directly would hold up the interruption of whatever reads it, and with
   * it the process's shutdown (a SIGTERM waits for that interruption). Here the stream only waits on a queue: it's
   * interrupted at once, and the reader thread, a daemon, doesn't keep the process alive.
   */
  private def detached(in: InputStream): ZStream[Any, Throwable, Byte] =
    ZStream.unwrapScoped:
      for
        queue <- ZIO.acquireRelease(Queue.bounded[Take[Throwable, Byte]](16))(_.shutdown)
        rt    <- ZIO.runtime[Any]
        _     <- ZIO.succeed:
                   val reader = Thread.ofPlatform().daemon(true).name("acp-stdin").unstarted: () =>
                     def offer(take: Take[Throwable, Byte]): Boolean =
                       Unsafe.unsafely(rt.unsafe.run(queue.offer(take)).getOrElse(_ => false))
                     val buffer = new Array[Byte](64 * 1024)
                     try
                       var n = in.read(buffer)
                       var open = true
                       while n >= 0 && open do
                         if n > 0 then open = offer(Take.chunk(Chunk.fromArray(buffer.take(n))))
                         if open then n = in.read(buffer)
                       if open then offer(Take.end)
                     catch case e: Throwable => offer(Take.fail(e))
                   reader.start()
      yield ZStream.fromQueue(queue).flattenTake

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
      // the original System.out carries the protocol; everything else that prints to System.out reaches stderr
      protocolOut <- ZIO.acquireRelease(
                       ZIO.succeed(java.lang.System.out).tap(_ => ZIO.succeed(java.lang.System.setOut(java.lang.System.err))),
                     )(original => ZIO.succeed(java.lang.System.setOut(original)))
      transport   <- fromStreams(java.lang.System.in, protocolOut)
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
      stdin   <- Queue.unbounded[Option[Chunk[Byte]]]
      running <- ZIO.acquireRelease(launch(agent, stdin, stderr))(stop(_, exitTimeout))
    yield Transport(
      ZStream.fromQueue(running.stdout).flattenTake,
      message => stdin.offer(Some(Chunk.fromArray((message + "\n").getBytes(StandardCharsets.UTF_8)))).unit,
      stdin.offer(None).unit,
    )

  /** A started agent, its stdin, its stdout lines, and the fibers pumping its stdout and stderr. */
  private final case class Running(
    process: Process,
    stdin: Queue[Option[Chunk[Byte]]],
    stdout: Queue[Take[Throwable, String]],
    pumps: Fiber[Nothing, Any],
  )

  /**
   * Starts the agent, with stdin fed from `stdin` (`None` closes it), and pumps its stdout and stderr on fibers that
   * belong to the process rather than to its readers: a read blocked on a pipe can't be interrupted, so the readers must
   * be able to stop without waiting for one. The pumps end at end of file, once the process has exited.
   */
  private def launch(agent: AgentCommand, stdin: Queue[Option[Chunk[Byte]]], stderr: String => UIO[Unit]): Task[Running] =
    val input = ProcessInput.fromStream(ZStream.fromQueue(stdin).collectWhileSome.flattenChunks, flushChunksEagerly = true)
    val command = Command(agent.command, agent.args*).env(agent.env).stdin(input)
    for
      process <- agent.cwd.fold(command)(dir => command.workingDirectory(dir.toFile)).run
      stdout  <- Queue.unbounded[Take[Throwable, String]]
      out     <- nonBlank(process.stdout.linesStream).runIntoQueue(stdout).forkDaemon
      err     <- nonBlank(process.stderr.linesStream).foreach(stderr)
                   .catchAllCause(cause => ZIO.logDebugCause("agent stderr closed", cause))
                   .forkDaemon
    yield Running(process, stdin, stdout, out.zip(err))

  private def nonBlank[E](lines: ZStream[Any, E, String]): ZStream[Any, E, String] = lines.filter(_.exists(!_.isWhitespace))

  /**
   * Closes stdin and waits for the agent to exit; after `exitTimeout` terminates it, and after another kills it.
   *
   * This runs as a finalizer, which is uninterruptible, and so is anything it races: a timeout could not stop the wait.
   * Each wait is therefore made interruptible on its own, and it waits on the process's exit future rather than on
   * zio-process's `exitCode`, which blocks a thread in `waitFor` until the process exits.
   */
  private def stop(running: Running, exitTimeout: Duration): UIO[Unit] =
    val process = running.process
    val exited: UIO[Boolean] =
      ZIO.interruptible(process.execute(_.onExit().nn).flatMap(ZIO.fromCompletableFuture(_)))
        .timeout(exitTimeout).map(_.isDefined).orElseSucceed(false)
    (for
      _      <- running.stdin.offer(None)
      inTime <- exited
      _      <- ZIO.unless(inTime):
                  for
                    _          <- ZIO.logWarning(s"agent did not exit within $exitTimeout of its stdin closing; terminating it")
                    _          <- process.execute(_.destroy())
                    terminated <- exited
                    _          <- process.execute(_.destroyForcibly()).unless(terminated)
                  yield ()
      _      <- ZIO.interruptible(running.pumps.await).timeout(exitTimeout)
    yield ()).ignoreLogged

  /** Two transports connected to each other in memory. */
  val pipe: UIO[(Transport, Transport)] =
    for
      aToB <- Queue.unbounded[Option[String]]
      bToA <- Queue.unbounded[Option[String]]
    yield (queues(bToA, aToB), queues(aToB, bToA))

  private def queues(in: Queue[Option[String]], out: Queue[Option[String]]): Transport =
    Transport(ZStream.fromQueue(in).collectWhileSome, message => out.offer(Some(message)).unit, out.offer(None).unit)
