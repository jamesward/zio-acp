package com.jamesward.zioacp

import com.jamesward.zioacp.jsonrpc.{NotificationRoute, Route, Routes, RpcConnection}
import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*

/** Serves an [[Agent]] to a client. */
object AcpAgent:

  /**
   * Serves the agent over `transport` until the client disconnects, answering the requests still running before it
   * returns. The agent is built from its connection to the client, which it uses to send updates and call the client.
   */
  def serve(transport: Transport, options: AgentOptions = AgentOptions())(agent: ClientConnection => UIO[Agent]): UIO[Unit] =
    ZIO.scoped:
      defer:
        val turns = Ref.make(Map.empty[SessionId, Set[Promise[Nothing, Unit]]]).run
        val connection =
          RpcConnection.make(transport, options.drainTimeout)(rpc => agent(ClientConnection(rpc)).map(routes(_, turns, options))).run
        connection.awaitClosed.run

  /** Serves the agent on stdin and stdout, the standard ACP transport for an agent launched by a client. */
  def serveStdio(options: AgentOptions = AgentOptions())(agent: ClientConnection => UIO[Agent]): UIO[Unit] =
    ZIO.scoped(Transport.stdio.flatMap(serve(_, options)(agent)))

  /**
   * Sends ZIO's log output to stderr instead of stdout, which carries the protocol on the stdio transport. Use it as the
   * `bootstrap` of a stdio agent's `ZIOAppDefault`.
   */
  val stderrLogging: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers ++
      Runtime.addLogger(ZLogger.default.map(line => java.lang.System.err.println(line)).filterLogLevel(_ >= LogLevel.Info))

  private type Turns = Ref[Map[SessionId, Set[Promise[Nothing, Unit]]]]

  private def routes(agent: Agent, turns: Turns, options: AgentOptions): Routes =
    import Methods.Agent as M
    Routes(
      Seq(
        Route(M.Initialize)((_, req) => agent.initialize(req)),
        Route(M.Authenticate)((_, req) => agent.authenticate(req)),
        Route(M.Logout)((_, req) => agent.logout(req)),
        Route(M.NewSession)((_, req) => agent.newSession(req)),
        Route(M.LoadSession)((_, req) => agent.loadSession(req)),
        Route(M.ListSessions)((_, req) => agent.listSessions(req)),
        Route(M.DeleteSession)((_, req) => agent.deleteSession(req)),
        Route(M.ResumeSession)((_, req) => agent.resumeSession(req)),
        Route(M.CloseSession)((_, req) => cancelTurns(agent, turns, CancelNotification(req.sessionId)) *> agent.closeSession(req)),
        Route(M.SetSessionMode)((_, req) => agent.setSessionMode(req)),
        Route(M.SetSessionConfigOption)((_, req) => agent.setSessionConfigOption(req)),
        Route(M.Prompt)((_, req) => prompt(agent, turns, options, req)),
      ),
      Seq(NotificationRoute(M.Cancel)(cancelTurns(agent, turns, _))),
      agent.extMethod,
      agent.extNotification,
    )

  private def prompt(agent: Agent, turns: Turns, options: AgentOptions, request: PromptRequest): IO[RpcError, PromptResponse] =
    val sessionId = request.sessionId
    val cancelled = PromptResponse(StopReason.Cancelled)
    defer:
      val signal = Promise.make[Nothing, Unit].run
      turns.update(t => t.updated(sessionId, t.get(sessionId).fold(Set(signal))(_ + signal))).run
      val turn = PromptTurn(signal)
      agent
        .prompt(request, turn)
        // a turn that fails after its cancel still answers "cancelled", as the protocol requires
        .catchAll(error => ZIO.ifZIO(turn.isCancelled)(ZIO.succeed(cancelled), ZIO.fail(error)))
        .raceFirst(
          turn.awaitCancel *> ZIO.sleep(options.cancelGrace) *>
            ZIO.logWarning(s"prompt turn of $sessionId still running ${options.cancelGrace} after its cancel; interrupting it")
              .as(cancelled),
        )
        .ensuring(turns.update(t => t.updated(sessionId, t.get(sessionId).fold(Set.empty)(_ - signal))))
        .run

  private def cancelTurns(agent: Agent, turns: Turns, notification: CancelNotification): UIO[Unit] =
    defer:
      val signals = turns.get.map(_.get(notification.sessionId).toList.flatten).run
      ZIO.foreachDiscard(signals)(_.succeed(())).run
      agent.cancel(notification).run
