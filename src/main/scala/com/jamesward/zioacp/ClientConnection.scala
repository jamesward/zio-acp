package com.jamesward.zioacp

import com.jamesward.zioacp.jsonrpc.RpcConnection
import com.jamesward.zioacp.schema.*
import zio.*
import zio.json.ast.Json

/**
 * An agent's connection to its client. An agent must only call the methods the client advertised in its
 * [[ClientCapabilities]]. Interrupting a call sends `$/cancel_request` for it.
 */
final case class ClientConnection(private val rpc: RpcConnection):
  import Methods.Client as M

  def sessionUpdate(notification: SessionNotification): IO[RpcError, Unit] = rpc.notify(M.SessionUpdate, notification)

  def sessionUpdate(sessionId: SessionId, update: SessionUpdate): IO[RpcError, Unit] =
    sessionUpdate(SessionNotification(sessionId, update))

  def requestPermission(request: RequestPermissionRequest): IO[RpcError, RequestPermissionResponse] =
    rpc.call(M.RequestPermission, request)

  def readTextFile(request: ReadTextFileRequest): IO[RpcError, ReadTextFileResponse] = rpc.call(M.ReadTextFile, request)

  def writeTextFile(request: WriteTextFileRequest): IO[RpcError, WriteTextFileResponse] = rpc.call(M.WriteTextFile, request)

  def createTerminal(request: CreateTerminalRequest): IO[RpcError, CreateTerminalResponse] =
    rpc.call(M.CreateTerminal, request)

  def terminalOutput(request: TerminalOutputRequest): IO[RpcError, TerminalOutputResponse] =
    rpc.call(M.TerminalOutput, request)

  def releaseTerminal(request: ReleaseTerminalRequest): IO[RpcError, ReleaseTerminalResponse] =
    rpc.call(M.ReleaseTerminal, request)

  def waitForTerminalExit(request: WaitForTerminalExitRequest): IO[RpcError, WaitForTerminalExitResponse] =
    rpc.call(M.WaitForTerminalExit, request)

  def killTerminal(request: KillTerminalRequest): IO[RpcError, KillTerminalResponse] = rpc.call(M.KillTerminal, request)

  def createElicitation(request: CreateElicitationRequest): IO[RpcError, CreateElicitationResponse] =
    rpc.call(M.CreateElicitation, request)

  def completeElicitation(notification: CompleteElicitationNotification): IO[RpcError, Unit] =
    rpc.notify(M.CompleteElicitation, notification)

  /** Sends an extension request; `method` must start with `_`. */
  def extMethod(method: String, params: Json): IO[RpcError, Json] = rpc.request(method, params)

  /** Sends an extension notification; `method` must start with `_`. */
  def extNotification(method: String, params: Json): IO[RpcError, Unit] = rpc.notify(method, params)
