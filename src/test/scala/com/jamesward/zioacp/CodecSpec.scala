package com.jamesward.zioacp

import com.jamesward.zioacp.schema.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object CodecSpec extends ZIOSpecDefault:

  private given CanEqual[Json, Json] = CanEqual.derived

  private def roundTrip[A: JsonCodec](a: A)(using CanEqual[A, A]): Boolean =
    a.toJson.fromJson[A] == Right(a)

  def spec = suite("CodecSpec")(
    test("defaults fill in missing fields"):
      val decoded = """{"protocolVersion":1}""".fromJson[InitializeRequest]
      assertTrue(
        decoded.map(_.clientCapabilities.fs.readTextFile) == Right(false),
        decoded.map(_.clientCapabilities.terminal) == Right(false),
      )
    ,
    test("None is omitted and _meta keeps its wire name"):
      val json = PromptResponse(StopReason.EndTurn, Some(Json.Obj("k" -> Json.Str("v")))).toJson
      val noMeta = PromptResponse(StopReason.EndTurn).toJson
      assertTrue(json == """{"stopReason":"end_turn","_meta":{"k":"v"}}""", noMeta == """{"stopReason":"end_turn"}""")
    ,
    test("content blocks use the type discriminator"):
      val json = ContentBlock.text("hi").toJson
      assertTrue(json == """{"type":"text","text":"hi"}""", json.fromJson[ContentBlock] == Right(ContentBlock.text("hi")))
    ,
    test("unknown enum values are kept"):
      val decoded = """{"toolCallId":"c","title":"t","kind":"future_kind","status":"future_status"}""".fromJson[ToolCall]
      assertTrue(
        decoded.map(_.kind) == Right(ToolKind.Unknown("future_kind")),
        decoded.map(_.status) == Right(ToolCallStatus.Unknown("future_status")),
        decoded.map(_.toJson.contains("future_status")) == Right(true),
      )
    ,
    test("unknown session updates are kept"):
      val raw = """{"sessionUpdate":"future_update","payload":{"x":1}}"""
      val decoded = raw.fromJson[SessionUpdate]
      assertTrue(
        decoded.isRight,
        decoded.exists:
          case SessionUpdate.Unknown(kind, _) => kind == "future_update"
          case _                              => false
        ,
        decoded.flatMap(_.toJsonAST) == raw.fromJson[Json],
      )
    ,
    test("stdio MCP servers have no type"):
      val stdio: McpServer = McpServer.Stdio("s", "cmd")
      val http: McpServer = McpServer.Http("h", "http://x")
      assertTrue(
        stdio.toJson == """{"name":"s","command":"cmd","args":[],"env":[]}""",
        http.toJson == """{"type":"http","name":"h","url":"http://x","headers":[]}""",
        roundTrip(List(stdio, http)),
      )
    ,
    test("permission outcomes"):
      val selected = RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId("allow")))
      assertTrue(
        selected.toJson == """{"outcome":{"outcome":"selected","optionId":"allow"}}""",
        """{"outcome":{"outcome":"cancelled"}}""".fromJson[RequestPermissionResponse] ==
          Right(RequestPermissionResponse(RequestPermissionOutcome.Cancelled)),
      )
    ,
    test("grouped and ungrouped select options"):
      val grouped = """[{"group":"g","name":"G","options":[{"value":"a","name":"A"}]}]""".fromJson[SessionConfigSelectOptions]
      val flat = """[{"value":"a","name":"A"}]""".fromJson[SessionConfigSelectOptions]
      assertTrue(
        grouped.map(_.all.map(_.value.value)) == Right(List("a")),
        grouped.exists(_.isInstanceOf[SessionConfigSelectOptions.Grouped]),
        flat.exists(_.isInstanceOf[SessionConfigSelectOptions.Ungrouped]),
      )
    ,
    test("set config option values"):
      val bool = """{"sessionId":"s","configId":"c","type":"boolean","value":true}""".fromJson[SetSessionConfigOptionRequest]
      val id = """{"sessionId":"s","configId":"c","value":"v"}""".fromJson[SetSessionConfigOptionRequest]
      assertTrue(
        bool.map(_.value) == Right(SessionConfigValue.Boolean(true)),
        id.map(_.value) == Right(SessionConfigValue.ValueId(SessionConfigValueId("v"))),
        bool.map(_.toJson.fromJson[SetSessionConfigOptionRequest]) == Right(bool),
      )
    ,
    test("auth methods: untagged or \"agent\" is agent-handled, unknown types are kept"):
      val methods = """[{"id":"a","name":"A"},{"type":"agent","id":"b","name":"B"},{"type":"future","id":"c","name":"C","x":1}]"""
        .fromJson[List[AuthMethod]]
      assertTrue(
        methods.map(_.map(_.id.value)) == Right(List("a", "b", "c")),
        methods.exists(_.take(2).forall(_.isInstanceOf[AuthMethod.Agent])),
        methods.exists(_.lift(2).exists(_.isInstanceOf[AuthMethod.Other])),
        methods.map(_.lift(2).map(_.toJson)) == Right(Some("""{"type":"future","id":"c","name":"C","x":1}""")),
      )
    ,
    test("request ids"):
      assertTrue(
        "9007199254740993".fromJson[RequestId] == Right(RequestId.Number(9007199254740993L)),
        "null".fromJson[RequestId] == Right(RequestId.Null),
        "\"a\"".fromJson[RequestId] == Right(RequestId.Str("a")),
        "1.5".fromJson[RequestId].isLeft,
        "{}".fromJson[RequestId].isLeft,
      )
    ,
    test("errors without a message decode"):
      assertTrue("""{"code":-32603}""".fromJson[RpcError] == Right(RpcError(ErrorCode.InternalError)))
    ,
    test("elicitation requests flatten the scope"):
      val req: CreateElicitationRequest = CreateElicitationRequest.Form(
        ElicitationScope.Session(SessionId("s")),
        "m",
        ElicitationSchema(properties = scala.collection.immutable.ListMap("name" -> ElicitationPropertySchema.StringProperty())),
      )
      val json = req.toJson
      assertTrue(
        json == """{"mode":"form","message":"m","requestedSchema":{"type":"object","properties":{"name":{"type":"string"}}},"sessionId":"s"}""",
        json.fromJson[CreateElicitationRequest].map(_.toJson) == Right(json),
      )
    ,
  )
