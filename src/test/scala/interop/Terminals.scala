package interop

import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*
import zio.stream.*

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** A client's terminal: a real process, its output collected in memory. */
final case class Terminal(process: java.lang.Process, output: Ref[String], reader: Fiber[Nothing, Unit])

/** The client's terminals, keyed by id. */
final case class Terminals(terminals: Ref[Map[TerminalId, Terminal]], ids: Ref[Long]):

  def create(request: CreateTerminalRequest): IO[RpcError, CreateTerminalResponse] =
    defer:
      val process = ZIO
        .attemptBlocking:
          val builder = ProcessBuilder((request.command :: request.args).asJava).redirectErrorStream(true)
          request.cwd.foreach(dir => builder.directory(Path.of(dir).toFile))
          request.env.foreach(e => builder.environment().nn.put(e.name, e.value))
          val p = builder.start().nn
          p.getOutputStream.nn.close()
          p
        .mapError(e => RpcError.internalError(s"could not start ${request.command}"))
        .run
      val output = Ref.make("").run
      val reader = ZStream
        .fromInputStream(process.getInputStream.nn)
        .via(ZPipeline.utf8Decode)
        .foreach(s => output.update(_ + s))
        .catchAllCause(cause => ZIO.logDebugCause("terminal output ended", cause))
        .forkDaemon
        .run
      val id = TerminalId(s"term-${ids.updateAndGet(_ + 1).run}")
      terminals.update(_.updated(id, Terminal(process, output, reader))).run
      Conn.out(s"  terminal/create ${id.value} ${request.command} ${request.args.mkString(" ")}").run
      CreateTerminalResponse(id)

  private def terminal(id: TerminalId): IO[RpcError, Terminal] =
    terminals.get.map(_.get(id)).someOrFail(RpcError.invalidParams(s"unknown terminal ${id.value}"))

  def output(request: TerminalOutputRequest): IO[RpcError, TerminalOutputResponse] =
    defer:
      val t = terminal(request.terminalId).run
      val status = Option.when(!t.process.isAlive)(TerminalExitStatus(exitCode = Some(t.process.exitValue().toLong)))
      TerminalOutputResponse(t.output.get.run, truncated = false, exitStatus = status)

  def waitForExit(request: WaitForTerminalExitRequest): IO[RpcError, WaitForTerminalExitResponse] =
    defer:
      val t = terminal(request.terminalId).run
      val code = ZIO.attemptBlockingInterrupt(t.process.waitFor()).mapError(_ => RpcError.internalError("wait interrupted")).run
      // let the reader drain what the process wrote before it exited
      t.reader.join.timeout(1.second).run
      WaitForTerminalExitResponse(exitCode = Some(code.toLong))

  def kill(request: KillTerminalRequest): IO[RpcError, KillTerminalResponse] =
    defer:
      val t = terminal(request.terminalId).run
      ZIO.succeed:
        t.process.descendants().nn.forEach(_.destroy())
        t.process.destroy()
      .run
      KillTerminalResponse()

  def release(request: ReleaseTerminalRequest): IO[RpcError, ReleaseTerminalResponse] =
    terminals
      .modify(m => (m.get(request.terminalId), m - request.terminalId))
      .flatMap(ZIO.foreachDiscard(_)(t => ZIO.succeed(t.process.destroyForcibly()).when(t.process.isAlive)))
      .as(ReleaseTerminalResponse())

  def releaseAll: UIO[Unit] =
    terminals.getAndSet(Map.empty).flatMap(m => ZIO.foreachDiscard(m.values)(t => ZIO.succeed(t.process.destroyForcibly())))

object Terminals:
  val make: UIO[Terminals] = Ref.make(Map.empty[TerminalId, Terminal]).zipWith(Ref.make(0L))(Terminals(_, _))
