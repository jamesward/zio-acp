package interop

import com.jamesward.zioacp.Transport
import zio.*

import java.nio.charset.StandardCharsets

/**
 * `stdio.eof-exit`: a second agent, driven by hand, must exit within 5 s of its stdin closing. The client library hides
 * this (it ends the child itself), so the step talks to the process directly.
 */
final case class StdioEofExit(agentCmd: String, stepTimeout: Duration):
  private val initialize =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{},"clientInfo":{"name":"interop-scala-client","version":"1"}}}"""

  private def start: ZIO[Scope, Throwable, java.lang.Process] =
    ZIO.acquireRelease(ZIO.attemptBlocking(ProcessBuilder("bash", "-c", s"exec $agentCmd").start())): p =>
      ZIO.succeed:
        p.descendants().forEach(_.destroyForcibly())
        p.destroyForcibly()

  def step: IO[StepFailure, String] =
    ZIO
      .scoped:
        for
          process <- start
          _ <- Transport.lines(process.getErrorStream).foreach(line => Conn.relay(s"[eof] $line")).ignore.forkScoped
          in = process.getOutputStream
          _ <- ZIO.attemptBlocking { in.write((initialize + "\n").getBytes(StandardCharsets.UTF_8)); in.flush() }
          response <- Transport.lines(process.getInputStream).runHead.timeout(stepTimeout).map(_.flatten)
          _ <- ZIO.fail(Exception(s"initialize answered $response")).unless(response.exists(_.contains("\"result\"")))
          _ <- ZIO.attemptBlocking(in.close())
          t0 <- Clock.nanoTime
          exited <- ZIO.attemptBlockingInterrupt(process.waitFor()).timeout(5.seconds)
          now <- Clock.nanoTime
          detail <- exited match
                      case Some(code) => ZIO.succeed(s"initialized, then exited ${(now - t0) / 1000000} ms after EOF (code $code)")
                      case None       => ZIO.fail(Exception("no exit on EOF within 5 s"))
        yield detail
      .mapError(e => StepFailure(e.getMessage))
