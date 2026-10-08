package com.jamesward.zioacp

import com.jamesward.zioacp.schema.*
import zio.*
import zio.json.ast.Json

/**
 * An ACP agent: the methods a client calls. Only [[initialize]], [[newSession]] and [[prompt]] are required; the others
 * answer "method not found" unless overridden, and an agent should advertise the ones it overrides in its
 * [[AgentCapabilities]].
 */
trait Agent:
  def initialize(request: InitializeRequest): IO[RpcError, InitializeResponse]

  def newSession(request: NewSessionRequest): IO[RpcError, NewSessionResponse]

  /**
   * Runs a prompt turn. When the client sends `session/cancel`, `turn` signals it: the agent should stop, send its last
   * updates and answer [[StopReason.Cancelled]]. An agent that hasn't answered `cancelGrace` after the cancel (see
   * [[AgentOptions]]) is interrupted and the turn is answered `cancelled` for it, and so is one that fails after the
   * cancel, as the protocol requires.
   */
  def prompt(request: PromptRequest, turn: PromptTurn): IO[RpcError, PromptResponse]

  /** Called when the client cancels a session's prompt turn, after the turn has been signalled. */
  def cancel(notification: CancelNotification): UIO[Unit] = ZIO.unit

  def authenticate(request: AuthenticateRequest): IO[RpcError, AuthenticateResponse] =
    Methods.Agent.Authenticate.unsupported

  def logout(request: LogoutRequest): IO[RpcError, LogoutResponse] =
    Methods.Agent.Logout.unsupported

  def loadSession(request: LoadSessionRequest): IO[RpcError, LoadSessionResponse] =
    Methods.Agent.LoadSession.unsupported

  def listSessions(request: ListSessionsRequest): IO[RpcError, ListSessionsResponse] =
    Methods.Agent.ListSessions.unsupported

  def deleteSession(request: DeleteSessionRequest): IO[RpcError, DeleteSessionResponse] =
    Methods.Agent.DeleteSession.unsupported

  def resumeSession(request: ResumeSessionRequest): IO[RpcError, ResumeSessionResponse] =
    Methods.Agent.ResumeSession.unsupported

  def closeSession(request: CloseSessionRequest): IO[RpcError, CloseSessionResponse] =
    Methods.Agent.CloseSession.unsupported

  def setSessionMode(request: SetSessionModeRequest): IO[RpcError, SetSessionModeResponse] =
    Methods.Agent.SetSessionMode.unsupported

  def setSessionConfigOption(request: SetSessionConfigOptionRequest): IO[RpcError, SetSessionConfigOptionResponse] =
    Methods.Agent.SetSessionConfigOption.unsupported

  /** Handles an extension request, whose method starts with `_`. */
  def extMethod(method: String, params: Json): IO[RpcError, Json] = ZIO.fail(RpcError.methodNotFound(method))

  /** Handles an extension notification, whose method starts with `_`. */
  def extNotification(method: String, params: Json): UIO[Unit] = ZIO.unit

/** The cancellation signal of one prompt turn. */
final case class PromptTurn(private val cancelled: Promise[Nothing, Unit]):
  /** Completes when the client cancels the turn. */
  def awaitCancel: UIO[Unit] = cancelled.await

  def isCancelled: UIO[Boolean] = cancelled.isDone

/**
 * @param cancelGrace
 *   how long a prompt turn may keep running after `session/cancel` before it is interrupted and answered `cancelled`
 * @param drainTimeout
 *   how long requests still running when the client disconnects may take to answer
 */
final case class AgentOptions(cancelGrace: Duration = 5.seconds, drainTimeout: Duration = 30.seconds)
