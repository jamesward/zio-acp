package interop

import com.jamesward.zioacp.*
import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*
import zio.json.ast.Json

/** One session, shared by every connection of the process (`http.reconnect` loads a session on a new connection). */
final case class SessionState(
  id: SessionId,
  cwd: String,
  closed: Boolean = false,
  mode: SessionModeId = Fixtures.modeA,
  model: SessionConfigValueId = Fixtures.modelA,
  verbose: Boolean = false,
  grouped: Boolean = false,
  effort: SessionConfigValueId = SessionConfigValueId("effort-low"),
  history: Chunk[SessionUpdate] = Chunk.empty,
  running: Int = 0,
  closedWhileRunning: Boolean = false,
)

/**
 * The Scala interop agent of the cross-SDK step catalogue, driven by directives in the prompt text.
 *
 * {{{
 *   agent --transport stdio
 * }}}
 */
object InteropAgent extends ZIOAppDefault:
  override val bootstrap = AcpAgent.stderrLogging ++ Runtime.enableLoomBasedBlockingExecutor

  def run =
    defer:
      val args = getArgs.run.toList
      args match
        case List("--transport", "stdio") =>
          val sessions = Ref.make(Map.empty[SessionId, SessionState]).run
          val counter = Ref.make(0).run
          AcpAgent.serveStdio(AgentOptions(cancelGrace = 1.second))(client => Agents.make(client, sessions, counter)).run
          Step.log("stdin closed; exiting").run
        case _ =>
          Step.log("usage: agent --transport stdio").run
          exit(ExitCode(2)).run

object Agents:
  def make(client: ClientConnection, sessions: Ref[Map[SessionId, SessionState]], counter: Ref[Int]): UIO[Agent] =
    defer:
      InteropAgentImpl(client, sessions, counter, Ref.make(Option.empty[InitializeRequest]).run, Ref.make("none").run)

