package com.jamesward.zioacp

import com.jamesward.zioacp.schema.*
import zio.*
import zio.json.ast.Json

/**
 * An ACP client: the methods an agent calls. Only [[sessionUpdate]] and [[requestPermission]] are required; the others
 * answer "method not found" unless overridden, and a client should advertise the ones it overrides in its
 * [[ClientCapabilities]].
 *
 * [[sessionUpdate]] runs in the order updates arrive and before the response that follows them is delivered, so it must
 * not wait on a call to the agent.
 */
trait Client:
  def sessionUpdate(notification: SessionNotification): UIO[Unit]

  /**
   * Asks the user to authorize a tool call. When the client cancels the session's prompt turn, a pending request is
   * answered [[RequestPermissionOutcome.Cancelled]] for it, as the protocol requires.
   */
  def requestPermission(request: RequestPermissionRequest): IO[RpcError, RequestPermissionResponse]

  def readTextFile(request: ReadTextFileRequest): IO[RpcError, ReadTextFileResponse] =
    Methods.Client.ReadTextFile.unsupported

  def writeTextFile(request: WriteTextFileRequest): IO[RpcError, WriteTextFileResponse] =
    Methods.Client.WriteTextFile.unsupported

  def createTerminal(request: CreateTerminalRequest): IO[RpcError, CreateTerminalResponse] =
    Methods.Client.CreateTerminal.unsupported

  def terminalOutput(request: TerminalOutputRequest): IO[RpcError, TerminalOutputResponse] =
    Methods.Client.TerminalOutput.unsupported

  def releaseTerminal(request: ReleaseTerminalRequest): IO[RpcError, ReleaseTerminalResponse] =
    Methods.Client.ReleaseTerminal.unsupported

  def waitForTerminalExit(request: WaitForTerminalExitRequest): IO[RpcError, WaitForTerminalExitResponse] =
    Methods.Client.WaitForTerminalExit.unsupported

  def killTerminal(request: KillTerminalRequest): IO[RpcError, KillTerminalResponse] =
    Methods.Client.KillTerminal.unsupported

  def createElicitation(request: CreateElicitationRequest): IO[RpcError, CreateElicitationResponse] =
    Methods.Client.CreateElicitation.unsupported

  def completeElicitation(notification: CompleteElicitationNotification): UIO[Unit] = ZIO.unit

  /** Handles an extension request, whose method starts with `_`. */
  def extMethod(method: String, params: Json): IO[RpcError, Json] = ZIO.fail(RpcError.methodNotFound(method))

  /** Handles an extension notification, whose method starts with `_`. */
  def extNotification(method: String, params: Json): UIO[Unit] = ZIO.unit
