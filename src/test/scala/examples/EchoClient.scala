package examples

import com.jamesward.zioacp.*
import com.jamesward.zioacp.schema.*
import zio.*

/** An ACP client that starts an agent subprocess, sends it one prompt and prints what it streams back. */
object EchoClient extends ZIOAppDefault:

  object Printer extends Client:
    def sessionUpdate(notification: SessionNotification): UIO[Unit] =
      notification.update match
        case SessionUpdate.AgentMessageChunk(ContentBlock.Text(text, _, _), _, _) => Console.printLine(text).ignoreLogged
        case _                                                                    => ZIO.unit

    def requestPermission(request: RequestPermissionRequest): IO[RpcError, RequestPermissionResponse] =
      ZIO.succeed(RequestPermissionResponse(RequestPermissionOutcome.Cancelled))

  def run =
    ZIO.scoped:
      for
        args  <- getArgs
        agent <- AcpClient.launch(AgentCommand(args.head, args.tail.toList), Printer)
        _     <- agent.initialize(InitializeRequest(ProtocolVersion.V1, clientInfo = Some(Implementation("echo-client", "1.0.0"))))
        session  <- agent.newSession(NewSessionRequest(java.lang.System.getProperty("user.dir"), Nil))
        response <- agent.prompt(PromptRequest(session.sessionId, List(ContentBlock.text("Hello, agent!"))))
        _        <- Console.printLine(s"stop reason: ${response.stopReason}")
      yield ()
