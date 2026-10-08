package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*
import zio.json.ast.Json

/** The category of a tool, used by clients to pick icons and UI. Unknown kinds are kept as `Unknown`. */
enum ToolKind derives CanEqual:
  case Read, Edit, Delete, Move, Search, Execute, Think, Fetch, SwitchMode, Other
  case Unknown(value: String)

object ToolKind:
  given JsonCodec[ToolKind] = openEnum(Seq(Read, Edit, Delete, Move, Search, Execute, Think, Fetch, SwitchMode, Other))(
    {
      case Read           => "read"
      case Edit           => "edit"
      case Delete         => "delete"
      case Move           => "move"
      case Search         => "search"
      case Execute        => "execute"
      case Think          => "think"
      case Fetch          => "fetch"
      case SwitchMode     => "switch_mode"
      case Other          => "other"
      case Unknown(value) => value
    },
    Unknown(_),
  )

/** The execution status of a tool call. Unknown statuses are kept as `Unknown`. */
enum ToolCallStatus derives CanEqual:
  case Pending, InProgress, Completed, Failed
  case Unknown(value: String)

object ToolCallStatus:
  given JsonCodec[ToolCallStatus] = openEnum(Seq(Pending, InProgress, Completed, Failed))(
    {
      case Pending        => "pending"
      case InProgress     => "in_progress"
      case Completed      => "completed"
      case Failed         => "failed"
      case Unknown(value) => value
    },
    Unknown(_),
  )

/** Content produced by a tool call. */
enum ToolCallContent derives CanEqual:
  case Content(content: ContentBlock, @jsonField("_meta") meta: Meta = None)
  case Diff(path: String, newText: String, oldText: Option[String] = None, @jsonField("_meta") meta: Meta = None)
  case Terminal(terminalId: TerminalId, @jsonField("_meta") meta: Meta = None)

object ToolCallContent:
  private given JsonCodec[Content]  = DeriveJsonCodec.gen
  private given JsonCodec[Diff]     = DeriveJsonCodec.gen
  private given JsonCodec[Terminal] = DeriveJsonCodec.gen

  given JsonCodec[ToolCallContent] = tagged[ToolCallContent](
    "type",
    Variant[ToolCallContent, Content]("content"),
    Variant[ToolCallContent, Diff]("diff"),
    Variant[ToolCallContent, Terminal]("terminal"),
  )(noFallback("type"), noFallbackEncoding)

/** A file location a tool call is working with, for "follow-along" features. */
final case class ToolCallLocation(path: String, line: Option[Long] = None, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

/** A new tool call, reported in a `tool_call` session update. */
final case class ToolCall(
  toolCallId: ToolCallId,
  title: String,
  name: Option[String] = None,
  kind: ToolKind = ToolKind.Other,
  status: ToolCallStatus = ToolCallStatus.Pending,
  content: List[ToolCallContent] = Nil,
  locations: List[ToolCallLocation] = Nil,
  rawInput: Option[Json] = None,
  rawOutput: Option[Json] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** Changes to an existing tool call; absent fields are unchanged. */
final case class ToolCallUpdate(
  toolCallId: ToolCallId,
  title: Option[String] = None,
  name: Option[String] = None,
  kind: Option[ToolKind] = None,
  status: Option[ToolCallStatus] = None,
  content: Option[List[ToolCallContent]] = None,
  locations: Option[List[ToolCallLocation]] = None,
  rawInput: Option[Json] = None,
  rawOutput: Option[Json] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** The kind of a permission option, which clients use for icons and to remember choices. */
enum PermissionOptionKind derives CanEqual:
  case AllowOnce, AllowAlways, RejectOnce, RejectAlways
  case Unknown(value: String)

object PermissionOptionKind:
  given JsonCodec[PermissionOptionKind] = openEnum(Seq(AllowOnce, AllowAlways, RejectOnce, RejectAlways))(
    {
      case AllowOnce      => "allow_once"
      case AllowAlways    => "allow_always"
      case RejectOnce     => "reject_once"
      case RejectAlways   => "reject_always"
      case Unknown(value) => value
    },
    Unknown(_),
  )

final case class PermissionOption(
  optionId: PermissionOptionId,
  name: String,
  kind: PermissionOptionKind,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** `session/request_permission`: the agent asks the user to authorize a tool call. */
final case class RequestPermissionRequest(
  sessionId: SessionId,
  toolCall: ToolCallUpdate,
  options: List[PermissionOption],
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** The user's decision on a permission request. */
enum RequestPermissionOutcome derives CanEqual:
  /** The prompt turn was cancelled before the user responded. */
  case Cancelled
  case Selected(optionId: PermissionOptionId, @jsonField("_meta") meta: Meta = None)

object RequestPermissionOutcome:
  private final case class SelectedOutcome(optionId: PermissionOptionId, @jsonField("_meta") meta: Meta = None)
      derives JsonCodec, CanEqual

  given JsonCodec[RequestPermissionOutcome] =
    val encoder = Json.Obj.encoder.contramap[RequestPermissionOutcome]:
      case Cancelled                => Json.Obj("outcome" -> Json.Str("cancelled"))
      case Selected(optionId, meta) => withField(toObj(SelectedOutcome(optionId, meta)), "outcome", Json.Str("selected"))
    val decoder = Json.Obj.decoder.mapOrFail: obj =>
      stringField(obj, "outcome") match
        case Some("cancelled") => Right(Cancelled)
        case Some("selected")  => fromJson[SelectedOutcome](obj).map(s => Selected(s.optionId, s.meta))
        case other             => Left(s"unknown permission outcome: $other")
    JsonCodec(encoder, decoder)

final case class RequestPermissionResponse(outcome: RequestPermissionOutcome, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual
