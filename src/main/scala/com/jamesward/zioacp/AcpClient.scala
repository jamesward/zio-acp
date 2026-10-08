package com.jamesward.zioacp

import com.jamesward.zioacp.jsonrpc.{NotificationRoute, Route, Routes, RpcConnection}
import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*

/** Connects a [[Client]] to an agent. */
object AcpClient:

  /** Connects to an agent over `transport`. The connection closes with the scope. */
  def connect(transport: Transport, client: Client, drainTimeout: Duration = 30.seconds): ZIO[Scope, Nothing, AgentConnection] =
    defer:
      val permissions = Ref.make(Map.empty[SessionId, Set[Promise[Nothing, Unit]]]).run
      val rpc = RpcConnection.make(transport, drainTimeout)(_ => ZIO.succeed(routes(client, permissions))).run
      ZIO.addFinalizer(rpc.close).run
      AgentConnection(rpc, permissions)

  /** Starts an agent subprocess and connects to it over its stdin and stdout. */
  def launch(
    agent: AgentCommand,
    client: Client,
    stderr: String => UIO[Unit] = line => ZIO.logInfo(s"agent stderr: $line"),
  ): ZIO[Scope, Throwable, AgentConnection] =
    Transport.process(agent, stderr).flatMap(connect(_, client))

  private def routes(client: Client, permissions: Ref[Map[SessionId, Set[Promise[Nothing, Unit]]]]): Routes =
    import Methods.Client as M
    Routes(
      Seq(
        Route(M.RequestPermission)((_, req) => requestPermission(client, permissions, req)),
        Route(M.ReadTextFile)((_, req) => client.readTextFile(req)),
        Route(M.WriteTextFile)((_, req) => client.writeTextFile(req)),
        Route(M.CreateTerminal)((_, req) => client.createTerminal(req)),
        Route(M.TerminalOutput)((_, req) => client.terminalOutput(req)),
        Route(M.ReleaseTerminal)((_, req) => client.releaseTerminal(req)),
        Route(M.WaitForTerminalExit)((_, req) => client.waitForTerminalExit(req)),
        Route(M.KillTerminal)((_, req) => client.killTerminal(req)),
        Route(M.CreateElicitation)((_, req) => client.createElicitation(req)),
      ),
      Seq(
        NotificationRoute(M.SessionUpdate)(client.sessionUpdate),
        NotificationRoute(M.CompleteElicitation)(client.completeElicitation),
      ),
      client.extMethod,
      client.extNotification,
    )

  private def requestPermission(
    client: Client,
    permissions: Ref[Map[SessionId, Set[Promise[Nothing, Unit]]]],
    request: RequestPermissionRequest,
  ): IO[RpcError, RequestPermissionResponse] =
    val sessionId = request.sessionId
    defer:
      val cancelled = Promise.make[Nothing, Unit].run
      permissions.update(p => p.updated(sessionId, p.get(sessionId).fold(Set(cancelled))(_ + cancelled))).run
      client
        .requestPermission(request)
        .raceFirst(cancelled.await.as(RequestPermissionResponse(RequestPermissionOutcome.Cancelled)))
        .ensuring(permissions.update(p => p.updated(sessionId, p.get(sessionId).fold(Set.empty)(_ - cancelled))))
        .run
