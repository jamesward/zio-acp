package com.jamesward.zioacp

import zio.*
import zio.test.*

import java.io.{PipedInputStream, PipedOutputStream}

/** A transport's input is read without holding up interruption: an agent stops at once, with nothing to read. */
object TransportSpec extends ZIOSpecDefault:
  def spec = suite("Transport")(
    test("lines are read as they arrive; a reader blocked on an input with nothing to read is interrupted at once") {
      for
        out     <- ZIO.succeed(PipedOutputStream())
        in       = PipedInputStream(out)
        lines   <- Ref.make(Vector.empty[String])
        reading <- Transport.lines(in).runForeach(l => lines.update(_ :+ l)).fork
        _       <- ZIO.attemptBlocking { out.write("a\n\nb\n".getBytes); out.flush() }
        got     <- lines.get.repeatUntil(_.size == 2)
        took    <- reading.interrupt.timed
      yield assertTrue(got == Vector("a", "b"), took._1 < 2.seconds)
    } @@ TestAspect.withLiveClock,
    test("the end of the input ends the stream") {
      for
        out  <- ZIO.succeed(PipedOutputStream())
        in    = PipedInputStream(out)
        _    <- ZIO.attemptBlocking { out.write("x\n".getBytes); out.close() }
        all  <- Transport.lines(in).runCollect
      yield assertTrue(all == Chunk("x"))
    },
  ) @@ TestAspect.timeout(20.seconds)
