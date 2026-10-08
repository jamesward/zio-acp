package interop

import com.jamesward.zioacp.*
import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*

import java.nio.file.{Files, Path}

/** A step's failure detail. A detail containing TIMEOUT counts as a hang. */
final case class StepFailure(detail: String)

/**
 * The Scala interop client of the cross-SDK step catalogue: runs the step ids in `STEPS` in order, printing one
 * `STEP <id> PASS|FAIL (<ms> ms) -> <detail>` line per step and then a `RESULT` line.
 *
 * {{{
 *   STEPS=... AGENT_CMD=... client --transport stdio
 * }}}
 */
object InteropClient extends ZIOAppDefault:
  override val bootstrap = AcpAgent.stderrLogging ++ Runtime.enableLoomBasedBlockingExecutor

  def run =
    defer:
      val args = getArgs.run.toList
      val steps = parseSteps(System.env("STEPS").run)
      val agentCmd = System.env("AGENT_CMD").run
      (args, agentCmd) match
        case (List("--transport", "stdio"), Some(cmd)) if steps.nonEmpty =>
          val timeout = System.env("STEP_TIMEOUT_MS").run.flatMap(_.toLongOption).fold(15.seconds)(Duration.fromMillis)
          val dir = ZIO.attemptBlocking(Files.createTempDirectory("acp-interop-").nn).orDie.run
          val counters = Counters(Ref.make(0).run, Ref.make(Map.empty[String, Int]).run)
          val runner = ClientSteps(cmd, dir, timeout, counters, Ref.make(Option.empty[Conn]).run, Ref.make(Option.empty[InitializeResponse]).run)
          val results = ZIO.foreach(steps)(runner.run).run
          runner.main.get.flatMap(ZIO.foreachDiscard(_)(_.close)).run
          val total = counters.total.get.run
          val byKind = kinds(counters.byKind.get.run)
          Conn.out(s"RESULT pass=${results.count(identity)} fail=${results.count(!_)} updates_total=$total $byKind").run
          exit(ExitCode.success).run
        case _ =>
          Step.log("usage: STEPS=<ids> AGENT_CMD=<command> client --transport stdio").run
          exit(ExitCode(2)).run

def kinds(byKind: Map[String, Int]): String = byKind.toList.sorted.map((k, n) => s"upd_$k=$n").mkString(" ")

def parseSteps(env: Option[String]): List[String] = env.toList.flatMap(_.split(',').toList.map(_.trim).filter(_.nonEmpty))

final case class ClientSteps(
  agentCmd: String,
  dir: Path,
  stepTimeout: Duration,
  counters: Counters,
  main: Ref[Option[Conn]],
  initResponse: Ref[Option[InitializeResponse]],
):
  val updateGrace = 1.second
  val replayGrace = 2.seconds

  type StepIO[A] = IO[StepFailure, A]

  def run(id: String): UIO[Boolean] =
    defer:
      val t0 = Clock.nanoTime.run
      val result = steps.get(id) match
        case Some(body) =>
          body.timeoutFail(StepFailure(s"TIMEOUT after ${stepTimeout.toMillis} ms"))(stepTimeout).either.run
        case None => Left(StepFailure("unknown-step"))
      val now = Clock.nanoTime.run
      val detail = Step.abbreviate(result.fold(_.detail, identity))
      Conn.out(Step.line(id, result.isRight, t0, if result.isLeft && steps.get(id).isEmpty then t0 else now, detail)).run
      result.isRight

  // ---------------------------------------------------------------- helpers

  def fail(detail: String): StepIO[Nothing] = ZIO.fail(StepFailure(detail))

  def check(cond: Boolean, detail: => String): StepIO[Unit] = fail(detail).unless(cond).unit

  def rpc[A](io: IO[RpcError, A]): StepIO[A] = io.mapError(e => StepFailure(s"error ${e.code.value}: ${e.message}"))

  def await(cond: UIO[Boolean], detail: UIO[String], grace: Duration = updateGrace): StepIO[Unit] =
    cond
      .repeat(Schedule.spaced(20.millis) && Schedule.recurUntil[Boolean](identity))
      .timeout(grace)
      .flatMap:
        case Some(_) => ZIO.unit
        case None    => cond.flatMap(ok => if ok then ZIO.unit else detail.flatMap(fail))

  def conn: StepIO[Conn] = main.get.someOrFail(StepFailure("no main connection: init.initialize did not run first"))

  def init: StepIO[InitializeResponse] = initResponse.get.someOrFail(StepFailure("no initialize response: init.initialize did not pass"))

  def newSession(c: Conn): StepIO[SessionId] = rpc(c.newSession(dir.toString))

  def prompt(c: Conn, sid: SessionId, text: String): StepIO[PromptResponse] = rpc(c.prompt(sid, text))

  def endTurn(r: PromptResponse): StepIO[Unit] = check(r.stopReason == StopReason.EndTurn, s"stopReason ${r.stopReason}")

  def promptEndTurn(response: StepIO[PromptResponse]): StepIO[Unit] = response.flatMap(endTurn)

  /** Prompts on a new session and waits for the chunk `expected`. */
  def chunkStep(text: String, expected: String, within: Duration): StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val t0 = Clock.nanoTime.run
      promptEndTurn(prompt(c, sid, text)).run
      await(c.chunks(sid).map(_.contains(expected)), c.chunks(sid).map(cs => s"no chunk \"$expected\" in $cs")).run
      val ms = (Clock.nanoTime.run - t0) / 1000000
      check(ms <= within.toMillis, s"\"$expected\" after $ms ms").run
      s"\"$expected\" after $ms ms"

  /** `#emit <kind>`: an update matching `matches` arrives, and the prompt answers end_turn. */
  def emit(kind: String)(matches: PartialFunction[SessionUpdate, Boolean]): StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, s"#emit $kind")).run
      await(c.updates(sid).map(_.exists(u => matches.applyOrElse(u, _ => false))), c.updates(sid).map(us => s"no matching $kind in $us")).run
      s"$kind received; end_turn"

  def text(block: ContentBlock): String =
    block match
      case ContentBlock.Text(t, _, _) => t
      case _                          => ""

  def selectValue(options: List[SessionConfigOption], id: String): Option[SessionConfigValueId] =
    options.collectFirst { case s: SessionConfigOption.Select if s.id.value == id => s.currentValue }

  val steps: Map[String, StepIO[String]] = ClientCatalogue(this).steps
