package com.jamesward.zioacp

import com.jamesward.zioacp.schema.*
import zio.json.*
import zio.json.ast.Json

import scala.collection.immutable.ListMap

/** A value of every ACP v1 type, with every optional field set, paired with the name of its schema `$defs` entry. */
object Samples:
  final case class Sample(definition: String, json: Json)

  private def sample[A: JsonEncoder](definition: String, a: A): Sample =
    Sample(definition, a.toJsonAST.fold(e => Json.Str(s"encoding failed: $e"), identity))

  val meta: Option[Json.Obj] = Some(Json.Obj("trace" -> Json.Str("t")))
  val sid = SessionId("s1")
  val annotations = Annotations(Some(List(Role.User, Role.Assistant)), Some("2026-01-01T00:00:00Z"), Some(0.5), meta)

  val contentBlocks: List[ContentBlock] = List(
    ContentBlock.Text("hi", Some(annotations), meta),
    ContentBlock.Image("aGk=", "image/png", Some("file:///a.png"), Some(annotations), meta),
    ContentBlock.Audio("aGk=", "audio/wav", Some(annotations), meta),
    ContentBlock.ResourceLink("a", "file:///a", Some("A"), Some("d"), Some("text/plain"), Some(3), Some(annotations), meta),
    ContentBlock.Resource(EmbeddedResourceResource.Text(TextResourceContents("file:///a", "hi", Some("text/plain"), meta)), Some(annotations), meta),
    ContentBlock.Resource(EmbeddedResourceResource.Blob(BlobResourceContents("file:///b", "aGk=", Some("application/octet-stream"), meta))),
  )

  val toolCallContent: List[ToolCallContent] = List(
    ToolCallContent.Content(ContentBlock.text("out"), meta),
    ToolCallContent.Diff("/a", "new", Some("old"), meta),
    ToolCallContent.Terminal(TerminalId("t1"), meta),
  )

  val toolCall = ToolCall(
    ToolCallId("c1"),
    "read",
    Some("read_file"),
    ToolKind.Read,
    ToolCallStatus.InProgress,
    toolCallContent,
    List(ToolCallLocation("/a", Some(3), meta)),
    Some(Json.Obj("path" -> Json.Str("/a"))),
    Some(Json.Str("done")),
    meta,
  )

  val toolCallUpdate = ToolCallUpdate(
    ToolCallId("c1"),
    Some("read"),
    Some("read_file"),
    Some(ToolKind.Edit),
    Some(ToolCallStatus.Completed),
    Some(toolCallContent),
    Some(List(ToolCallLocation("/a"))),
    Some(Json.Obj()),
    Some(Json.Obj()),
    meta,
  )

  val modes = SessionModeState(SessionModeId("a"), List(SessionMode(SessionModeId("a"), "A", Some("mode a"), meta)), meta)

  val configOptions: List[SessionConfigOption] = List(
    SessionConfigOption.Select(
      SessionConfigId("model"),
      "Model",
      SessionConfigValueId("m1"),
      SessionConfigSelectOptions.Ungrouped(List(SessionConfigSelectOption(SessionConfigValueId("m1"), "M1", Some("d"), meta))),
      Some("the model"),
      Some(SessionConfigOptionCategory.Model),
      meta,
    ),
    SessionConfigOption.Select(
      SessionConfigId("effort"),
      "Effort",
      SessionConfigValueId("low"),
      SessionConfigSelectOptions.Grouped(
        List(SessionConfigSelectGroup(SessionConfigGroupId("g"), "G", List(SessionConfigSelectOption(SessionConfigValueId("low"), "Low")), meta)),
      ),
      category = Some(SessionConfigOptionCategory.Other("custom")),
    ),
    SessionConfigOption.Boolean(SessionConfigId("verbose"), "Verbose", true, Some("d"), Some(SessionConfigOptionCategory.ThoughtLevel), meta),
  )

  val mcpServers: List[McpServer] = List(
    McpServer.Stdio("s", "cmd", List("-x"), List(EnvVariable("K", "V", meta)), meta),
    McpServer.Http("h", "https://h", List(HttpHeader("A", "B", meta)), meta),
    McpServer.Sse("e", "https://e", Nil, meta),
  )

  val updates: List[SessionUpdate] = List(
    SessionUpdate.UserMessageChunk(ContentBlock.text("u"), Some(MessageId("m1")), meta),
    SessionUpdate.AgentMessageChunk(ContentBlock.text("a"), Some(MessageId("m2")), meta),
    SessionUpdate.AgentThoughtChunk(ContentBlock.text("t"), None, meta),
    SessionUpdate.ToolCallStarted(toolCall),
    SessionUpdate.ToolCallUpdated(toolCallUpdate),
    SessionUpdate.Plan(List(PlanEntry("p", PlanEntryPriority.High, PlanEntryStatus.InProgress, meta)), meta),
    SessionUpdate.AvailableCommandsUpdate(List(AvailableCommand("c", "d", Some(UnstructuredCommandInput("h", meta)), meta)), meta),
    SessionUpdate.CurrentModeUpdate(SessionModeId("a"), meta),
    SessionUpdate.ConfigOptionUpdate(configOptions, meta),
    SessionUpdate.SessionInfoUpdate(Some("title"), Some("2026-01-01T00:00:00Z"), meta),
    SessionUpdate.UsageUpdate(1, 2, Some(Cost(0.5, "USD", meta)), meta),
  )

  val elicitationSchema = ElicitationSchema(
    ListMap(
      "s" -> ElicitationPropertySchema.StringProperty(
        Some("S"), Some("d"), Some(1), Some(9), Some("^a"), Some(StringFormat.Email), Some("a@b"), Some(List("a@b")),
        Some(List(EnumOption("a@b", "A", Some("d"), meta))), meta,
      ),
      "n" -> ElicitationPropertySchema.NumberProperty(Some("N"), Some("d"), Some(0.5), Some(9.5), Some(1.5), meta),
      "i" -> ElicitationPropertySchema.IntegerProperty(Some("I"), Some("d"), Some(0), Some(9), Some(1), meta),
      "b" -> ElicitationPropertySchema.BooleanProperty(Some("B"), Some("d"), Some(true), meta),
      "m" -> ElicitationPropertySchema.MultiSelectProperty(MultiSelectItems.Strings(List("x", "y")), Some("M"), Some("d"), Some(1), Some(2), Some(List("x")), meta),
      "t" -> ElicitationPropertySchema.MultiSelectProperty(MultiSelectItems.Titled(List(EnumOption("x", "X")))),
    ),
    Some(List("s")),
    Some("Form"),
    Some("d"),
    meta,
  )

  val all: List[Sample] = List(
    sample(
      "InitializeRequest",
      InitializeRequest(
        ProtocolVersion.V1,
        ClientCapabilities(
          FileSystemCapabilities(true, true, meta),
          terminal = true,
          session = Some(ClientSessionCapabilities(Some(SessionConfigOptionsCapabilities(Some(Marker(meta)), meta)), meta)),
          auth = AuthCapabilities(true, meta),
          elicitation = Some(ElicitationCapabilities(Some(Marker()), Some(Marker()), meta)),
          meta = meta,
        ),
        Some(Implementation("c", "1", Some("Client"), meta)),
        meta,
      ),
    ),
    sample(
      "InitializeResponse",
      InitializeResponse(
        ProtocolVersion.V1,
        AgentCapabilities(
          true,
          PromptCapabilities(true, true, true, meta),
          McpCapabilities(true, true, meta),
          SessionCapabilities(Some(Marker()), Some(Marker()), Some(Marker()), Some(Marker()), Some(Marker()), meta),
          AgentAuthCapabilities(Some(Marker(meta)), meta),
          meta,
        ),
        List(
          AuthMethod.Agent(AuthMethodId("a"), "A", Some("d"), meta),
          AuthMethod.Terminal(AuthMethodId("t"), "T", Some("d"), List("--login"), Map("K" -> "V"), meta),
        ),
        Some(Implementation("a", "1")),
        meta,
      ),
    ),
    sample("AuthenticateRequest", AuthenticateRequest(AuthMethodId("a"), meta)),
    sample("AuthenticateResponse", AuthenticateResponse(meta)),
    sample("LogoutRequest", LogoutRequest(meta)),
    sample("LogoutResponse", LogoutResponse(meta)),
    sample("NewSessionRequest", NewSessionRequest("/w", mcpServers, List("/x"), meta)),
    sample("NewSessionResponse", NewSessionResponse(sid, Some(modes), Some(configOptions), meta)),
    sample("LoadSessionRequest", LoadSessionRequest(sid, "/w", mcpServers, List("/x"), meta)),
    sample("LoadSessionResponse", LoadSessionResponse(Some(modes), Some(configOptions), meta)),
    sample("ListSessionsRequest", ListSessionsRequest(Some("/w"), Some("c"), meta)),
    sample("ListSessionsResponse", ListSessionsResponse(List(SessionInfo(sid, "/w", List("/x"), Some("t"), Some("2026-01-01T00:00:00Z"), meta)), Some("n"), meta)),
    sample("DeleteSessionRequest", DeleteSessionRequest(sid, meta)),
    sample("DeleteSessionResponse", DeleteSessionResponse(meta)),
    sample("ResumeSessionRequest", ResumeSessionRequest(sid, "/w", mcpServers, List("/x"), meta)),
    sample("ResumeSessionResponse", ResumeSessionResponse(Some(modes), Some(configOptions), meta)),
    sample("CloseSessionRequest", CloseSessionRequest(sid, meta)),
    sample("CloseSessionResponse", CloseSessionResponse(meta)),
    sample("SetSessionModeRequest", SetSessionModeRequest(sid, SessionModeId("a"), meta)),
    sample("SetSessionModeResponse", SetSessionModeResponse(meta)),
    sample("SetSessionConfigOptionRequest", SetSessionConfigOptionRequest(sid, SessionConfigId("model"), SessionConfigValue.ValueId(SessionConfigValueId("m1")), meta)),
    sample("SetSessionConfigOptionRequest", SetSessionConfigOptionRequest(sid, SessionConfigId("verbose"), SessionConfigValue.Boolean(false))),
    sample("SetSessionConfigOptionResponse", SetSessionConfigOptionResponse(configOptions, meta)),
    sample("PromptRequest", PromptRequest(sid, contentBlocks, meta)),
    sample("PromptResponse", PromptResponse(StopReason.MaxTurnRequests, meta)),
    sample("CancelNotification", CancelNotification(sid, meta)),
    sample("CancelRequestNotification", CancelRequestNotification(RequestId.Str("r"), meta)),
    sample("CancelRequestNotification", CancelRequestNotification(RequestId.Number(3))),
    sample(
      "RequestPermissionRequest",
      RequestPermissionRequest(sid, toolCallUpdate, List(PermissionOption(PermissionOptionId("o"), "O", PermissionOptionKind.AllowAlways, meta)), meta),
    ),
    sample("RequestPermissionResponse", RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("o"), meta), meta)),
    sample("RequestPermissionResponse", RequestPermissionResponse(RequestPermissionOutcome.Cancelled)),
    sample("ReadTextFileRequest", ReadTextFileRequest(sid, "/a", Some(1), Some(2), meta)),
    sample("ReadTextFileResponse", ReadTextFileResponse("c", meta)),
    sample("WriteTextFileRequest", WriteTextFileRequest(sid, "/a", "c", meta)),
    sample("WriteTextFileResponse", WriteTextFileResponse(meta)),
    sample("CreateTerminalRequest", CreateTerminalRequest(sid, "ls", List("-l"), List(EnvVariable("K", "V")), Some("/w"), Some(1024), meta)),
    sample("CreateTerminalResponse", CreateTerminalResponse(TerminalId("t"), meta)),
    sample("TerminalOutputRequest", TerminalOutputRequest(sid, TerminalId("t"), meta)),
    sample("TerminalOutputResponse", TerminalOutputResponse("out", true, Some(TerminalExitStatus(Some(0), Some("SIGTERM"), meta)), meta)),
    sample("ReleaseTerminalRequest", ReleaseTerminalRequest(sid, TerminalId("t"), meta)),
    sample("ReleaseTerminalResponse", ReleaseTerminalResponse(meta)),
    sample("WaitForTerminalExitRequest", WaitForTerminalExitRequest(sid, TerminalId("t"), meta)),
    sample("WaitForTerminalExitResponse", WaitForTerminalExitResponse(Some(1), Some("SIGKILL"), meta)),
    sample("KillTerminalRequest", KillTerminalRequest(sid, TerminalId("t"), meta)),
    sample("KillTerminalResponse", KillTerminalResponse(meta)),
    sample(
      "CreateElicitationRequest",
      CreateElicitationRequest.Form(ElicitationScope.Session(sid, Some(ToolCallId("c"))), "m", elicitationSchema, meta),
    ),
    sample(
      "CreateElicitationRequest",
      CreateElicitationRequest.Url(ElicitationScope.Request(RequestId.Number(7)), "m", ElicitationId("e"), "https://example.com/e", meta),
    ),
    sample(
      "CreateElicitationResponse",
      CreateElicitationResponse.Accept(
        Some(
          ListMap(
            "s" -> ElicitationContentValue.Str("a"),
            "i" -> ElicitationContentValue.Integer(1),
            "n" -> ElicitationContentValue.Number(1.5),
            "b" -> ElicitationContentValue.Bool(true),
            "m" -> ElicitationContentValue.StringArray(List("x")),
          ),
        ),
        meta,
      ),
    ),
    sample("CreateElicitationResponse", CreateElicitationResponse.Decline(meta)),
    sample("CreateElicitationResponse", CreateElicitationResponse.Cancel()),
    sample("CompleteElicitationNotification", CompleteElicitationNotification(ElicitationId("e"), meta)),
    sample("Error", RpcError(ErrorCode.InvalidParams, "bad", Some(Json.Obj("x" -> Json.Num(1))))),
    sample("Error", RpcError(ErrorCode.Other(-1), "other")),
  ) ++ updates.map(u => sample("SessionNotification", SessionNotification(sid, u, meta)))
