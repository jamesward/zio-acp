package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*
import zio.json.ast.Json

/** `session/prompt`: user input for a prompt turn. */
final case class PromptRequest(sessionId: SessionId, prompt: List[ContentBlock], @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

/** Why the agent stopped a prompt turn. Unknown reasons are kept as `Unknown`. */
enum StopReason derives CanEqual:
  case EndTurn, MaxTokens, MaxTurnRequests, Refusal, Cancelled
  case Unknown(value: String)

object StopReason:
  given JsonCodec[StopReason] = openEnum(Seq(EndTurn, MaxTokens, MaxTurnRequests, Refusal, Cancelled))(
    {
      case EndTurn         => "end_turn"
      case MaxTokens       => "max_tokens"
      case MaxTurnRequests => "max_turn_requests"
      case Refusal         => "refusal"
      case Cancelled       => "cancelled"
      case Unknown(value)  => value
    },
    Unknown(_),
  )

final case class PromptResponse(stopReason: StopReason, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

/** `session/cancel`: the client cancels the ongoing prompt turn of a session. */
final case class CancelNotification(sessionId: SessionId, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

/** `$/cancel_request`: cancels an in-flight JSON-RPC request. */
final case class CancelRequestNotification(requestId: RequestId, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

enum PlanEntryPriority derives CanEqual:
  case High, Medium, Low
  case Unknown(value: String)

object PlanEntryPriority:
  given JsonCodec[PlanEntryPriority] = openEnum(Seq(High, Medium, Low))(
    {
      case High           => "high"
      case Medium         => "medium"
      case Low            => "low"
      case Unknown(value) => value
    },
    Unknown(_),
  )

enum PlanEntryStatus derives CanEqual:
  case Pending, InProgress, Completed
  case Unknown(value: String)

object PlanEntryStatus:
  given JsonCodec[PlanEntryStatus] = openEnum(Seq(Pending, InProgress, Completed))(
    {
      case Pending        => "pending"
      case InProgress     => "in_progress"
      case Completed      => "completed"
      case Unknown(value) => value
    },
    Unknown(_),
  )

final case class PlanEntry(
  content: String,
  priority: PlanEntryPriority,
  status: PlanEntryStatus,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class UnstructuredCommandInput(hint: String, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class AvailableCommand(
  name: String,
  description: String,
  input: Option[UnstructuredCommandInput] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class Cost(amount: Double, currency: String, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

/** Real-time updates streamed by the agent during a session, sent as `session/update` notifications. */
enum SessionUpdate derives CanEqual:
  case UserMessageChunk(content: ContentBlock, messageId: Option[MessageId] = None, @jsonField("_meta") meta: Meta = None)
  case AgentMessageChunk(content: ContentBlock, messageId: Option[MessageId] = None, @jsonField("_meta") meta: Meta = None)
  case AgentThoughtChunk(content: ContentBlock, messageId: Option[MessageId] = None, @jsonField("_meta") meta: Meta = None)
  case ToolCallStarted(toolCall: ToolCall)
  case ToolCallUpdated(update: ToolCallUpdate)
  case Plan(entries: List[PlanEntry], @jsonField("_meta") meta: Meta = None)
  case AvailableCommandsUpdate(availableCommands: List[AvailableCommand], @jsonField("_meta") meta: Meta = None)
  case CurrentModeUpdate(currentModeId: SessionModeId, @jsonField("_meta") meta: Meta = None)
  case ConfigOptionUpdate(configOptions: List[SessionConfigOption], @jsonField("_meta") meta: Meta = None)
  case SessionInfoUpdate(title: Option[String] = None, updatedAt: Option[String] = None, @jsonField("_meta") meta: Meta = None)
  case UsageUpdate(used: Long, size: Long, cost: Option[Cost] = None, @jsonField("_meta") meta: Meta = None)

  /** An update kind this library doesn't know, from a newer protocol revision or an extension, kept as received. */
  case Unknown(sessionUpdate: String, raw: Json.Obj)

object SessionUpdate:
  private given JsonCodec[UserMessageChunk]        = DeriveJsonCodec.gen
  private given JsonCodec[AgentMessageChunk]       = DeriveJsonCodec.gen
  private given JsonCodec[AgentThoughtChunk]       = DeriveJsonCodec.gen
  private given JsonCodec[ToolCallStarted]         = summon[JsonCodec[ToolCall]].transform(ToolCallStarted(_), _.toolCall)
  private given JsonCodec[ToolCallUpdated]         = summon[JsonCodec[ToolCallUpdate]].transform(ToolCallUpdated(_), _.update)
  private given JsonCodec[Plan]                    = DeriveJsonCodec.gen
  private given JsonCodec[AvailableCommandsUpdate] = DeriveJsonCodec.gen
  private given JsonCodec[CurrentModeUpdate]       = DeriveJsonCodec.gen
  private given JsonCodec[ConfigOptionUpdate]      = DeriveJsonCodec.gen
  private given JsonCodec[SessionInfoUpdate]       = DeriveJsonCodec.gen
  private given JsonCodec[UsageUpdate]             = DeriveJsonCodec.gen

  given JsonCodec[SessionUpdate] = tagged[SessionUpdate](
    "sessionUpdate",
    Variant[SessionUpdate, UserMessageChunk]("user_message_chunk"),
    Variant[SessionUpdate, AgentMessageChunk]("agent_message_chunk"),
    Variant[SessionUpdate, AgentThoughtChunk]("agent_thought_chunk"),
    Variant[SessionUpdate, ToolCallStarted]("tool_call"),
    Variant[SessionUpdate, ToolCallUpdated]("tool_call_update"),
    Variant[SessionUpdate, Plan]("plan"),
    Variant[SessionUpdate, AvailableCommandsUpdate]("available_commands_update"),
    Variant[SessionUpdate, CurrentModeUpdate]("current_mode_update"),
    Variant[SessionUpdate, ConfigOptionUpdate]("config_option_update"),
    Variant[SessionUpdate, SessionInfoUpdate]("session_info_update"),
    Variant[SessionUpdate, UsageUpdate]("usage_update"),
  )(
    {
      case (Some(kind), obj) => Right(Unknown(kind, obj))
      case (None, _)         => Left("missing sessionUpdate")
    },
    {
      case Unknown(kind, raw) => Some(withField(raw, "sessionUpdate", Json.Str(kind)))
      case _                  => None
    },
  )

/** `session/update`: a progress update for a session. */
final case class SessionNotification(sessionId: SessionId, update: SessionUpdate, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual
