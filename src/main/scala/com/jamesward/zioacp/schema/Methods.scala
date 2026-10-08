package com.jamesward.zioacp.schema

import zio.*
import zio.json.*

/** A JSON-RPC request method, with the types of its params and result. */
final case class RequestMethod[Req, Res](name: String)(using val request: JsonCodec[Req], val response: JsonCodec[Res]):
  /** The answer of a peer that doesn't implement this method. */
  def unsupported[A]: IO[RpcError, A] = ZIO.fail(RpcError.methodNotFound(name))

/** A JSON-RPC notification method, with the type of its params. */
final case class NotificationMethod[N](name: String)(using val params: JsonCodec[N])

/** The ACP v1 methods. */
object Methods:

  /** Methods the agent implements and the client calls. */
  object Agent:
    val Initialize             = RequestMethod[InitializeRequest, InitializeResponse]("initialize")
    val Authenticate           = RequestMethod[AuthenticateRequest, AuthenticateResponse]("authenticate")
    val Logout                 = RequestMethod[LogoutRequest, LogoutResponse]("logout")
    val NewSession             = RequestMethod[NewSessionRequest, NewSessionResponse]("session/new")
    val LoadSession            = RequestMethod[LoadSessionRequest, LoadSessionResponse]("session/load")
    val ListSessions           = RequestMethod[ListSessionsRequest, ListSessionsResponse]("session/list")
    val DeleteSession          = RequestMethod[DeleteSessionRequest, DeleteSessionResponse]("session/delete")
    val ResumeSession          = RequestMethod[ResumeSessionRequest, ResumeSessionResponse]("session/resume")
    val CloseSession           = RequestMethod[CloseSessionRequest, CloseSessionResponse]("session/close")
    val SetSessionMode         = RequestMethod[SetSessionModeRequest, SetSessionModeResponse]("session/set_mode")
    val SetSessionConfigOption =
      RequestMethod[SetSessionConfigOptionRequest, SetSessionConfigOptionResponse]("session/set_config_option")
    val Prompt                 = RequestMethod[PromptRequest, PromptResponse]("session/prompt")
    val Cancel                 = NotificationMethod[CancelNotification]("session/cancel")

  /** Methods the client implements and the agent calls. */
  object Client:
    val SessionUpdate       = NotificationMethod[SessionNotification]("session/update")
    val RequestPermission   = RequestMethod[RequestPermissionRequest, RequestPermissionResponse]("session/request_permission")
    val ReadTextFile        = RequestMethod[ReadTextFileRequest, ReadTextFileResponse]("fs/read_text_file")
    val WriteTextFile       = RequestMethod[WriteTextFileRequest, WriteTextFileResponse]("fs/write_text_file")
    val CreateTerminal      = RequestMethod[CreateTerminalRequest, CreateTerminalResponse]("terminal/create")
    val TerminalOutput      = RequestMethod[TerminalOutputRequest, TerminalOutputResponse]("terminal/output")
    val ReleaseTerminal     = RequestMethod[ReleaseTerminalRequest, ReleaseTerminalResponse]("terminal/release")
    val WaitForTerminalExit = RequestMethod[WaitForTerminalExitRequest, WaitForTerminalExitResponse]("terminal/wait_for_exit")
    val KillTerminal        = RequestMethod[KillTerminalRequest, KillTerminalResponse]("terminal/kill")
    val CreateElicitation   = RequestMethod[CreateElicitationRequest, CreateElicitationResponse]("elicitation/create")
    val CompleteElicitation = NotificationMethod[CompleteElicitationNotification]("elicitation/complete")

  /** Methods either side may send. */
  object Protocol:
    val CancelRequest = NotificationMethod[CancelRequestNotification]("$/cancel_request")

  /** Method names starting with `_` are reserved for extensions. */
  def isExtension(method: String): Boolean = method.startsWith("_")
