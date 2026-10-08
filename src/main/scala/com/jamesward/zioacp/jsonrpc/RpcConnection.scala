package com.jamesward.zioacp.jsonrpc

import com.jamesward.zioacp.Transport
import com.jamesward.zioacp.schema.*
import zio.*
import zio.direct.*
import zio.json.*
import zio.json.ast.Json

/** Handles the requests and notifications a peer sends. */
trait RpcHandler:
  /** Answers a request. An unknown method fails with [[RpcError.methodNotFound]]. */
  def request(id: RequestId, method: String, params: Option[Json]): IO[RpcError, Json]

  /** Handles a notification. Unknown notifications are ignored. */
  def notification(method: String, params: Option[Json]): UIO[Unit]

/**
 * One side of a JSON-RPC 2.0 connection, where both sides send requests.
 *
 * Incoming requests run concurrently, each on its own fiber; `$/cancel_request` interrupts one, and it is answered with
 * [[ErrorCode.RequestCancelled]]. Incoming notifications and responses are handled one at a time, in the order they
 * arrived, so a response is delivered only after the notifications the peer sent before it. A notification handler must
 * therefore not wait for the response to its own outgoing request; it can fork that work instead.
 *
 * Interrupting an outgoing request sends `$/cancel_request` for it.
 */
final case class RpcConnection private (
  private val transport: Transport,
  private val nextId: Ref[Long],
  private val pending: Ref[Map[RequestId, Promise[RpcError, Json]]],
  private val inflight: Ref[Map[RequestId, Fiber.Runtime[Nothing, Unit]]],
  private val inbox: Queue[Option[Message]],
  private val inputClosed: Promise[Nothing, Unit],
):

  def request(method: String, params: Json): IO[RpcError, Json] =
    ZIO.uninterruptibleMask: restore =>
      defer:
        val id = RequestId.Number(nextId.getAndUpdate(_ + 1).run)
        val promise = Promise.make[RpcError, Json].run
        register(id, promise).run
        send(Message.Request(id, method, Some(params))).tapError(_ => pending.update(_ - id)).run
        restore(promise.await).onInterrupt(cancelOutgoing(id)).run

  def notify(method: String, params: Json): IO[RpcError, Unit] =
    send(Message.Notification(method, Some(params)))

  def call[Req, Res](method: RequestMethod[Req, Res], request: Req): IO[RpcError, Res] =
    defer:
      val params = RpcConnection.encode(request)(using method.request.encoder).run
      val result = this.request(method.name, params).run
      RpcConnection.decodeResult(method.name, result)(using method.response.decoder).run

  def notify[N](method: NotificationMethod[N], notification: N): IO[RpcError, Unit] =
    RpcConnection.encode(notification)(using method.params.encoder).flatMap(notify(method.name, _))

  /** Completes when the peer has closed the connection and every incoming request has been answered. */
  def awaitClosed: UIO[Unit] = inputClosed.await

  /** Closes the outgoing side of the transport. */
  def close: UIO[Unit] = transport.close

  private def register(id: RequestId, promise: Promise[RpcError, Json]): IO[RpcError, Unit] =
    ZIO.ifZIO(inputClosed.isDone)(ZIO.fail(RpcError.connectionClosed), pending.update(_ + (id -> promise)))

  private def cancelOutgoing(id: RequestId): UIO[Unit] =
    pending.update(_ - id) *>
      RpcConnection
        .encode(CancelRequestNotification(id))
        .flatMap(notify(Methods.Protocol.CancelRequest.name, _))
        .catchAllCause(cause => ZIO.logDebugCause(s"could not send $$/cancel_request for $id", cause))

  private def send(message: Message): IO[RpcError, Unit] =
    transport
      .send(Message.encode(message))
      .tapErrorCause(cause => ZIO.logWarningCause("sending a message failed", cause))
      .orElseFail(RpcError.connectionClosed)

  private def receive(handler: RpcHandler)(line: String): UIO[Unit] =
    Message.parse(line) match
      case Parsed.Invalid(id, error) =>
        ZIO.logWarning(s"invalid message: ${error.message}") *> send(Message.Response(id, Left(error))).ignore
      case Parsed.Ignored(reason) =>
        ZIO.logWarning(s"ignored message: $reason")
      case Parsed.Valid(Message.Request(id, method, params)) =>
        startRequest(handler, id, method, params)
      case Parsed.Valid(Message.Notification(method, params)) if method == Methods.Protocol.CancelRequest.name =>
        cancelIncoming(params)
      case Parsed.Valid(message) =>
        inbox.offer(Some(message)).unit

  private def startRequest(handler: RpcHandler, id: RequestId, method: String, params: Option[Json]): UIO[Unit] =
    val answer =
      handler
        .request(id, method, params)
        .catchAllDefect(defect =>
          ZIO.logErrorCause(s"$method handler died", Cause.die(defect)) *>
            ZIO.fail(RpcError.internalError(s"$method failed unexpectedly")),
        )
        .either
        .flatMap(result => send(Message.Response(id, result)))
        .onInterrupt(send(Message.Response(id, Left(RpcError.requestCancelled))).ignore)
        .ignore
    defer:
      val gate = Promise.make[Nothing, Unit].run
      // registered before it runs, so a $/cancel_request right behind the request finds it
      val fiber = (gate.await *> answer).ensuring(inflight.update(_ - id)).forkDaemon.run
      inflight.update(_ + (id -> fiber)).run
      gate.succeed(()).unit.run

  private def cancelIncoming(params: Option[Json]): UIO[Unit] =
    RpcConnection.decodeParams[CancelRequestNotification](params).either.flatMap:
      case Right(cancel) =>
        inflight.get.flatMap(_.get(cancel.requestId).fold(ZIO.unit)(_.interruptFork))
      case Left(error) =>
        ZIO.logWarning(s"invalid $$/cancel_request: ${error.message}")

  private def dispatch(handler: RpcHandler)(message: Message): UIO[Unit] =
    message match
      case Message.Notification(method, params) =>
        handler.notification(method, params).catchAllCause(cause => ZIO.logErrorCause(s"$method handler failed", cause))
      case Message.Response(id, result) =>
        pending.modify(p => (p.get(id), p - id)).flatMap:
          case Some(promise) => promise.complete(ZIO.fromEither(result)).unit
          case None          => ZIO.logWarning(s"response to unknown request $id")
      case request: Message.Request =>
        ZIO.logError(s"request ${request.id} reached the ordered inbox")

  /** Reads until the peer closes the connection, then answers the requests still running, closes its side and stops. */
  private def run(handler: RpcHandler, drainTimeout: Duration): UIO[Unit] =
    defer:
      val dispatcher =
        ZIO.iterate(true)(identity)(_ => inbox.take.flatMap(ZIO.foreach(_)(dispatch(handler)).map(_.isDefined))).fork.run
      transport.incoming
        .foreach(receive(handler))
        .catchAllCause(cause => ZIO.logDebugCause("incoming stream ended with an error", cause))
        .run
      inbox.offer(None).run
      dispatcher.join.run
      // no more responses can arrive, so requests waiting for one fail now
      val waiting = pending.getAndSet(Map.empty).run
      ZIO.foreachDiscard(waiting.values)(_.fail(RpcError.connectionClosed)).run
      drain(drainTimeout).run
      transport.close.run
      inputClosed.succeed(()).unit.run

  private def drain(timeout: Duration): UIO[Unit] =
    val running = inflight.get.map(_.values)
    running
      .flatMap(fibers => ZIO.foreachDiscard(fibers)(_.await))
      .repeatUntilZIO(_ => running.map(_.isEmpty))
      .timeout(timeout)
      .flatMap:
        case Some(_) => ZIO.unit
        case None =>
          running.flatMap: fibers =>
            ZIO.logWarning(s"interrupting ${fibers.size} requests still running $timeout after the connection closed") *>
              ZIO.foreachDiscard(fibers)(_.interrupt)

