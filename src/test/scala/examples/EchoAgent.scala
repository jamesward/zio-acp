package examples

import com.jamesward.zioacp.*
import com.jamesward.zioacp.schema.*
import zio.*

/** An ACP agent that echoes each prompt back, served on stdin and stdout. */
object EchoAgent extends ZIOAppDefault:
  // stdout carries the protocol, so logs go to stderr
  override val bootstrap = AcpAgent.stderrLogging

  final case class Echo(client: ClientConnection) extends Agent:
    def initialize(request: InitializeRequest): IO[RpcError, InitializeResponse] =
      ZIO.succeed(InitializeResponse(ProtocolVersion.V1, agentInfo = Some(Implementation("echo", "1.0.0"))))

    def newSession(request: NewSessionRequest): IO[RpcError, NewSessionResponse] =
      Random.nextUUID.map(id => NewSessionResponse(SessionId(id.toString)))

    def prompt(request: PromptRequest, turn: PromptTurn): IO[RpcError, PromptResponse] =
      val text = request.prompt.collect { case ContentBlock.Text(t, _, _) => t }.mkString
      client
        .sessionUpdate(request.sessionId, SessionUpdate.AgentMessageChunk(ContentBlock.text(s"echo: $text")))
        .as(PromptResponse(StopReason.EndTurn))

  def run = AcpAgent.serveStdio()(client => ZIO.succeed(Echo(client)))
