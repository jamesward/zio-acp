package interop

import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The client side of every catalogue step (steps.json "steps"). */
final case class ClientCatalogue(s: ClientSteps):
  import s.*

  private given CanEqual[Json, Json] = CanEqual.derived

  val steps: Map[String, StepIO[String]] = Map(
    "init.initialize"          -> initInitialize,
    "init.agent-capabilities"  -> initAgentCapabilities,
    "init.client-capabilities" -> initClientCapabilities,
    "init.auth-methods"        -> init.flatMap(r => check(r.authMethods.exists(_.id == AuthMethodId("interop-auth")), s"authMethods ${r.authMethods}").as("authMethods has interop-auth")),
    "init.agent-info"          -> init.flatMap(r => check(r.agentInfo.exists(_.name.startsWith("interop-")), s"agentInfo ${r.agentInfo}").as(s"agentInfo ${r.agentInfo.map(_.name)}")),
    "init.config-boolean"      -> initConfigBoolean,
    "auth.authenticate"        -> conn.flatMap(c => rpc(c.agent.authenticate(AuthenticateRequest(AuthMethodId("interop-auth"))))).as("authenticated with interop-auth"),
    "auth.logout"              -> conn.flatMap(c => rpc(c.agent.logout(LogoutRequest()))).as("logged out"),
    "auth.logout-capability"   -> init.flatMap(r => check(r.agentCapabilities.auth.logout.isDefined, "no agentCapabilities.auth.logout").as("agentCapabilities.auth.logout present")),
    "auth.terminal" -> init.flatMap(r =>
      check(r.authMethods.exists { case t: AuthMethod.Terminal => t.id == AuthMethodId("interop-terminal-auth"); case _ => false }, s"authMethods ${r.authMethods}")
        .as("authMethods has the terminal method interop-terminal-auth"),
    ),
    "session.new"         -> conn.flatMap(newSession).map(sid => s"sessionId=${sid.value}"),
    "session.load"        -> sessionLoad,
    "session.load-replay" -> sessionLoadReplay,
    "session.resume"      -> sessionResume,
    "session.list"        -> sessionList,
    "session.close"       -> sessionClose,
    "session.delete" -> (for
      c <- conn
      sid <- newSession(c)
      _ <- rpc(c.agent.deleteSession(DeleteSessionRequest(sid)))
      _ <- rpc(c.agent.deleteSession(DeleteSessionRequest(SessionId("no-such-session"))))
    yield s"deleted ${sid.value} and no-such-session"),
    "session.multi"              -> sessionMulti,
    "update.agent_message_chunk" -> agentMessageChunk,
    "update.user_message_chunk"  -> emit("user_message_chunk") { case SessionUpdate.UserMessageChunk(c, _, _) => text(c) == "user-chunk" },
    "update.agent_thought_chunk" -> emit("agent_thought_chunk") { case SessionUpdate.AgentThoughtChunk(c, _, _) => text(c) == "thinking" },
    "update.tool_call" -> emit("tool_call") { case SessionUpdate.ToolCallStarted(t) =>
      t.toolCallId == ToolCallId("call-1") && t.title == "interop tool" && t.kind == ToolKind.Read && t.status == ToolCallStatus.Pending
    },
    "update.tool_call_update" -> toolCallUpdate,
    "update.tool_call-name"   -> toolCallName,
    "update.plan" -> emit("plan") { case SessionUpdate.Plan(entries, _) =>
      entries == List(
        PlanEntry("step one", PlanEntryPriority.High, PlanEntryStatus.Pending),
        PlanEntry("step two", PlanEntryPriority.Low, PlanEntryStatus.Completed),
      )
    },
    "update.available_commands_update" -> emit("available_commands_update") { case SessionUpdate.AvailableCommandsUpdate(cs, _) =>
      cs.map(c => (c.name, c.input.map(_.hint))) == List(("interop", Some("args")))
    },
    "update.current_mode_update" -> emit("current_mode_update") { case SessionUpdate.CurrentModeUpdate(m, _) => m == Fixtures.modeB },
    "update.config_option_update" -> emit("config_option_update") { case SessionUpdate.ConfigOptionUpdate(os, _) =>
      selectValue(os, "model").contains(Fixtures.modelB)
    },
    "update.session_info_update" -> emit("session_info_update") { case SessionUpdate.SessionInfoUpdate(title, _, _) => title.contains("interop title") },
    "update.usage_update" -> emit("usage_update") { case SessionUpdate.UsageUpdate(used, size, cost, _) =>
      used == 100 && size == 1000 && cost.exists(c => (c.amount - 0.01).abs < 1e-9 && c.currency == "USD")
    },
    "update.unknown"         -> updateUnknown,
    "stop.max_tokens"        -> stop("max_tokens", StopReason.MaxTokens),
    "stop.refusal"           -> stop("refusal", StopReason.Refusal),
    "stop.max_turn_requests" -> stop("max_turn_requests", StopReason.MaxTurnRequests),
    "enum.tool_call"         -> enumToolCall,
    "enum.plan"              -> enumPlan,
    "enum.stop"              -> enumStop,
    "enum.audience"          -> enumAudience,
    "mode.set"               -> modeSet,
    "config.on-new" -> (for
      c <- conn
      r <- rpc(c.agent.newSession(NewSessionRequest(dir.toString, Nil)))
      _ <- check(selectValue(r.configOptions.toList.flatten, "model").contains(Fixtures.modelA), s"configOptions ${r.configOptions}")
    yield "session/new configOptions: model at model-a"),
    "config.select"           -> configSet("model", SessionConfigValue.ValueId(Fixtures.modelB)),
    "config.boolean"          -> configSet("verbose", SessionConfigValue.Boolean(true)),
    "config.grouped"          -> configGrouped,
    "perm.selected"           -> permSelected,
    "perm.cancelled"          -> permCancelled,
    "fs.write"                -> fsWrite,
    "fs.read"                 -> fsReadFile.flatMap(f => fsRead(s"#fs read $f", _ == Fixtures.fsReadContent)),
    "fs.read-range"           -> fsReadFile.flatMap(f => fsRead(s"#fs read-range $f line=2 limit=1", _.trim == "line2")),
    "fs.read-missing"         -> fsRead(s"#fs read-missing ${dir.resolve("no-such-file.txt")}", _.startsWith("fs read error")),
    "term.run"                -> chunkStep("#terminal run echo hi", "terminal: hi exit=0", stepTimeout),
    "term.kill"               -> chunkStep("#terminal kill sleep 30", "terminal killed", 5.seconds),
    "elicit.form"             -> elicitForm,
    "elicit.complete"         -> elicitComplete,
    "cancel.prompt"           -> cancelPrompt,
    "cancel.prompt-while-cancelling" -> cancelWhileCancelling,
    "cancel.grace"            -> cancelGrace,
    "cancel.max-duration"     -> cancelMaxDuration,
    "cancel-request.client"   -> cancelRequestClient,
    "cancel-request.agent"    -> cancelRequestAgent,
    "cancel-request.unknown"  -> cancelRequestUnknown,
    "ext.agent-request"       -> chunkStep(s"#ext request ${Fixtures.extMethod}", "ext: {\"pong\":1}", stepTimeout),
    "ext.agent-notification"  -> extAgentNotification,
    "ext.client-request" -> (for
      c <- conn
      r <- rpc(c.agent.extMethod(Fixtures.extMethod, Fixtures.extParams))
      _ <- check(r == Fixtures.extResult, s"result ${r.toJson}")
    yield s"result ${r.toJson}"),
    "ext.client-notification" -> (for
      c <- conn
      _ <- rpc(c.agent.extNotification(Fixtures.extNotification, Fixtures.extParams))
      r <- chunkStep("#ext last-notification", s"ext last: ${Fixtures.extNotification}", stepTimeout)
    yield r),
    "meta.prompt"            -> metaPrompt,
    "meta.permission"        -> metaPermission,
    "error.method-not-found" -> methodNotFound,
    "big.prompt-1m"          -> bigPrompt(1 << 20),
    "big.update-1m"          -> bigUpdate(1 << 20),
    "big.prompt-8m"          -> bigPrompt(8 << 20),
    "big.update-8m"          -> bigUpdate(8 << 20),
    "stdio.eof-exit"         -> StdioEofExit(agentCmd, stepTimeout).step,
    "conn.close"             -> main.getAndSet(None).flatMap(ZIO.foreachDiscard(_)(_.close)).as("closed"),
  )

  def initInitialize: StepIO[String] =
    defer:
      val c = Conn.open(agentCmd, counters).mapError(e => StepFailure(s"could not start the agent: $e")).run
      main.set(Some(c)).run
      val r = rpc(c.initialize).run
      initResponse.set(Some(r)).run
      check(r.protocolVersion == ProtocolVersion.V1, s"protocolVersion ${r.protocolVersion.value}").run
      s"protocolVersion=1 agentInfo=${r.agentInfo.map(_.name)}"

  def initAgentCapabilities: StepIO[String] =
    defer:
      val caps = init.run.agentCapabilities
      check(caps.loadSession, "loadSession false").run
      val sc = caps.sessionCapabilities
      val missing = List("list" -> sc.list, "resume" -> sc.resume, "close" -> sc.close, "delete" -> sc.delete).collect { case (n, None) => n }
      check(missing.isEmpty, s"sessionCapabilities missing $missing").run
      "loadSession true; sessionCapabilities list, resume, close, delete"

  def echoCaps: StepIO[Json] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#echo-caps")).run
      await(c.chunks(sid).map(_.nonEmpty), ZIO.succeed("no chunk with the echoed capabilities")).run
      val json = c.chunks(sid).run.mkString
      ZIO.fromEither(json.fromJson[Json]).mapError(e => StepFailure(s"echoed capabilities are not JSON: $e")).run

  def path(json: Json, keys: String*): Option[Json] =
    keys.foldLeft(Option(json))((cur, k) => cur.flatMap { case o: Json.Obj => o.get(k); case _ => None })

  def initClientCapabilities: StepIO[String] =
    echoCaps.flatMap: caps =>
      val ok = List(path(caps, "fs", "readTextFile"), path(caps, "fs", "writeTextFile"), path(caps, "terminal")).forall(_.contains(Json.Bool(true)))
      check(ok, s"echoed capabilities ${caps.toJson}").as(s"echoed ${caps.toJson}")

  def initConfigBoolean: StepIO[String] =
    echoCaps.flatMap: caps =>
      check(path(caps, "session", "configOptions", "boolean").isDefined, s"no session.configOptions.boolean in ${caps.toJson}")
        .as("echoed session.configOptions.boolean")

  def sessionLoad: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      prompt(c, sid, "hello load").run
      rpc(c.agent.loadSession(LoadSessionRequest(sid, dir.toString, Nil))).run
      val before = c.chunks(sid).run.size
      promptEndTurn(prompt(c, sid, "after load")).run
      await(c.chunks(sid).map(_.size > before), ZIO.succeed("no agent_message_chunk after the load")).run
      s"loaded ${sid.value}; prompt after load end_turn"

  def sessionLoadReplay: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      prompt(c, sid, "replay me").run
      await(c.chunks(sid).map(_.mkString == "echo: replay me"), ZIO.succeed("the prompt's own chunks did not arrive")).run
      val from = c.updates(sid).run.size
      rpc(c.agent.loadSession(LoadSessionRequest(sid, dir.toString, Nil))).run
      val after = c.updates(sid).map(_.drop(from))
      val replayed = after.map: us =>
        us.exists { case SessionUpdate.UserMessageChunk(b, _, _) => text(b) == "replay me"; case _ => false } &&
          us.exists { case SessionUpdate.AgentMessageChunk(b, _, _) => text(b).contains("replay me"); case _ => false }
      await(replayed, after.map(us => s"replayed updates ${us.map(Conn.kind)}"), replayGrace).run
      s"replayed ${after.run.size} updates"

  def sessionResume: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      prompt(c, sid, "before resume").run
      await(c.chunks(sid).map(_.mkString == "echo: before resume"), ZIO.succeed("the prompt's own chunks did not arrive")).run
      val from = c.updates(sid).run.size
      rpc(c.agent.resumeSession(ResumeSessionRequest(sid, dir.toString))).run
      ZIO.sleep(1.second).run
      val during = c.updates(sid).run.drop(from)
      check(during.isEmpty, s"resume replayed ${during.map(Conn.kind)}").run
      promptEndTurn(prompt(c, sid, "after resume")).run
      "resumed without replay; prompt after resume end_turn"

  def sessionList: StepIO[String] =
    defer:
      val c = conn.run
      val cwd = ZIO.attemptBlocking(Files.createDirectories(dir.resolve("list")).nn.toString).mapError(e => StepFailure(e.toString)).run
      val sid = rpc(c.newSession(cwd)).run
      listPage(c, cwd, sid, None, 1).run

  def listPage(c: Conn, cwd: String, sid: SessionId, cursor: Option[String], n: Int): StepIO[String] =
    rpc(c.agent.listSessions(ListSessionsRequest(Some(cwd), cursor))).flatMap: r =>
      r.sessions.find(_.sessionId == sid) match
        case Some(info) => check(info.cwd == cwd, s"listed with cwd ${info.cwd}").as(s"listed ${sid.value} with cwd ${info.cwd} on page $n")
        case None =>
          r.nextCursor match
            case Some(next) if n < 10 => listPage(c, cwd, sid, Some(next), n + 1)
            case _                    => fail(s"${sid.value} not listed")

  def sessionClose: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val slow = c.prompt(sid, "#slow").fork.run
      await(c.chunks(sid).map(_.contains("tick")), ZIO.succeed("no tick"), stepTimeout).run
      rpc(c.agent.closeSession(CloseSessionRequest(sid))).run
      val t0 = Clock.nanoTime.run
      val ended = slow.join.either.timeout(5.seconds).run match
        case None                     => fail("TIMEOUT: the #slow prompt did not end within 5 s of the close").run
        case Some(Right(r)) if r.stopReason == StopReason.Cancelled => "cancelled"
        case Some(Right(r))           => fail(s"the #slow prompt answered ${r.stopReason}").run
        case Some(Left(e))            => s"error ${e.code.value}"
      val ms = (Clock.nanoTime.run - t0) / 1000000
      c.prompt(sid, "after close").either.run match
        case Right(r) => fail(s"the prompt after close answered ${r.stopReason}").run
        case Left(e)  => s"close {}; #slow ended ($ended) $ms ms after; prompt after close failed: ${e.code.value}"

  def sessionMulti: StepIO[String] =
    defer:
      val c = conn.run
      val a = newSession(c).run
      val b = newSession(c).run
      val (ra, rb) = prompt(c, a, "multi A").zipPar(prompt(c, b, "multi B")).run
      check(ra.stopReason == StopReason.EndTurn && rb.stopReason == StopReason.EndTurn, s"stopReasons ${ra.stopReason}, ${rb.stopReason}").run
      await(
        c.chunks(a).zipWith(c.chunks(b))((ca, cb) => ca.mkString == "echo: multi A" && cb.mkString == "echo: multi B"),
        c.chunks(a).zipWith(c.chunks(b))((ca, cb) => s"chunks A $ca, B $cb"),
      ).run
      "both end_turn; each session got only its own chunks"

  def agentMessageChunk: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "hello")).run
      await(c.chunks(sid).map(_.mkString == "echo: hello"), c.chunks(sid).map(cs => s"chunks $cs do not spell \"echo: hello\"")).run
      "end_turn; chunks spell \"echo: hello\""

  def toolCallUpdate: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#emit tool_call_update")).run
      val ordered = c.updates(sid).map: us =>
        val call = us.indexWhere { case SessionUpdate.ToolCallStarted(t) => t.toolCallId == ToolCallId("call-1"); case _ => false }
        val update = us.indexWhere {
          case SessionUpdate.ToolCallUpdated(u) =>
            u.toolCallId == ToolCallId("call-1") && u.status.contains(ToolCallStatus.Completed) &&
            u.content.exists(_.exists { case ToolCallContent.Content(b, _) => text(b) == "tool output"; case _ => false })
          case _ => false
        }
        call >= 0 && update > call
      await(ordered, c.updates(sid).map(us => s"updates $us")).run
      "tool_call call-1, then tool_call_update completed \"tool output\""

  def toolCallName: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      prompt(c, sid, "#emit tool_call name=read_file").run
      val names = c.updates(sid).map(_.collect { case SessionUpdate.ToolCallStarted(t) => t.name })
      await(names.map(_.nonEmpty), ZIO.succeed("no tool_call")).run
      val ns = names.run
      check(ns.contains(Some("read_file")), s"tool_call names $ns").run
      "tool_call name read_file"

  def updateUnknown: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#emit unknown")).run
      await(c.chunks(sid).map(_.contains("after-unknown")), c.chunks(sid).map(cs => s"no chunk \"after-unknown\" in $cs")).run
      val surfaced = c.updates(sid).run.exists(_.isInstanceOf[SessionUpdate.Unknown])
      s"after-unknown arrived; end_turn (unknown update ${if surfaced then "surfaced as SessionUpdate.Unknown" else "dropped"})"

  def stop(reason: String, expected: StopReason): StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val r = prompt(c, sid, s"#stop $reason").run
      check(r.stopReason == expected, s"stopReason ${r.stopReason}").run
      s"stopReason $reason"

  def enumToolCall: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#enum tool_call")).run
      await(c.chunks(sid).map(_.contains("after-enum")), c.chunks(sid).map(cs => s"no chunk \"after-enum\" in $cs")).run
      val updates = c.updates(sid).run
      val call = ClientCatalogue.toolCall(updates, ToolCallId("call-enum"))
      call match
        case None => fail("no tool_call call-enum").run
        case Some(t) =>
          check(t.status == ToolCallStatus.Unknown(Fixtures.futureStatus), s"status ${t.status}").run
          check(t.kind == ToolKind.Unknown("interop_future_kind") || t.kind == ToolKind.Other, s"kind ${t.kind}").run
          s"tool_call status ${t.status}, kind ${t.kind}; after-enum; end_turn"

  def enumPlan: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#enum plan")).run
      await(c.chunks(sid).map(_.contains("after-enum")), c.chunks(sid).map(cs => s"no chunk \"after-enum\" in $cs")).run
      val updates = c.updates(sid).run
      val plan = ClientCatalogue.plan(updates)
      val expected = List(PlanEntry("future entry", PlanEntryPriority.Unknown("interop_future_priority"), PlanEntryStatus.Unknown(Fixtures.futureStatus)))
      check(plan.contains(expected), s"plan $plan").run
      "plan entry priority interop_future_priority, status interop_future_status kept; end_turn"

  def enumStop: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val r = prompt(c, sid, "#enum stop").run
      check(r.stopReason == StopReason.Unknown("interop_future_stop"), s"stopReason ${r.stopReason}").run
      promptEndTurn(prompt(c, sid, "after enum")).run
      "stopReason interop_future_stop kept; \"after enum\" end_turn"

  def enumAudience: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val block = ContentBlock.Text("#enum audience", Some(Annotations(audience = Some(List(Role.User, Role.Other("interop_future_role"))))))
      promptEndTurn(rpc(c.agent.prompt(PromptRequest(sid, List(block))))).run
      await(c.chunks(sid).map(_.contains("audience: user,interop_future_role")), c.chunks(sid).map(cs => s"chunks $cs")).run
      "audience: user,interop_future_role; end_turn"

  def modeSet: StepIO[String] =
    defer:
      val c = conn.run
      val n = rpc(c.agent.newSession(NewSessionRequest(dir.toString, Nil))).run
      check(n.modes.exists(_.availableModes.exists(_.id == Fixtures.modeB)), s"modes ${n.modes}").run
      rpc(c.agent.setSessionMode(SetSessionModeRequest(n.sessionId, Fixtures.modeB))).run
      "session/new listed interop-mode-b; set_mode answered"

  def configSet(id: String, value: SessionConfigValue): StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val r = rpc(c.agent.setSessionConfigOption(SetSessionConfigOptionRequest(sid, SessionConfigId(id), value))).run
      val ok = value match
        case SessionConfigValue.ValueId(v) => selectValue(r.configOptions, id).contains(v)
        case SessionConfigValue.Boolean(b) =>
          r.configOptions.exists { case o: SessionConfigOption.Boolean => o.id.value == id && o.currentValue == b; case _ => false }
      check(ok, s"configOptions ${r.configOptions}").run
      s"$id set in the full list of ${r.configOptions.size}"

  def configGrouped: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#config grouped")).run
      val update = c.updates(sid).map(_.collectFirst { case SessionUpdate.ConfigOptionUpdate(os, _) => os })
      await(update.map(_.isDefined), ZIO.succeed("no config_option_update")).run
      val options = update.run.toList.flatten
      val effort = ClientCatalogue.select(options, "effort")
      effort match
        case None => fail("no select effort").run
        case Some(e) =>
          val groups = e.options match
            case SessionConfigSelectOptions.Grouped(gs) => gs.map(_.group.value)
            case _                                      => Nil
          check(groups == List("fast", "deep"), s"effort options ${e.options}").run
          val values = e.options.all.map(_.value.value)
          check(values == List("effort-low", "effort-medium", "effort-high"), s"effort values $values").run
          val set = rpc(
            c.agent.setSessionConfigOption(SetSessionConfigOptionRequest(sid, SessionConfigId("effort"), SessionConfigValue.ValueId(SessionConfigValueId("effort-high")))),
          ).run
          check(selectValue(set.configOptions, "effort").contains(SessionConfigValueId("effort-high")), s"configOptions ${set.configOptions}").run
          s"effort grouped fast/deep, values $values; set to effort-high"

  def permSelected: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#permission allow")).run
      await(c.chunks(sid).map(_.contains("permission: selected allow")), c.chunks(sid).map(cs => s"chunks $cs")).run
      val asked = c.received.permissions.get.map(_.get(sid).fold(0)(identity)).run
      check(asked == 1, s"$asked permission requests, expected 1").run
      "one permission request; selected allow; end_turn"

  def permCancelled: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      c.received.cancelOnPermission.update(_ + sid).run
      val r = prompt(c, sid, "#permission hold").run
      val at = c.received.cancelSentAt.get.map(_.get(sid)).run
      at match
        case None => fail("no permission request arrived, so no cancel was sent").run
        case Some(sent) =>
          val ms = (Clock.nanoTime.run - sent) / 1000000
          check(r.stopReason == StopReason.Cancelled, s"stopReason ${r.stopReason}").run
          check(ms <= 5000, s"cancelled $ms ms after the cancel").run
          s"cancel sent on the permission request; stopReason cancelled $ms ms later"

  def fsWrite: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val file = dir.resolve("fs-write.txt")
      promptEndTurn(prompt(c, sid, s"#fs write $file interop write")).run
      await(c.chunks(sid).map(_.contains("fs write ok")), c.chunks(sid).map(cs => s"no chunk \"fs write ok\" in $cs")).run
      val content = ZIO.attemptBlocking(Files.readString(file)).mapError(e => StepFailure(s"$file was not written")).run
      check(content == "interop write", s"file content \"$content\"").run
      "written through the client: \"interop write\""

  def fsReadFile: StepIO[String] =
    val file = dir.resolve("fs-read.txt")
    ZIO.attemptBlocking(Files.writeString(file, Fixtures.fsReadContent, StandardCharsets.UTF_8)).mapError(e => StepFailure(e.toString)).as(file.toString)

  def fsRead(text: String, matches: String => Boolean): StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, text)).run
      await(c.chunks(sid).map(_.exists(matches)), c.chunks(sid).map(cs => s"chunks $cs")).run
      s"chunk ${c.chunks(sid).run}"

  def elicitForm: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#elicit form")).run
      await(c.chunks(sid).map(_.exists(_.startsWith("elicit: "))), c.chunks(sid).map(cs => s"chunks $cs")).run
      val chunk = c.chunks(sid).run.find(_.startsWith("elicit: ")).fold("")(identity)
      val content = chunk.stripPrefix("elicit: accept ").fromJson[Json]
      check(chunk.startsWith("elicit: accept ") && content == Right(Json.Obj("name" -> Json.Str("interop"))), s"chunk $chunk").run
      s"chunk $chunk"

  def elicitComplete: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "#elicit url")).run
      await(c.received.completedElicitations.get.map(_.contains(ElicitationId("elic-1"))), ZIO.succeed("no elicitation/complete for elic-1")).run
      "elicitation/complete elic-1 arrived"

  def cancelPrompt: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val slow = c.prompt(sid, "#slow").fork.run
      await(c.chunks(sid).map(_.contains("tick")), ZIO.succeed("no tick"), stepTimeout).run
      rpc(c.agent.cancel(sid)).run
      val t0 = Clock.nanoTime.run
      val r = rpc(slow.join).timeoutFail(StepFailure("TIMEOUT: the prompt did not answer within 5 s of the cancel"))(5.seconds).run
      val ms = (Clock.nanoTime.run - t0) / 1000000
      check(r.stopReason == StopReason.Cancelled, s"stopReason ${r.stopReason}").run
      s"stopReason cancelled $ms ms after the cancel"

  def cancelWhileCancelling: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val slow = c.prompt(sid, "#slow grace=1000").fork.run
      await(c.chunks(sid).map(_.contains("tick")), ZIO.succeed("no tick"), stepTimeout).run
      rpc(c.agent.cancel(sid)).run
      val during = c.prompt(sid, "during cancel").either.run match
        case Right(r) => fail(s"\"during cancel\" was accepted: ${r.stopReason}").run
        case Left(e) =>
          check(e.code == ErrorCode.InvalidRequest, s"\"during cancel\" failed with ${e.code.value}, expected -32600").run
          e.message
      val first = rpc(slow.join).timeoutFail(StepFailure("TIMEOUT: the cancelled prompt did not answer within 5 s"))(5.seconds).run
      check(first.stopReason == StopReason.Cancelled, s"the cancelled prompt answered ${first.stopReason}").run
      promptEndTurn(prompt(c, sid, "after cancel")).run
      s"first cancelled; \"during cancel\" rejected ($during); \"after cancel\" end_turn"

  def cancelGrace: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val hang = c.prompt(sid, "#hang").fork.run
      ZIO.sleep(200.millis).run
      rpc(c.agent.cancel(sid)).run
      val t0 = Clock.nanoTime.run
      val r = rpc(hang.join).timeoutFail(StepFailure("TIMEOUT: no answer within 6 s of the cancel"))(6.seconds).run
      check(r.stopReason == StopReason.Cancelled, s"stopReason ${r.stopReason}").run
      s"the SDK answered cancelled ${(Clock.nanoTime.run - t0) / 1000000} ms after the cancel"

  def cancelMaxDuration: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val t0 = Clock.nanoTime.run
      c.prompt(sid, "#hang").either.timeout(11.seconds).run match
        case None           => fail("TIMEOUT: no answer within 11 s").run
        case Some(Right(r)) => fail(s"the prompt answered ${r.stopReason}, expected error -32800").run
        case Some(Left(e)) =>
          val ms = (Clock.nanoTime.run - t0) / 1000000
          check(e.code == ErrorCode.RequestCancelled, s"the prompt failed with ${e.code.value}, expected -32800").run
          check(ms >= 7000, s"-32800 after $ms ms, before the 8 s maxPromptDuration").run
          promptEndTurn(prompt(c, sid, "after max-duration")).run
          s"-32800 after $ms ms; \"after max-duration\" end_turn"

  def cancelRequestClient: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val cancelAt = Ref.make(Option.empty[Long]).run
      val firstTick = c.chunks(sid).map(_.contains("tick")).repeatUntil(identity).delay(20.millis) *>
        Clock.nanoTime.flatMap(t => cancelAt.set(Some(t)))
      val outcome = c.agent
        .call(Methods.Agent.Prompt, PromptRequest(sid, List(ContentBlock.text("#slow"))), cancelWhen = firstTick)
        .either
        .timeout(12.seconds)
        .run match
        case None => fail("TIMEOUT: the prompt did not end").run
        case Some(Right(r)) =>
          check(r.stopReason == StopReason.Cancelled, s"the cancelled prompt answered ${r.stopReason}").run
          "stopReason cancelled"
        case Some(Left(e)) =>
          check(e.code == ErrorCode.RequestCancelled, s"the cancelled prompt failed with ${e.code.value}, expected -32800").run
          "error -32800"
      val at = cancelAt.get.someOrFail(StepFailure("the prompt ended before its first tick")).run
      val ms = (Clock.nanoTime.run - at) / 1000000
      check(ms < 5000, s"the prompt ended $ms ms after $$/cancel_request").run
      s"$outcome $ms ms after $$/cancel_request"

  def cancelRequestAgent: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val t0 = Clock.nanoTime.run
      val r = prompt(c, sid, s"#fs read-slow ${dir.resolve("slow.txt")}").run
      val ms = (Clock.nanoTime.run - t0) / 1000000
      endTurn(r).run
      val chunks = c.chunks(sid).run
      check(chunks.contains("cancel-request sent"), "no chunk \"cancel-request sent\"").run
      check(ms < 5000, s"the prompt took $ms ms").run
      s"\"cancel-request sent\" and end_turn after $ms ms"

  def cancelRequestUnknown: StepIO[String] =
    defer:
      val c = conn.run
      // $/cancel_request for an id the agent never saw, and for initialize's id (0), answered long ago
      ZIO.foreachDiscard(List(RequestId.Number(999999), RequestId.Number(0))): id =>
        rpc(c.agent.extNotification(Methods.Protocol.CancelRequest.name, Json.Obj("requestId" -> id.asJson)))
      .run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, "after cancel-request")).run
      "cancelled 999999 and the answered initialize; the next prompt answered end_turn"

  def extAgentNotification: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, s"#ext notify ${Fixtures.extNotification}")).run
      await(c.received.extNotes.get.map(_.contains(Fixtures.extParams)), c.received.extNotes.get.map(ns => s"_interop/note notifications $ns")).run
      "_interop/note arrived with {n: 1}"

  def metaPrompt: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      val r = rpc(c.agent.prompt(PromptRequest(sid, List(ContentBlock.text("#meta")), Some(Fixtures.meta)))).run
      check(Fixtures.hasMeta(r.meta), s"PromptResponse _meta ${r.meta}").run
      val chunk = c.updates(sid).map(_.exists { case SessionUpdate.AgentMessageChunk(b, _, m) => text(b) == "meta" && Fixtures.hasMeta(m); case _ => false })
      await(chunk, c.updates(sid).map(us => s"updates $us")).run
      "_meta interop=m1 on the chunk and the PromptResponse"

  def metaPermission: StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      prompt(c, sid, "#permission allow meta").run
      val meta = c.received.permissionMeta.get.map(_.get(sid)).run
      check(Fixtures.hasMeta(meta), s"permission request _meta $meta").run
      "the permission request carried _meta interop == m1"

  def methodNotFound: StepIO[String] =
    defer:
      val c = conn.run
      c.agent.extMethod("interop/no_such_method", Json.Obj()).either.run match
        case Right(r) => fail(s"interop/no_such_method answered ${r.toJson}").run
        case Left(e)  => check(e.code == ErrorCode.MethodNotFound, s"failed with ${e.code.value}, expected -32601").run
      val sid = newSession(c).run
      s"interop/no_such_method -32601; session/new after it: ${sid.value}"

  def bigPrompt(n: Int): StepIO[String] = chunkStep("#len " + ("x" * n), s"len=$n", stepTimeout)

  def bigUpdate(n: Int): StepIO[String] =
    defer:
      val c = conn.run
      val sid = newSession(c).run
      promptEndTurn(prompt(c, sid, s"#big $n")).run
      await(c.chunks(sid).map(_.exists(_.length == n)), c.chunks(sid).map(cs => s"chunk lengths ${cs.map(_.length)}")).run
      s"one agent_message_chunk of $n characters"

object ClientCatalogue:
  def toolCall(updates: Chunk[SessionUpdate], id: ToolCallId): Option[ToolCall] =
    updates.collectFirst { case SessionUpdate.ToolCallStarted(t) if t.toolCallId == id => t }

  def plan(updates: Chunk[SessionUpdate]): Option[List[PlanEntry]] =
    updates.collectFirst { case SessionUpdate.Plan(entries, _) => entries }

  def select(options: List[SessionConfigOption], id: String): Option[SessionConfigOption.Select] =
    options.collectFirst { case s: SessionConfigOption.Select if s.id.value == id => s }
