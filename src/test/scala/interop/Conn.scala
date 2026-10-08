package interop

import com.jamesward.zioacp.*
import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, Path}

/** Counts of every `session/update` received, on any connection, for the RESULT line. */
final case class Counters(total: Ref[Int], byKind: Ref[Map[String, Int]]):
  def record(update: SessionUpdate): UIO[Unit] =
    val kind = Conn.kind(update)
    total.update(_ + 1) *> byKind.update(m => m.updated(kind, m.get(kind).fold(1)(_ + 1)))

/** What one client connection received. */
final case class Received(
  updates: Ref[Map[SessionId, Chunk[SessionUpdate]]],
  permissions: Ref[Map[SessionId, Int]],
  permissionMeta: Ref[Map[SessionId, Json.Obj]],
  cancelOnPermission: Ref[Set[SessionId]],
  cancelSentAt: Ref[Map[SessionId, Long]],
  completedElicitations: Ref[List[ElicitationId]],
  extNotes: Ref[List[Json]],
)

/** The interop client's handlers: real files and processes, and the fixture answers. */
final case class InteropClientHandlers(
  received: Received,
  counters: Counters,
  terminals: Terminals,
  agent: Promise[Nothing, AgentConnection],
) extends Client:

  def sessionUpdate(notification: SessionNotification): UIO[Unit] =
    received.updates.update(m => m.updated(notification.sessionId, m.get(notification.sessionId).fold(Chunk(notification.update))(_ :+ notification.update))) *>
      counters.record(notification.update) *>
      Conn.out(s"  update ${notification.sessionId.value}: ${Step.abbreviate(notification.update.toJson)}")

  def requestPermission(request: RequestPermissionRequest): IO[RpcError, RequestPermissionResponse] =
    val sid = request.sessionId
    defer:
      received.permissions.update(m => m.updated(sid, m.get(sid).fold(1)(_ + 1))).run
      ZIO.foreachDiscard(request.meta)(m => received.permissionMeta.update(_.updated(sid, m))).run
      if received.cancelOnPermission.get.run.contains(sid) then
        // recorded first: cancelling answers this request for us, which ends this handler
        Clock.nanoTime.flatMap(t => received.cancelSentAt.update(_.updated(sid, t))).run
        agent.await.flatMap(_.cancel(sid)).run
        RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
      else
        val chosen = request.options.find(_.kind == PermissionOptionKind.AllowOnce).orElse(request.options.headOption)
        chosen match
          case Some(option) => RequestPermissionResponse(RequestPermissionOutcome.Selected(option.optionId), request.meta)
          case None         => ZIO.fail(RpcError.invalidParams("no permission options")).run

  override def writeTextFile(request: WriteTextFileRequest): IO[RpcError, WriteTextFileResponse] =
    ZIO
      .attemptBlocking(Files.writeString(Path.of(request.path), request.content, StandardCharsets.UTF_8))
      .mapError(e => RpcError.internalError(s"could not write ${request.path}"))
      .as(WriteTextFileResponse())

  override def readTextFile(request: ReadTextFileRequest): IO[RpcError, ReadTextFileResponse] =
    val read = ZIO
      .attemptBlocking(Files.readString(Path.of(request.path), StandardCharsets.UTF_8))
      .mapError:
        case _: NoSuchFileException => RpcError.resourceNotFound(request.path)
        case _                      => RpcError.internalError(s"could not read ${request.path}")
      .map: content =>
        if request.line.isEmpty && request.limit.isEmpty then content
        else
          val lines = content.linesIterator.toVector
          val from = (request.line.fold(1L)(identity) - 1).max(0).toInt
          val to = request.limit.fold(lines.size)(l => (from + l.toInt).min(lines.size))
          lines.slice(from, to).map(_ + "\n").mkString
      .map(ReadTextFileResponse(_))
    // cancel-request.agent: slow.txt is held for 10 s unless the agent cancels the read
    if request.path.endsWith("slow.txt") then read.delay(10.seconds) else read

  override def createTerminal(request: CreateTerminalRequest): IO[RpcError, CreateTerminalResponse] = terminals.create(request)
  override def terminalOutput(request: TerminalOutputRequest): IO[RpcError, TerminalOutputResponse] = terminals.output(request)
  override def waitForTerminalExit(request: WaitForTerminalExitRequest): IO[RpcError, WaitForTerminalExitResponse] =
    terminals.waitForExit(request)
  override def killTerminal(request: KillTerminalRequest): IO[RpcError, KillTerminalResponse] = terminals.kill(request)
  override def releaseTerminal(request: ReleaseTerminalRequest): IO[RpcError, ReleaseTerminalResponse] = terminals.release(request)

  override def createElicitation(request: CreateElicitationRequest): IO[RpcError, CreateElicitationResponse] =
    request match
      case _: CreateElicitationRequest.Url => ZIO.succeed(CreateElicitationResponse.Accept())
      case _ =>
        ZIO.succeed(
          CreateElicitationResponse.Accept(Some(scala.collection.immutable.ListMap("name" -> ElicitationContentValue.Str("interop")))),
        )

  override def completeElicitation(notification: CompleteElicitationNotification): UIO[Unit] =
    received.completedElicitations.update(_ :+ notification.elicitationId)

  override def extMethod(method: String, params: Json): IO[RpcError, Json] =
    if method == Fixtures.extMethod then ZIO.succeed(Fixtures.extResult) else ZIO.fail(RpcError.methodNotFound(method))

  override def extNotification(method: String, params: Json): UIO[Unit] =
    received.extNotes.update(_ :+ params).when(method == Fixtures.extNotification).unit

