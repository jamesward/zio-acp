package com.jamesward.zioacp

import com.agentclientprotocol.sdk.agent.AcpAgent as JavaAgent
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport
import com.agentclientprotocol.sdk.client.AcpClient as JavaClient
import com.agentclientprotocol.sdk.client.transport.{AgentParameters, StdioAcpClientTransport}
import com.agentclientprotocol.sdk.json.AcpJsonMapper
import com.agentclientprotocol.sdk.spec.AcpSchema as J
import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*
import zio.test.*

import java.io.{PipedInputStream, PipedOutputStream}
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** Interop with the official ACP Java SDK (com.agentclientprotocol:acp-core), in both directions. */
object JavaSdkInteropSpec extends ZIOSpecDefault:

  private val json = AcpJsonMapper.createDefault()

  private given CanEqual[J.StopReason, J.StopReason] = CanEqual.derived

  /** The Scala interop agent (src/test/scala/interop) as a subprocess, on this JVM's test classpath. */
  private def scalaAgentProcess: AgentParameters =
    AgentParameters
      .builder("java")
      .args("-cp", java.lang.System.getProperty("interop.classpath"), "interop.InteropAgent", "--transport", "stdio")
      .build()

  private def javaClient(chunks: CopyOnWriteArrayList[String], permissions: CopyOnWriteArrayList[String]) =
    JavaClient
      .sync(StdioAcpClientTransport(scalaAgentProcess, json))
      .requestTimeout(java.time.Duration.ofSeconds(20))
      .clientCapabilities(J.ClientCapabilities(J.FileSystemCapability(true, true), false))
      .sessionUpdateConsumer: n =>
        n.update() match
          case c: J.AgentMessageChunk =>
            c.content() match
              case t: J.TextContent => chunks.add(t.text())
              case _                => ()
          case _ => ()
      .requestPermissionHandler: req =>
        permissions.add(req.toolCall().toolCallId())
        J.RequestPermissionResponse(J.PermissionSelected(req.options().get(0).optionId()))
      .readTextFileHandler(req => J.ReadTextFileResponse(s"read ${req.path()}"))
      .build()

  private def text(s: String): java.util.List[J.ContentBlock] = java.util.List.of(J.TextContent(s))

  /**
   * Takes the chunks received so far once there are `count` of them, or after a second: the Java SDK delivers updates on
   * its own thread, so they can trail the prompt's response (the cross-SDK catalogue allows the same grace).
   */
  private def take(chunks: CopyOnWriteArrayList[String], count: Int): List[String] =
    val deadline = java.lang.System.nanoTime() + 1000000000L
    while chunks.size < count && java.lang.System.nanoTime() < deadline do Thread.sleep(10)
    val taken = chunks.asScala.toList
    chunks.clear()
    taken

  def spec = suite("JavaSdkInteropSpec")(
    test("a Java SDK client drives the Scala agent"):
      ZIO.attemptBlocking:
        val chunks = CopyOnWriteArrayList[String]()
        val permissions = CopyOnWriteArrayList[String]()
        val client = javaClient(chunks, permissions)
        try
          val init = client.initialize()
          val session = client.newSession(J.NewSessionRequest("/tmp", java.util.List.of()))
          val echo = client.prompt(J.PromptRequest(session.sessionId(), text("hello")))
          val echoed = take(chunks, 2).mkString
          val permission = client.prompt(J.PromptRequest(session.sessionId(), text("#permission allow")))
          val permitted = take(chunks, 1)
          val read = client.prompt(J.PromptRequest(session.sessionId(), text("#fs read /x.txt")))
          val readChunks = take(chunks, 1)
          assertTrue(
            init.protocolVersion() == 1,
            init.agentInfo().name() == "interop-scala-agent",
            session.modes().currentModeId() == "interop-mode-a",
            echo.stopReason() == J.StopReason.END_TURN,
            echoed == "echo: hello",
            permission.stopReason() == J.StopReason.END_TURN,
            permissions.asScala.toList == List("perm-1"),
            permitted == List("permission: selected allow"),
            read.stopReason() == J.StopReason.END_TURN,
            readChunks == List("read /x.txt"),
          )
        finally client.closeGracefully()
    ,
    test("a Java SDK client cancels a Scala agent's prompt turn"):
      val chunks = CopyOnWriteArrayList[String]()
      ZIO.acquireRelease(ZIO.attemptBlocking(javaClient(chunks, CopyOnWriteArrayList[String]())))(c => ZIO.attemptBlocking(c.closeGracefully()).ignoreLogged).flatMap: client =>
        defer:
          ZIO.attemptBlocking(client.initialize()).run
          val session = ZIO.attemptBlocking(client.newSession(J.NewSessionRequest("/tmp", java.util.List.of()))).run
          val turn = ZIO.attemptBlocking(client.prompt(J.PromptRequest(session.sessionId(), text("#slow")))).fork.run
          ZIO.succeed(chunks.contains("tick")).repeatUntil(identity).delay(20.millis).run
          ZIO.attemptBlocking(client.cancel(J.CancelNotification(session.sessionId()))).run
          val response = turn.join.timeout(5.seconds).run
          assertTrue(response.map(_.stopReason()) == Some(J.StopReason.CANCELLED))
    ,
    test("the Scala client drives a Java SDK agent"):
      val toAgent = PipedOutputStream()
      val agentIn = PipedInputStream(toAgent, 1 << 16)
      val toClient = PipedOutputStream()
      val clientIn = PipedInputStream(toClient, 1 << 16)
      val javaAgent = JavaAgent
        .sync(StdioAcpAgentTransport(json, agentIn, toClient))
        .initializeHandler(_ => J.InitializeResponse.ok())
        .newSessionHandler(_ => J.NewSessionResponse("java-session", null, null))
        .promptHandler: (req, context) =>
          val prompt = req.prompt().asScala.collect { case t: J.TextContent => t.text() }.mkString
          context.sendMessage(s"java: $prompt")
          context.sendMessage(context.readFile("/etc/hostname"))
          J.PromptResponse.endTurn()
        .build()
      defer:
        ZIO.attemptBlocking(javaAgent.run()).forkDaemon.run
        val transport = Transport.fromStreams(clientIn, toAgent).run
        val updates = Ref.make(Chunk.empty[SessionNotification]).run
        val client = AgentClientSpec.RecordingClient(updates, ZIO.succeed(RequestPermissionResponse(RequestPermissionOutcome.Cancelled)))
        val agent = AcpClient.connect(transport, client).run
        val init = agent
          .initialize(InitializeRequest(ProtocolVersion.V1, ClientCapabilities(fs = FileSystemCapabilities(readTextFile = true))))
          .run
        val session = agent.newSession(NewSessionRequest("/tmp", Nil)).run
        val response = agent.prompt(PromptRequest(session.sessionId, List(ContentBlock.text("hi")))).run
        val chunks = updates.get.run.flatMap(n => AgentClientSpec.text(n.update))
        agent.close.run
        assertTrue(
          init.protocolVersion == ProtocolVersion.V1,
          session.sessionId == SessionId("java-session"),
          response.stopReason == StopReason.EndTurn,
          chunks == Chunk("java: hi", "contents of /etc/hostname"),
        )
    ,
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential
