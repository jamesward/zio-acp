package interop

import com.jamesward.zioacp.*
import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*
import zio.json.*
import zio.json.ast.Json

/** The agent's behaviour for each prompt directive of the step catalogue ("directives" in steps.json). */
final case class Directives(agent: InteropAgentImpl, session: SessionState, request: PromptRequest, turn: PromptTurn):
  private val client = agent.client
  private val sid = session.id
  private val endTurn = PromptResponse(StopReason.EndTurn)
  private val cancelled = PromptResponse(StopReason.Cancelled)

  private def say(text: String): IO[RpcError, Unit] =
    client.sessionUpdate(sid, SessionUpdate.AgentMessageChunk(ContentBlock.text(text)))

  private def sayAndEnd(text: String): IO[RpcError, PromptResponse] = say(text).as(endTurn)

  private def unknown(text: String): IO[RpcError, PromptResponse] =
    ZIO.fail(RpcError.invalidParams(s"unknown directive: ${text.split(' ').head}"))

  private val text: String =
    request.prompt.collectFirst { case ContentBlock.Text(t, _, _) => t }.fold("")(identity)

  /** Everything after the first `n` space-separated words. */
  private def rest(n: Int): String = text.split(" ", n + 1).lift(n).fold("")(identity)

  private def code(error: RpcError): String = error.code.value.toString

  def respond: IO[RpcError, PromptResponse] =
    val words = text.split(' ').toList
    if !text.startsWith("#") then echo
    else
      words match
        case "#permission" :: "allow" :: Nil           => permission(hold = false, meta = false)
        case "#permission" :: "allow" :: "meta" :: Nil => permission(hold = false, meta = true)
        case "#permission" :: "hold" :: Nil            => permission(hold = true, meta = false)
        case "#fs" :: "write" :: _                     => fsWrite(rest(2))
        case "#fs" :: "read" :: path :: _              => fsRead("fs.read", path, words.drop(3))
        case "#fs" :: "read-range" :: path :: _        => fsRead("fs.read-range", path, words.drop(3))
        case "#fs" :: "read-missing" :: path :: _      => fsRead("fs.read-missing", path, Nil)
        case "#fs" :: "read-slow" :: path :: _         => readSlow(path)
        case "#emit" :: kind :: options                => emit(kind, options)
        case "#stop" :: reason :: Nil                  => stop(reason)
        case "#slow" :: options                        => slow(options)
        case "#hang" :: Nil                            => ZIO.never
        case "#terminal" :: "run" :: command :: args   => terminalRun(command, args)
        case "#terminal" :: "kill" :: command :: args  => terminalKill(command, args)
        case "#elicit" :: "form" :: Nil                => elicitForm
        case "#elicit" :: "url" :: Nil                 => elicitUrl
        case "#ext" :: "request" :: method             => extRequest(method.headOption.fold(Fixtures.extMethod)(identity))
        case "#ext" :: "notify" :: method              =>
          client.extNotification(method.headOption.fold(Fixtures.extNotification)(identity), Fixtures.extParams) *>
            sayAndEnd("ext notified")
        case "#ext" :: "last-notification" :: Nil => agent.lastExtNotification.get.flatMap(m => sayAndEnd(s"ext last: $m"))
        case "#meta" :: Nil                       => meta
        case "#echo-caps" :: Nil                  => echoCaps
        case "#enum" :: what :: Nil               => unknownEnum(what)
        case "#config" :: "grouped" :: Nil        => configGrouped
        case "#len" :: _                          => sayAndEnd(s"len=${rest(1).length}")
        case "#big" :: bytes :: Nil if bytes.forall(_.isDigit) => sayAndEnd("x" * bytes.toInt)
        case _                                    => unknown(text)

  private def echo: IO[RpcError, PromptResponse] =
    val history = Chunk(
      SessionUpdate.UserMessageChunk(ContentBlock.text(text)),
      SessionUpdate.AgentMessageChunk(ContentBlock.text("echo: ")),
      SessionUpdate.AgentMessageChunk(ContentBlock.text(text)),
    )
    agent.update(sid)(s => s.copy(history = s.history ++ history)) *> say("echo: ") *> sayAndEnd(text)

  private def permission(hold: Boolean, meta: Boolean): IO[RpcError, PromptResponse] =
    val id = if hold then "perm.cancelled" else "perm.selected"
    val req = RequestPermissionRequest(sid, Fixtures.permissionToolCall, Fixtures.permissionOptions, Option.when(meta)(Fixtures.meta))
    defer:
      val t0 = Clock.nanoTime.run
      val response = client.requestPermission(req).tapError(e => Step.agent(if meta then "meta.permission" else id, false, t0, s"failed: ${e.message}")).run
      val said = response.outcome match
        case RequestPermissionOutcome.Selected(optionId, _) => s"selected ${optionId.value}"
        case RequestPermissionOutcome.Cancelled             => "cancelled"
      if meta then Step.agent("meta.permission", Fixtures.hasMeta(response.meta), t0, s"response _meta ${response.meta.toJson}").run
      else Step.agent(id, said == (if hold then "cancelled" else "selected allow"), t0, s"outcome $said").run
      say(s"permission: $said").run
      if said == "cancelled" then cancelled else endTurn

  private def fsAllowed(write: Boolean): UIO[Boolean] =
    agent.capabilities.map(_.exists(c => if write then c.fs.writeTextFile else c.fs.readTextFile))

  private def fsWrite(args: String): IO[RpcError, PromptResponse] =
    val (path, content) = args.split(" ", 2) match
      case Array(p, c) => (p, c)
      case other       => (other.mkString, "")
    defer:
      val t0 = Clock.nanoTime.run
      if !fsAllowed(write = true).run then
        Step.agent("fs.write", false, t0, "the client did not advertise fs.writeTextFile").run
        sayAndEnd("fs write error capability").run
      else
        val result = client.writeTextFile(WriteTextFileRequest(sid, path, content)).either.run
        Step.agent("fs.write", result.isRight, t0, result.fold(e => s"failed: ${e.message}", _ => "answered without error")).run
        sayAndEnd(result.fold(e => s"fs write error ${code(e)}", _ => "fs write ok")).run

  private def fsRead(id: String, path: String, options: List[String]): IO[RpcError, PromptResponse] =
    def option(name: String): Option[Long] = options.collectFirst { case o if o.startsWith(s"$name=") => o.drop(name.length + 1).toLong }
    defer:
      val t0 = Clock.nanoTime.run
      if !fsAllowed(write = false).run then
        Step.agent(id, false, t0, "the client did not advertise fs.readTextFile").run
        sayAndEnd("fs read error capability").run
      else
        client.readTextFile(ReadTextFileRequest(sid, path, option("line"), option("limit"))).either.run match
          case Right(response) =>
            val content = response.content
            val pass = id match
              case "fs.read"       => content == Fixtures.fsReadContent
              case "fs.read-range" => content.trim == "line2"
              case _               => false
            Step.agent(id, pass, t0, s"content ${Step.abbreviate(content).replace("\n", "\\n")}").run
            sayAndEnd(content).run
          case Left(error) =>
            Step.agent(id, id == "fs.read-missing", t0, s"failed with ${code(error)}: ${error.message}").run
            sayAndEnd(s"fs read error ${code(error)}").run

  private def readSlow(path: String): IO[RpcError, PromptResponse] =
    defer:
      val t0 = Clock.nanoTime.run
      val result = client
        .call(Methods.Client.ReadTextFile, ReadTextFileRequest(sid, path), cancelWhen = ZIO.sleep(300.millis))
        .either
        .timeout(5300.millis)
        .run
      val elapsed = (Clock.nanoTime.run - t0) / 1000000
      result match
        case Some(Left(e)) =>
          Step.agent("cancel-request.agent", e.code == ErrorCode.RequestCancelled, t0, s"ended with ${code(e)} after $elapsed ms").run
        case Some(Right(_)) => Step.agent("cancel-request.agent", false, t0, "the read answered content, not -32800").run
        case None           => Step.agent("cancel-request.agent", false, t0, "TIMEOUT: no answer within 5 s of $/cancel_request").run
      sayAndEnd("cancel-request sent").run

  private def emit(kind: String, options: List[String]): IO[RpcError, PromptResponse] =
    val name = options.collectFirst { case o if o.startsWith("name=") => o.drop(5) }
    val toolCall = SessionUpdate.ToolCallStarted(
      ToolCall(ToolCallId("call-1"), "interop tool", name = name, kind = ToolKind.Read, status = ToolCallStatus.Pending),
    )
    val updates: Option[List[SessionUpdate]] = kind match
      case "user_message_chunk"  => Some(List(SessionUpdate.UserMessageChunk(ContentBlock.text("user-chunk"))))
      case "agent_thought_chunk" => Some(List(SessionUpdate.AgentThoughtChunk(ContentBlock.text("thinking"))))
      case "tool_call"           => Some(List(toolCall))
      case "tool_call_update" =>
        Some(
          List(
            toolCall,
            SessionUpdate.ToolCallUpdated(
              ToolCallUpdate(
                ToolCallId("call-1"),
                status = Some(ToolCallStatus.Completed),
                content = Some(List(ToolCallContent.Content(ContentBlock.text("tool output")))),
              ),
            ),
          ),
        )
      case "plan" =>
        Some(
          List(
            SessionUpdate.Plan(
              List(
                PlanEntry("step one", PlanEntryPriority.High, PlanEntryStatus.Pending),
                PlanEntry("step two", PlanEntryPriority.Low, PlanEntryStatus.Completed),
              ),
            ),
          ),
        )
      case "available_commands_update" =>
        Some(List(SessionUpdate.AvailableCommandsUpdate(List(AvailableCommand("interop", "interop command", Some(UnstructuredCommandInput("args")))))))
      case "current_mode_update"  => Some(List(SessionUpdate.CurrentModeUpdate(Fixtures.modeB)))
      case "config_option_update" => Some(List(SessionUpdate.ConfigOptionUpdate(List(Fixtures.modelOption(Fixtures.modelB)))))
      case "session_info_update"  => Some(List(SessionUpdate.SessionInfoUpdate(title = Some("interop title"))))
      case "usage_update"         => Some(List(SessionUpdate.UsageUpdate(100, 1000, Some(Cost(0.01, "USD")))))
      case "unknown" =>
        Some(
          List(
            SessionUpdate.Unknown("interop_future_update", Json.Obj("payload" -> Json.Obj("x" -> Json.Num(1)))),
            SessionUpdate.AgentMessageChunk(ContentBlock.text("after-unknown")),
          ),
        )
      case _ => None
    updates.fold(unknown(text))(us => ZIO.foreachDiscard(us)(client.sessionUpdate(sid, _)).as(endTurn))

  private def stop(reason: String): IO[RpcError, PromptResponse] =
    val stopReason: Option[StopReason] = reason match
      case "end_turn"          => Some(StopReason.EndTurn)
      case "max_tokens"        => Some(StopReason.MaxTokens)
      case "max_turn_requests" => Some(StopReason.MaxTurnRequests)
      case "refusal"           => Some(StopReason.Refusal)
      case "cancelled"         => Some(StopReason.Cancelled)
      case _                   => None
    stopReason.fold(unknown(text))(r => say("stop").as(PromptResponse(r)))

  /** A tick every 100 ms; after a cancel, `grace` more ms of ticks and then `cancelled`; uncancelled, `end_turn` after 10 s. */
  private def slow(options: List[String]): IO[RpcError, PromptResponse] =
    val grace = options.collectFirst { case o if o.startsWith("grace=") => o.drop(6).toLong }.fold(0L)(identity) * 1000000
    defer:
      val t0 = Clock.nanoTime.run
      val cancelledAt = Ref.make(Option.empty[Long]).run
      (turn.awaitCancel *> Clock.nanoTime.flatMap(t => cancelledAt.set(Some(t)))).fork.run
      val done = Clock.nanoTime.zipWith(cancelledAt.get)((now, at) => at.exists(now - _ >= grace) || now - t0 >= 10000000000L)
      val ticks = (say("tick") *> ZIO.sleep(100.millis)).repeatUntilZIO(_ => done.map(identity))
      val wasClosed = agent.sessions.get.map(_.get(sid).exists(_.closed))
      ticks
        .onInterrupt(
          ZIO.whenZIO(turn.isCancelled.map(!_))(Step.agent("cancel-request.client", true, t0, "the handler was interrupted by $/cancel_request")),
        )
        .run
      val closed = wasClosed.run
      if turn.isCancelled.run then
        if !closed && grace == 0 then Step.agent("cancel.prompt", true, t0, "session/cancel arrived for the running session").run
        cancelled
      else endTurn

  private def terminalAllowed: UIO[Boolean] = agent.capabilities.map(_.exists(_.terminal))

  private def terminalRun(command: String, args: List[String]): IO[RpcError, PromptResponse] =
    defer:
      val t0 = Clock.nanoTime.run
      if !terminalAllowed.run then
        Step.agent("term.run", false, t0, "the client did not advertise terminal").run
        sayAndEnd("terminal error capability").run
      else
        val result = (for
          created <- client.createTerminal(CreateTerminalRequest(sid, command, args))
          tid = created.terminalId
          exit <- client.waitForTerminalExit(WaitForTerminalExitRequest(sid, tid))
          out <- client.terminalOutput(TerminalOutputRequest(sid, tid))
          _ <- client.releaseTerminal(ReleaseTerminalRequest(sid, tid))
        yield (out.output.trim, exit.exitCode)).either.run
        result match
          case Right((output, exitCode)) =>
            Step.agent("term.run", output.contains("hi") && exitCode.contains(0L), t0, s"output $output exitCode $exitCode").run
            sayAndEnd(s"terminal: $output exit=${exitCode.fold("null")(_.toString)}").run
          case Left(e) =>
            Step.agent("term.run", false, t0, s"terminal failed: ${e.message}").run
            sayAndEnd(s"terminal error ${code(e)}").run

  private def terminalKill(command: String, args: List[String]): IO[RpcError, PromptResponse] =
    defer:
      val t0 = Clock.nanoTime.run
      if !terminalAllowed.run then
        Step.agent("term.kill", false, t0, "the client did not advertise terminal").run
        sayAndEnd("terminal error capability").run
      else
        val result = (for
          created <- client.createTerminal(CreateTerminalRequest(sid, command, args))
          tid = created.terminalId
          _ <- ZIO.sleep(200.millis)
          _ <- client.killTerminal(KillTerminalRequest(sid, tid))
          killedAt <- Clock.nanoTime
          exit <- client.waitForTerminalExit(WaitForTerminalExitRequest(sid, tid))
          waited <- Clock.nanoTime.map(now => (now - killedAt) / 1000000)
          _ <- Step.agent("term.kill", waited <= 5000, t0, s"wait_for_exit returned $waited ms after the kill: $exit")
          _ <- client.releaseTerminal(ReleaseTerminalRequest(sid, tid))
        yield ()).either.run
        result match
          case Right(_) => sayAndEnd("terminal killed").run
          case Left(e) =>
            Step.agent("term.kill", false, t0, s"terminal failed: ${e.message}").run
            sayAndEnd(s"terminal error ${code(e)}").run

  private def elicitForm: IO[RpcError, PromptResponse] =
    val schema = ElicitationSchema(
      properties = scala.collection.immutable.ListMap("name" -> ElicitationPropertySchema.StringProperty()),
      required = Some(List("name")),
    )
    defer:
      val t0 = Clock.nanoTime.run
      if !agent.capabilities.map(_.exists(_.elicitation.exists(_.form.isDefined))).run then
        Step.agent("elicit.form", false, t0, "the client did not advertise elicitation.form").run
        sayAndEnd("elicit error capability").run
      else
        client.createElicitation(CreateElicitationRequest.Form(ElicitationScope.Session(sid), "interop form", schema)).either.run match
          case Right(CreateElicitationResponse.Accept(content, _)) =>
            val name = content.flatMap(_.get("name"))
            Step.agent("elicit.form", name.contains(ElicitationContentValue.Str("interop")), t0, s"action accept content ${content.toJson}").run
            sayAndEnd(s"elicit: accept ${content.fold("null")(_.toJson)}").run
          case Right(other) =>
            Step.agent("elicit.form", false, t0, s"answered ${other.toJson}").run
            sayAndEnd(s"elicit: ${other.toJson}").run
          case Left(e) =>
            Step.agent("elicit.form", false, t0, s"elicitation/create failed: ${e.message}").run
            sayAndEnd(s"elicit error ${code(e)}").run

  private def elicitUrl: IO[RpcError, PromptResponse] =
    val id = ElicitationId("elic-1")
    defer:
      if !agent.capabilities.map(_.exists(_.elicitation.exists(_.url.isDefined))).run then sayAndEnd("elicit error capability").run
      else
        val request = CreateElicitationRequest.Url(ElicitationScope.Session(sid), "interop url", id, "https://example.invalid/interop")
        val result = (client.createElicitation(request) *> client.completeElicitation(CompleteElicitationNotification(id))).either.run
        sayAndEnd(result.fold(e => s"elicit error ${code(e)}", _ => "elicit url done")).run

  private def extRequest(method: String): IO[RpcError, PromptResponse] =
    defer:
      val t0 = Clock.nanoTime.run
      client.extMethod(method, Fixtures.extParams).either.run match
        case Right(result) =>
          Step.agent("ext.agent-request", result == Fixtures.extResult, t0, s"result ${result.toJson}").run
          sayAndEnd(s"ext: ${result.toJson}").run
        case Left(e) =>
          Step.agent("ext.agent-request", false, t0, s"$method failed: ${e.message}").run
          sayAndEnd(s"ext error ${code(e)}").run

  private def meta: IO[RpcError, PromptResponse] =
    client
      .sessionUpdate(sid, SessionUpdate.AgentMessageChunk(ContentBlock.text("meta"), meta = request.meta))
      .as(PromptResponse(StopReason.EndTurn, request.meta))

  private def echoCaps: IO[RpcError, PromptResponse] =
    defer:
      val t0 = Clock.nanoTime.run
      val caps = agent.capabilities.run
      Step.agent("init.client-capabilities", caps.isDefined, t0, s"clientCapabilities ${caps.toJson}").run
      sayAndEnd(caps.fold("null")(_.toJson)).run

  private def unknownEnum(what: String): IO[RpcError, PromptResponse] =
    what match
      case "tool_call" =>
        val update = SessionUpdate.ToolCallStarted(
          ToolCall(
            ToolCallId("call-enum"),
            "interop enum tool",
            kind = ToolKind.Unknown("interop_future_kind"),
            status = ToolCallStatus.Unknown(Fixtures.futureStatus),
          ),
        )
        client.sessionUpdate(sid, update) *> sayAndEnd("after-enum")
      case "plan" =>
        val update = SessionUpdate.Plan(
          List(PlanEntry("future entry", PlanEntryPriority.Unknown("interop_future_priority"), PlanEntryStatus.Unknown(Fixtures.futureStatus))),
        )
        client.sessionUpdate(sid, update) *> sayAndEnd("after-enum")
      case "stop" => say("stop").as(PromptResponse(StopReason.Unknown("interop_future_stop")))
      case "audience" =>
        val audience = request.prompt.headOption.collect { case ContentBlock.Text(_, Some(Annotations(Some(roles), _, _, _)), _) => roles }
        val wire = audience.map(_.map(_.toJson.fromJson[String].fold(identity, identity)))
        val kept = audience.exists(_.lift(1).exists(_.isInstanceOf[Role.Other]))
        defer:
          val t0 = Clock.nanoTime.run
          Step.agent("enum.audience", wire.contains(List("user", "interop_future_role")) && kept, t0, s"audience $wire").run
          sayAndEnd(s"audience: ${wire.fold("none")(_.mkString(","))}").run
      case _ => unknown(text)

  private def configGrouped: IO[RpcError, PromptResponse] =
    defer:
      agent.update(sid)(_.copy(grouped = true)).run
      val s = agent.known(sid).run
      val options = agent.configOptions(s).run
      client.sessionUpdate(sid, SessionUpdate.ConfigOptionUpdate(options)).run
      endTurn

  private given CanEqual[Json, Json] = CanEqual.derived
