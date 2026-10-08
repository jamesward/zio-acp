package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*
import zio.json.ast.Json

final case class EnvVariable(name: String, value: String, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class HttpHeader(name: String, value: String, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

/** An MCP server the agent should connect to. Stdio is mandatory for agents; HTTP and SSE are capabilities. */
enum McpServer derives CanEqual:
  case Stdio(
    name: String,
    command: String,
    args: List[String] = Nil,
    env: List[EnvVariable] = Nil,
    @jsonField("_meta") meta: Meta = None,
  )
  case Http(name: String, url: String, headers: List[HttpHeader] = Nil, @jsonField("_meta") meta: Meta = None)
  case Sse(name: String, url: String, headers: List[HttpHeader] = Nil, @jsonField("_meta") meta: Meta = None)

object McpServer:
  private given JsonCodec[Stdio] = DeriveJsonCodec.gen
  private given JsonCodec[Http]  = DeriveJsonCodec.gen
  private given JsonCodec[Sse]   = DeriveJsonCodec.gen

  // stdio servers have no "type" property
  given JsonCodec[McpServer] = tagged[McpServer](
    "type",
    Variant[McpServer, Http]("http"),
    Variant[McpServer, Sse]("sse"),
  )(
    {
      case (None, obj) => fromJson[Stdio](obj)
      case (Some(t), _) => Left(s"unknown MCP server type: $t")
    },
    {
      case s: Stdio => Some(toObj(s))
      case _        => None
    },
  )

final case class NewSessionRequest(
  cwd: String,
  mcpServers: List[McpServer],
  additionalDirectories: List[String] = Nil,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class NewSessionResponse(
  sessionId: SessionId,
  modes: Option[SessionModeState] = None,
  configOptions: Option[List[SessionConfigOption]] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class LoadSessionRequest(
  sessionId: SessionId,
  cwd: String,
  mcpServers: List[McpServer],
  additionalDirectories: List[String] = Nil,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class LoadSessionResponse(
  modes: Option[SessionModeState] = None,
  configOptions: Option[List[SessionConfigOption]] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class ListSessionsRequest(
  cwd: Option[String] = None,
  cursor: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class SessionInfo(
  sessionId: SessionId,
  cwd: String,
  additionalDirectories: List[String] = Nil,
  title: Option[String] = None,
  updatedAt: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class ListSessionsResponse(
  sessions: List[SessionInfo],
  nextCursor: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class DeleteSessionRequest(sessionId: SessionId, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class DeleteSessionResponse(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class ResumeSessionRequest(
  sessionId: SessionId,
  cwd: String,
  mcpServers: List[McpServer] = Nil,
  additionalDirectories: List[String] = Nil,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class ResumeSessionResponse(
  modes: Option[SessionModeState] = None,
  configOptions: Option[List[SessionConfigOption]] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class CloseSessionRequest(sessionId: SessionId, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class CloseSessionResponse(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class SessionMode(
  id: SessionModeId,
  name: String,
  description: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class SessionModeState(
  currentModeId: SessionModeId,
  availableModes: List[SessionMode],
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class SetSessionModeRequest(sessionId: SessionId, modeId: SessionModeId, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

final case class SetSessionModeResponse(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual
