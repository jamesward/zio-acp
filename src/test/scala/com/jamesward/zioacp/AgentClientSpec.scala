package com.jamesward.zioacp

import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*
import zio.json.ast.Json
import zio.test.*

object AgentClientSpec extends ZIOSpecDefault:

  private given CanEqual[Json, Json] = CanEqual.derived

  /** A client that records updates and answers permission requests with the first option. */
  final case class RecordingClient(updates: Ref[Chunk[SessionNotification]], permission: IO[RpcError, RequestPermissionResponse])
      extends Client:
    def sessionUpdate(notification: SessionNotification): UIO[Unit] = updates.update(_ :+ notification)
    def requestPermission(request: RequestPermissionRequest): IO[RpcError, RequestPermissionResponse] = permission
    override def readTextFile(request: ReadTextFileRequest): IO[RpcError, ReadTextFileResponse] =
      ZIO.succeed(ReadTextFileResponse(s"contents of ${request.path}"))
    override def extMethod(method: String, params: Json): IO[RpcError, Json] =
      ZIO.succeed(Json.Obj("method" -> Json.Str(method), "params" -> params))

  def text(update: SessionUpdate): Option[String] =
    update match
      case SessionUpdate.AgentMessageChunk(ContentBlock.Text(text, _, _), _, _) => Some(text)
      case _                                                                     => None

  /** Echoes the prompt; "#permission" asks for permission, "#read" reads a file, "#slow" waits for a cancel. */
  final case class EchoAgent(client: ClientConnection) extends Agent:
    def initialize(request: InitializeRequest): IO[RpcError, InitializeResponse] =
      ZIO.succeed(InitializeResponse(ProtocolVersion.V1, agentInfo = Some(Implementation("echo", "1"))))

    def newSession(request: NewSessionRequest): IO[RpcError, NewSessionResponse] =
      ZIO.succeed(NewSessionResponse(SessionId("s-" + request.cwd)))

    def prompt(request: PromptRequest, turn: PromptTurn): IO[RpcError, PromptResponse] =
      val say = (s: String) => client.sessionUpdate(request.sessionId, SessionUpdate.AgentMessageChunk(ContentBlock.text(s)))
      request.prompt match
        case ContentBlock.Text("#permission", _, _) :: _ =>
          defer:
            val response = client
              .requestPermission(
                RequestPermissionRequest(
                  request.sessionId,
                  ToolCallUpdate(ToolCallId("t")),
                  List(PermissionOption(PermissionOptionId("allow"), "Allow", PermissionOptionKind.AllowOnce)),
                ),
              )
              .run
            response.outcome match
              case RequestPermissionOutcome.Selected(id, _) => say(s"selected ${id.value}").run
              case RequestPermissionOutcome.Cancelled       => say("permission cancelled").run
            val stopReason: StopReason = if turn.isCancelled.run then StopReason.Cancelled else StopReason.EndTurn
            PromptResponse(stopReason)
        case ContentBlock.Text("#read", _, _) :: _ =>
          client.readTextFile(ReadTextFileRequest(request.sessionId, "/a.txt")).flatMap(r => say(r.content)).as(PromptResponse(StopReason.EndTurn))
        case ContentBlock.Text("#slow", _, _) :: _ =>
          (say("tick") *> turn.awaitCancel).as(PromptResponse(StopReason.Cancelled))
        case ContentBlock.Text("#hang", _, _) :: _ =>
          ZIO.never
        case ContentBlock.Text(text, _, _) :: _ =>
          (say("echo: ") *> say(text)).as(PromptResponse(StopReason.EndTurn))
        case _ =>
          ZIO.fail(RpcError.invalidParams("expected text"))

    override def extMethod(method: String, params: Json): IO[RpcError, Json] =
      client.extMethod(method, params)

  /** An echo agent and a recording client connected in memory. */
  def connected(
    permission: IO[RpcError, RequestPermissionResponse] =
      ZIO.succeed(RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("allow")))),
    options: AgentOptions = AgentOptions(),
  ): ZIO[Scope, Nothing, (AgentConnection, Ref[Chunk[SessionNotification]])] =
    defer:
      val (clientSide, agentSide) = Transport.pipe.run
      AcpAgent.serve(agentSide, options)(c => ZIO.succeed(EchoAgent(c))).forkScoped.run
      val updates = Ref.make(Chunk.empty[SessionNotification]).run
      val agent = AcpClient.connect(clientSide, RecordingClient(updates, permission)).run
      (agent, updates)

  private def promptText(agent: AgentConnection, sessionId: SessionId, text: String) =
    agent.prompt(PromptRequest(sessionId, List(ContentBlock.text(text))))

  private def chunks(updates: Ref[Chunk[SessionNotification]]): UIO[String] =
    updates.get.map(_.flatMap(n => text(n.update)).mkString)

  def spec = suite("AgentClientSpec")(
    test("initialize, new session and an echoed prompt with ordered updates"):
      defer:
        val (agent, updates) = connected().run
        val init = agent.initialize(InitializeRequest(ProtocolVersion.V1)).run
        val session = agent.newSession(NewSessionRequest("/tmp", Nil)).run
        val response = promptText(agent, session.sessionId, "hello").run
        assertTrue(
          init.agentInfo.map(_.name) == Some("echo"),
          session.sessionId == SessionId("s-/tmp"),
          response.stopReason == StopReason.EndTurn,
          chunks(updates).run == "echo: hello",
        )
    ,
    test("the agent calls the client during a turn"):
      defer:
        val (agent, updates) = connected().run
        val permission = promptText(agent, SessionId("s"), "#permission").run
        val read = promptText(agent, SessionId("s"), "#read").run
        assertTrue(
          permission.stopReason == StopReason.EndTurn,
          read.stopReason == StopReason.EndTurn,
          chunks(updates).run == "selected allowcontents of /a.txt",
        )
    ,
    test("cancelling answers pending permission requests with cancelled"):
      defer:
        val (agent, updates) = connected(permission = ZIO.never).run
        val turn = promptText(agent, SessionId("s"), "#permission").fork.run
        updates.get.repeatUntil(_.isEmpty).run
        ZIO.sleep(100.millis).run
        agent.cancel(SessionId("s")).run
        val response = turn.join.run
        assertTrue(response.stopReason == StopReason.Cancelled, chunks(updates).run == "permission cancelled")
    ,
    test("a cancelled turn answers cancelled"):
      defer:
        val (agent, updates) = connected().run
        val turn = promptText(agent, SessionId("s"), "#slow").fork.run
        chunks(updates).repeatUntil(_ == "tick").run
        agent.cancel(SessionId("s")).run
        assertTrue(turn.join.run.stopReason == StopReason.Cancelled)
    ,
    test("a turn that ignores its cancel is interrupted after the grace period"):
      defer:
        val (agent, _) = connected(options = AgentOptions(cancelGrace = 200.millis)).run
        val turn = promptText(agent, SessionId("s"), "#hang").fork.run
        ZIO.sleep(100.millis).run
        agent.cancel(SessionId("s")).run
        assertTrue(turn.join.run.stopReason == StopReason.Cancelled)
    ,
    test("interrupting a call cancels the request on the agent"):
      defer:
        val (agent, _) = connected().run
        val turn = promptText(agent, SessionId("s"), "#hang").fork.run
        ZIO.sleep(100.millis).run
        turn.interrupt.run
        val after = promptText(agent, SessionId("s"), "after").run
        assertTrue(after.stopReason == StopReason.EndTurn)
    ,
    test("extension methods round trip"):
      defer:
        val (agent, _) = connected().run
        val result = agent.extMethod("_test/ping", Json.Obj("n" -> Json.Num(1))).run
        assertTrue(result == Json.Obj("method" -> Json.Str("_test/ping"), "params" -> Json.Obj("n" -> Json.Num(1))))
    ,
    test("unknown methods fail with method not found"):
      defer:
        val (agent, _) = connected().run
        val error = agent.listSessions(ListSessionsRequest()).flip.run
        assertTrue(error.code == ErrorCode.MethodNotFound)
    ,
    test("closing the client ends the agent and fails calls"):
      defer:
        val (agent, _) = connected().run
        agent.close.run
        agent.awaitClosed.run
        val error = promptText(agent, SessionId("s"), "late").flip.run
        assertTrue(error == RpcError.connectionClosed)
    ,
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