/** One client connection to a stdio agent, with what it received. */
final case class Conn(agent: AgentConnection, received: Received, terminals: Terminals, scope: Scope.Closeable):
  def initialize: IO[RpcError, InitializeResponse] = agent.initialize(Fixtures.clientInit)

  def newSession(cwd: String): IO[RpcError, SessionId] = agent.newSession(NewSessionRequest(cwd, Nil)).map(_.sessionId)

  def prompt(sid: SessionId, text: String): IO[RpcError, PromptResponse] =
    agent.prompt(PromptRequest(sid, List(ContentBlock.text(text))))

  def updates(sid: SessionId): UIO[Chunk[SessionUpdate]] = received.updates.get.map(_.get(sid).fold(Chunk.empty)(identity))

  /** The text of every agent_message_chunk received for the session, in order. */
  def chunks(sid: SessionId): UIO[Chunk[String]] =
    updates(sid).map(_.collect { case SessionUpdate.AgentMessageChunk(ContentBlock.Text(t, _, _), _, _) => t })

  def close: UIO[Unit] = agent.close *> agent.awaitClosed.timeout(10.seconds).unit *> terminals.releaseAll *> scope.close(Exit.unit)

object Conn:
  def out(line: String): UIO[Unit] = ZIO.succeed(java.lang.System.out.println(line))

  /** On stdio the agent's stderr comes to stdout: STEP lines verbatim, the rest prefixed. */
  def relay(line: String): UIO[Unit] = out(if line.startsWith("STEP ") then line else s"agent| $line")

  def open(agentCommand: String, counters: Counters): Task[Conn] =
    defer:
      val scope = Scope.make.run
      val received = Received(
        Ref.make(Map.empty[SessionId, Chunk[SessionUpdate]]).run,
        Ref.make(Map.empty[SessionId, Int]).run,
        Ref.make(Map.empty[SessionId, Json.Obj]).run,
        Ref.make(Set.empty[SessionId]).run,
        Ref.make(Map.empty[SessionId, Long]).run,
        Ref.make(List.empty[ElicitationId]).run,
        Ref.make(List.empty[Json]).run,
      )
      val terminals = Terminals.make.run
      val agentPromise = Promise.make[Nothing, AgentConnection].run
      val handlers = InteropClientHandlers(received, counters, terminals, agentPromise)
      val agent = AcpClient
        .launch(AgentCommand("bash", List("-c", s"exec $agentCommand")), handlers, relay)
        .provideEnvironment(ZEnvironment(scope))
        .run
      agentPromise.succeed(agent).run
      Conn(agent, received, terminals, scope)

  def kind(update: SessionUpdate): String =
    update match
      case _: SessionUpdate.UserMessageChunk        => "user_message_chunk"
      case _: SessionUpdate.AgentMessageChunk       => "agent_message_chunk"
      case _: SessionUpdate.AgentThoughtChunk       => "agent_thought_chunk"
      case _: SessionUpdate.ToolCallStarted         => "tool_call"
      case _: SessionUpdate.ToolCallUpdated         => "tool_call_update"
      case _: SessionUpdate.Plan                    => "plan"
      case _: SessionUpdate.AvailableCommandsUpdate => "available_commands_update"
      case _: SessionUpdate.CurrentModeUpdate       => "current_mode_update"
      case _: SessionUpdate.ConfigOptionUpdate      => "config_option_update"
      case _: SessionUpdate.SessionInfoUpdate       => "session_info_update"
      case _: SessionUpdate.UsageUpdate             => "usage_update"
      case _: SessionUpdate.Unknown                 => "other"
