package com.jamesward.zioacp

import com.jamesward.zioacp.jsonrpc.RpcConnection
import com.jamesward.zioacp.schema.*
import zio.*
import zio.json.ast.Json

/**
 * A client's connection to an agent. A client must only call the optional methods the agent advertised in its
 * [[AgentCapabilities]]. Interrupting a call sends `$/cancel_request` for it.
 */
final case class AgentConnection private[zioacp] (
  private val rpc: RpcConnection,
  private val permissions: Ref[Map[SessionId, Set[Promise[Nothing, Unit]]]],
):
  import Methods.Agent as M

  def initialize(request: InitializeRequest): IO[RpcError, InitializeResponse] = rpc.call(M.Initialize, request)

  def authenticate(request: AuthenticateRequest): IO[RpcError, AuthenticateResponse] = rpc.call(M.Authenticate, request)

  def logout(request: LogoutRequest): IO[RpcError, LogoutResponse] = rpc.call(M.Logout, request)

  def newSession(request: NewSessionRequest): IO[RpcError, NewSessionResponse] = rpc.call(M.NewSession, request)

  def loadSession(request: LoadSessionRequest): IO[RpcError, LoadSessionResponse] = rpc.call(M.LoadSession, request)

  def listSessions(request: ListSessionsRequest): IO[RpcError, ListSessionsResponse] = rpc.call(M.ListSessions, request)

  def deleteSession(request: DeleteSessionRequest): IO[RpcError, DeleteSessionResponse] = rpc.call(M.DeleteSession, request)

  def resumeSession(request: ResumeSessionRequest): IO[RpcError, ResumeSessionResponse] = rpc.call(M.ResumeSession, request)

  def closeSession(request: CloseSessionRequest): IO[RpcError, CloseSessionResponse] = rpc.call(M.CloseSession, request)

  def setSessionMode(request: SetSessionModeRequest): IO[RpcError, SetSessionModeResponse] =
    rpc.call(M.SetSessionMode, request)

  def setSessionConfigOption(request: SetSessionConfigOptionRequest): IO[RpcError, SetSessionConfigOptionResponse] =
    rpc.call(M.SetSessionConfigOption, request)

  /** Runs a prompt turn. Updates for the turn reach [[Client.sessionUpdate]] before this completes. */
  def prompt(request: PromptRequest): IO[RpcError, PromptResponse] = rpc.call(M.Prompt, request)

  /**
   * Cancels the session's prompt turn: sends `session/cancel` and answers the session's pending permission requests
   * [[RequestPermissionOutcome.Cancelled]]. The agent then answers the turn's prompt with [[StopReason.Cancelled]].
   */
  def cancel(notification: CancelNotification): IO[RpcError, Unit] =
    rpc.notify(M.Cancel, notification) *>
      permissions.get.flatMap(p => ZIO.foreachDiscard(p.get(notification.sessionId).toList.flatten)(_.succeed(())))

  def cancel(sessionId: SessionId): IO[RpcError, Unit] = cancel(CancelNotification(sessionId))

  /** Sends an extension request; `method` must start with `_`. */
  def extMethod(method: String, params: Json): IO[RpcError, Json] = rpc.request(method, params)

  /** Sends an extension notification; `method` must start with `_`. */
  def extNotification(method: String, params: Json): IO[RpcError, Unit] = rpc.notify(method, params)

  /** Closes the connection to the agent; a stdio agent exits when its stdin closes. */
  def close: UIO[Unit] = rpc.close

  /** Completes when the agent has closed the connection. */
  def awaitClosed: UIO[Unit] = rpc.awaitClosed