object RpcConnection:

  /**
   * Starts a connection over `transport`. The handler is built from the connection, so that it can call the peer. The
   * connection stops reading when the scope closes.
   */
  def make(transport: Transport, drainTimeout: Duration = 30.seconds)(
    handler: RpcConnection => UIO[RpcHandler],
  ): ZIO[Scope, Nothing, RpcConnection] =
    defer:
      val connection = RpcConnection(
        transport,
        Ref.make(0L).run,
        Ref.make(Map.empty[RequestId, Promise[RpcError, Json]]).run,
        Ref.make(Map.empty[RequestId, Fiber.Runtime[Nothing, Unit]]).run,
        Queue.unbounded[Option[Message]].run,
        Promise.make[Nothing, Unit].run,
      )
      val h = handler(connection).run
      connection.run(h, drainTimeout).forkScoped.run
      connection

  def encode[A](a: A)(using encoder: JsonEncoder[A]): IO[RpcError, Json] =
    ZIO.fromEither(encoder.toJsonAST(a)).mapError(RpcError.internalError)

  /** Decodes request params, which ACP always sends as an object (absent or null params read as `{}`). */
  def decodeParams[A](params: Option[Json])(using decoder: JsonDecoder[A]): IO[RpcError, A] =
    params match
      case None | Some(Json.Null) => ZIO.fromEither(decoder.fromJsonAST(Json.Obj())).mapError(RpcError.invalidParams)
      case Some(obj: Json.Obj)    => ZIO.fromEither(decoder.fromJsonAST(obj)).mapError(RpcError.invalidParams)
      case Some(_)                => ZIO.fail(RpcError.invalidParams("params must be an object"))

  /** Decodes a result; a `null` result reads as `{}`, which is valid for the responses whose fields are all optional. */
  def decodeResult[A](method: String, result: Json)(using decoder: JsonDecoder[A]): IO[RpcError, A] =
    val json = result match
      case Json.Null => Json.Obj()
      case other     => other
    ZIO.fromEither(decoder.fromJsonAST(json)).mapError(e => RpcError.internalError(s"invalid $method response: $e"))

  private given CanEqual[Json, Json] = CanEqual.derived
