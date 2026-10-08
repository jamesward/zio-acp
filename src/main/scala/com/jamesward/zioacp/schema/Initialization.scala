package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*
import zio.json.ast.Json

/** Name and version of an ACP implementation. */
final case class Implementation(
  name: String,
  version: String,
  title: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** A capability that is either present (an object, possibly with `_meta`) or absent. */
final case class Marker(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class FileSystemCapabilities(
  readTextFile: Boolean = false,
  writeTextFile: Boolean = false,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class SessionConfigOptionsCapabilities(boolean: Option[Marker] = None, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

final case class ClientSessionCapabilities(
  configOptions: Option[SessionConfigOptionsCapabilities] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class AuthCapabilities(terminal: Boolean = false, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class ElicitationCapabilities(
  form: Option[Marker] = None,
  url: Option[Marker] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** What the client supports; the agent must not call client methods the client didn't advertise. */
final case class ClientCapabilities(
  fs: FileSystemCapabilities = FileSystemCapabilities(),
  terminal: Boolean = false,
  session: Option[ClientSessionCapabilities] = None,
  auth: AuthCapabilities = AuthCapabilities(),
  elicitation: Option[ElicitationCapabilities] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class PromptCapabilities(
  image: Boolean = false,
  audio: Boolean = false,
  embeddedContext: Boolean = false,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class McpCapabilities(http: Boolean = false, sse: Boolean = false, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

final case class SessionCapabilities(
  list: Option[Marker] = None,
  delete: Option[Marker] = None,
  additionalDirectories: Option[Marker] = None,
  resume: Option[Marker] = None,
  close: Option[Marker] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class AgentAuthCapabilities(logout: Option[Marker] = None, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

/** What the agent supports beyond the baseline methods. */
final case class AgentCapabilities(
  loadSession: Boolean = false,
  promptCapabilities: PromptCapabilities = PromptCapabilities(),
  mcpCapabilities: McpCapabilities = McpCapabilities(),
  sessionCapabilities: SessionCapabilities = SessionCapabilities(),
  auth: AgentAuthCapabilities = AgentAuthCapabilities(),
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** A way to authenticate with the agent. */
enum AuthMethod derives CanEqual:
  /** The agent handles authentication itself, through `authenticate`. */
  case Agent(id: AuthMethodId, name: String, description: Option[String] = None, @jsonField("_meta") meta: Meta = None)

  /** The client runs the agent program again with `args` and `env`, in a terminal, so the user can log in. */
  case Terminal(
    id: AuthMethodId,
    name: String,
    description: Option[String] = None,
    args: List[String] = Nil,
    env: Map[String, String] = Map.empty,
    @jsonField("_meta") meta: Meta = None,
  )

  def id: AuthMethodId

object AuthMethod:
  private given JsonCodec[Agent]    = DeriveJsonCodec.gen
  private given JsonCodec[Terminal] = DeriveJsonCodec.gen

  // agent-handled methods have no "type" property
  given JsonCodec[AuthMethod] = tagged[AuthMethod]("type", Variant[AuthMethod, Terminal]("terminal"))(
    {
      case (None, obj)  => fromJson[Agent](obj)
      case (Some(t), _) => Left(s"unknown auth method type: $t")
    },
    {
      case a: Agent => Some(toObj(a))
      case _        => None
    },
  )

/** `initialize`: protocol version negotiation and capability exchange. */
final case class InitializeRequest(
  protocolVersion: ProtocolVersion,
  clientCapabilities: ClientCapabilities = ClientCapabilities(),
  clientInfo: Option[Implementation] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class InitializeResponse(
  protocolVersion: ProtocolVersion,
  agentCapabilities: AgentCapabilities = AgentCapabilities(),
  authMethods: List[AuthMethod] = Nil,
  agentInfo: Option[Implementation] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class AuthenticateRequest(methodId: AuthMethodId, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class AuthenticateResponse(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class LogoutRequest(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class LogoutResponse(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual
