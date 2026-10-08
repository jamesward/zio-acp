# zio-acp

A Scala 3 / [ZIO](https://zio.dev) implementation of version 1 of the
[Agent Client Protocol](https://agentclientprotocol.com) (ACP), the JSON-RPC protocol between code editors (clients)
and coding agents. Use it to build ACP agents, ACP clients, or both.

- **The whole v1 schema as Scala types**: every request, response and notification of
  [schema v1](https://github.com/agentclientprotocol/agent-client-protocol/tree/main/schema/v1) (initialization,
  authentication, sessions, prompt turns and session updates, tool calls, permissions, file system, terminals,
  elicitation, session modes and config options, `_meta`, extension methods), with JSON codecs checked against the
  official JSON Schema.
- **Forward compatible**: enum values, session updates and elicitation variants from newer protocol revisions are
  kept as received (`ToolKind.Unknown("...")`, `SessionUpdate.Unknown(...)`) instead of failing.
- **Effects all the way down**: handlers are ZIO effects; concurrent requests run on their own fibers; interrupting a
  call sends `$/cancel_request`; a `$/cancel_request` from the peer interrupts the handler and answers `-32800`.
- **Protocol rules handled for you**: `session/cancel` signals the running turn and, after a grace period, interrupts
  it and answers `cancelled`; `session/close` cancels the session's turn; a client's cancel answers its pending
  permission requests `cancelled`; updates are delivered before the response that follows them; malformed JSON-RPC
  is answered as JSON-RPC 2.0 requires.
- **Transports**: stdio (for an agent launched by a client), a subprocess (for a client launching an agent), any
  `InputStream`/`OutputStream` pair, and an in-memory pipe for tests.

## Install

```scala
libraryDependencies += "com.jamesward" %% "zio-acp" % "<version>"
```

## An agent

```scala
import com.jamesward.zioacp.*
import com.jamesward.zioacp.schema.*
import zio.*

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
```

`Agent` has a method per agent method; the optional ones (`loadSession`, `listSessions`, `setSessionMode`,
`authenticate`, ...) answer "method not found" unless overridden. `ClientConnection` calls the client:
`sessionUpdate`, `requestPermission`, `readTextFile`/`writeTextFile`, the `terminal/*` methods, `createElicitation`,
and extension methods. A long-running turn can race its work against `turn.awaitCancel`.

## A client

```scala
import com.jamesward.zioacp.*
import com.jamesward.zioacp.schema.*
import zio.*

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
```

Both examples are in [`src/test/scala/examples`](src/test/scala/examples). `AcpClient.connect` takes any
`Transport` instead of launching a process.

## Conformance

ACP has no official test kit; zio-acp is checked against everything the ecosystem offers:

| Check | What it covers |
|---|---|
| [`SchemaSpec`](src/test/scala/com/jamesward/zioacp/SchemaSpec.scala) | A value of every v1 type, every optional field set, validates against the official v1 JSON Schema and writes no property the schema doesn't declare. |
| [`JavaSdkInteropSpec`](src/test/scala/com/jamesward/zioacp/JavaSdkInteropSpec.scala) | The [ACP Java SDK](https://github.com/agentclientprotocol/java-sdk) client drives the Scala agent, and the Scala client drives a Java SDK agent. |
| [`CrossSdkSpec`](src/test/scala/com/jamesward/zioacp/CrossSdkSpec.scala) | The ACP SDKs' cross-SDK interop suite (the Java SDK's `integration-testing/`), in a Testcontainers image: its 70+ step catalogue between the Scala programs ([`src/test/scala/interop`](src/test/scala/interop)) and the Java and [Kotlin](https://github.com/agentclientprotocol/kotlin-sdk) SDK programs, in both directions, plus its raw JSON-RPC conformance driver against the Scala agent and client. |

Run everything with `./sbt testFull`. `CrossSdkSpec` needs Docker; its first image build takes about 15 minutes.

## License

Apache-2.0
