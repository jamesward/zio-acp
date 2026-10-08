package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*

final case class ReadTextFileRequest(
  sessionId: SessionId,
  path: String,
  line: Option[Long] = None,
  limit: Option[Long] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class ReadTextFileResponse(content: String, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class WriteTextFileRequest(
  sessionId: SessionId,
  path: String,
  content: String,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class WriteTextFileResponse(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class CreateTerminalRequest(
  sessionId: SessionId,
  command: String,
  args: List[String] = Nil,
  env: List[EnvVariable] = Nil,
  cwd: Option[String] = None,
  outputByteLimit: Option[Long] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class CreateTerminalResponse(terminalId: TerminalId, @jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class TerminalOutputRequest(sessionId: SessionId, terminalId: TerminalId, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

final case class TerminalExitStatus(
  exitCode: Option[Long] = None,
  signal: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class TerminalOutputResponse(
  output: String,
  truncated: Boolean,
  exitStatus: Option[TerminalExitStatus] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class ReleaseTerminalRequest(sessionId: SessionId, terminalId: TerminalId, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

final case class ReleaseTerminalResponse(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual

final case class WaitForTerminalExitRequest(
  sessionId: SessionId,
  terminalId: TerminalId,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class WaitForTerminalExitResponse(
  exitCode: Option[Long] = None,
  signal: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class KillTerminalRequest(sessionId: SessionId, terminalId: TerminalId, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual

final case class KillTerminalResponse(@jsonField("_meta") meta: Meta = None) derives JsonCodec, CanEqual