final case class InteropAgentImpl(
  client: ClientConnection,
  sessions: Ref[Map[SessionId, SessionState]],
  counter: Ref[Int],
  init: Ref[Option[InitializeRequest]],
  lastExtNotification: Ref[String],
) extends Agent:

  def capabilities: UIO[Option[ClientCapabilities]] = init.get.map(_.map(_.clientCapabilities))

  def booleanOptions: UIO[Boolean] = capabilities.map(_.exists(_.session.exists(_.configOptions.exists(_.boolean.isDefined))))

  def configOptions(s: SessionState): UIO[List[SessionConfigOption]] =
    booleanOptions.map: withBoolean =>
      List(Fixtures.modelOption(s.model)) ++
        Option.when(withBoolean)(Fixtures.verboseOption(s.verbose)) ++
        Option.when(s.grouped)(Fixtures.effortOption(s.effort))

  def known(sessionId: SessionId): IO[RpcError, SessionState] =
    sessions.get.map(_.get(sessionId)).flatMap:
      case Some(s) if s.closed => ZIO.fail(RpcError.invalidParams(s"session ${sessionId.value} is closed"))
      case Some(s)             => ZIO.succeed(s)
      case None                => ZIO.fail(RpcError.invalidParams(s"unknown session ${sessionId.value}"))

  def update(sessionId: SessionId)(f: SessionState => SessionState): UIO[Unit] =
    sessions.update(m => m.get(sessionId).fold(m)(s => m.updated(sessionId, f(s))))

  def initialize(request: InitializeRequest): IO[RpcError, InitializeResponse] =
    defer:
      init.set(Some(request)).run
      val terminalAuth = Option.when(request.clientCapabilities.auth.terminal)(Fixtures.terminalAuth)
      InitializeResponse(
        ProtocolVersion.V1,
        Fixtures.agentCapabilities,
        Fixtures.agentAuth :: terminalAuth.toList,
        Some(Implementation("interop-scala-agent", "1")),
      )

  override def authenticate(request: AuthenticateRequest): IO[RpcError, AuthenticateResponse] =
    defer:
      val t0 = Clock.nanoTime.run
      val ok = request.methodId == AuthMethodId("interop-auth")
      Step.agent("auth.authenticate", ok, t0, s"methodId ${request.methodId.value}").run
      if ok then AuthenticateResponse() else ZIO.fail(RpcError.invalidParams(s"unknown auth method ${request.methodId.value}")).run

  override def logout(request: LogoutRequest): IO[RpcError, LogoutResponse] = ZIO.succeed(LogoutResponse())

  def newSession(request: NewSessionRequest): IO[RpcError, NewSessionResponse] =
    defer:
      val n = counter.updateAndGet(_ + 1).run
      val state = SessionState(SessionId(s"scala-sess-$n"), request.cwd)
      sessions.update(_.updated(state.id, state)).run
      NewSessionResponse(state.id, Some(Fixtures.modes(state.mode)), Some(configOptions(state).run))

  override def loadSession(request: LoadSessionRequest): IO[RpcError, LoadSessionResponse] =
    defer:
      val s = known(request.sessionId).run
      ZIO.foreachDiscard(s.history)(client.sessionUpdate(s.id, _)).run
      LoadSessionResponse(Some(Fixtures.modes(s.mode)), Some(configOptions(s).run))

  override def resumeSession(request: ResumeSessionRequest): IO[RpcError, ResumeSessionResponse] =
    defer:
      val s = known(request.sessionId).run
      ResumeSessionResponse(Some(Fixtures.modes(s.mode)), Some(configOptions(s).run))

  override def listSessions(request: ListSessionsRequest): IO[RpcError, ListSessionsResponse] =
    sessions.get.map: all =>
      val listed = all.values.filter(s => !s.closed && request.cwd.forall(_ == s.cwd)).map(s => SessionInfo(s.id, s.cwd))
      ListSessionsResponse(listed.toList)

  override def closeSession(request: CloseSessionRequest): IO[RpcError, CloseSessionResponse] =
    defer:
      val t0 = Clock.nanoTime.run
      sessions.get.map(_.get(request.sessionId)).run match
        case None =>
          Step.agent("session.close", false, t0, s"close named an unknown session ${request.sessionId.value}").run
          ZIO.fail(RpcError.invalidParams(s"unknown session ${request.sessionId.value}")).run
        case Some(s) =>
          // the library has already signalled the running turn's cancellation
          update(s.id)(_.copy(closed = true, closedWhileRunning = s.running > 0)).run
          val detail = if s.running > 0 then s"closed ${s.id.value}; its running turn was cancelled" else s"closed ${s.id.value} with no running turn"
          Step.agent("session.close", s.running > 0, t0, detail).run
          CloseSessionResponse()

  override def deleteSession(request: DeleteSessionRequest): IO[RpcError, DeleteSessionResponse] =
    sessions.update(_ - request.sessionId).as(DeleteSessionResponse())

  override def setSessionMode(request: SetSessionModeRequest): IO[RpcError, SetSessionModeResponse] =
    defer:
      known(request.sessionId).run
      val t0 = Clock.nanoTime.run
      val knownMode = request.modeId == Fixtures.modeA || request.modeId == Fixtures.modeB
      Step.agent("mode.set", request.modeId == Fixtures.modeB, t0, s"set_mode ${request.modeId.value}").run
      if knownMode then
        update(request.sessionId)(_.copy(mode = request.modeId)).run
        SetSessionModeResponse()
      else ZIO.fail(RpcError.invalidParams(s"unknown mode ${request.modeId.value}")).run

  override def setSessionConfigOption(request: SetSessionConfigOptionRequest): IO[RpcError, SetSessionConfigOptionResponse] =
    defer:
      val s = known(request.sessionId).run
      val t0 = Clock.nanoTime.run
      val withBoolean = booleanOptions.run
      val updated: Option[SessionState] = (request.configId.value, request.value) match
        case ("model", SessionConfigValue.ValueId(v)) if v == Fixtures.modelA || v == Fixtures.modelB => Some(s.copy(model = v))
        case ("effort", SessionConfigValue.ValueId(v)) if s.grouped && Fixtures.effortOption(s.effort).options.all.exists(_.value == v) =>
          Some(s.copy(effort = v))
        case ("verbose", SessionConfigValue.Boolean(on)) if withBoolean => Some(s.copy(verbose = on))
        case _ => None
      updated match
        case Some(next) =>
          if request.configId.value == "effort" then
            Step.agent("config.grouped", next.effort == SessionConfigValueId("effort-high"), t0, s"effort set to ${next.effort.value}").run
          update(s.id)(_ => next).run
          SetSessionConfigOptionResponse(configOptions(next).run)
        case None =>
          ZIO.fail(RpcError.invalidParams(s"unknown config option ${request.configId.value}=${request.value}")).run

  override def extMethod(method: String, params: Json): IO[RpcError, Json] =
    if method == Fixtures.extMethod then ZIO.succeed(Fixtures.extResult) else ZIO.fail(RpcError.methodNotFound(method))

  override def extNotification(method: String, params: Json): UIO[Unit] =
    Step.log(s"[agent] extension notification $method").zipRight(lastExtNotification.set(method))

  def prompt(request: PromptRequest, turn: PromptTurn): IO[RpcError, PromptResponse] =
    defer:
      val s = known(request.sessionId).run
      update(s.id)(st => st.copy(running = st.running + 1)).run
      Directives(this, s, request, turn).respond.ensuring(update(s.id)(st => st.copy(running = st.running - 1))).run
