package com.jamesward.zioacp.jsonrpc

import com.jamesward.zioacp.schema.*
import zio.*
import zio.json.ast.Json

/** A typed handler for one request method. */
final case class Route(method: String, handle: (RequestId, Option[Json]) => IO[RpcError, Json])

object Route:
  def apply[Req, Res](method: RequestMethod[Req, Res])(handle: (RequestId, Req) => IO[RpcError, Res]): Route =
    Route(
      method.name,
      (id, params) =>
        RpcConnection
          .decodeParams(params)(using method.request.decoder)
          .flatMap(handle(id, _))
          .flatMap(RpcConnection.encode(_)(using method.response.encoder)),
    )

/** A typed handler for one notification method. */
final case class NotificationRoute(method: String, handle: Option[Json] => UIO[Unit])

object NotificationRoute:
  def apply[N](method: NotificationMethod[N])(handle: N => UIO[Unit]): NotificationRoute =
    NotificationRoute(
      method.name,
      params =>
        RpcConnection.decodeParams(params)(using method.params.decoder).foldZIO(
          error => ZIO.logWarning(s"ignoring invalid ${method.name} notification: ${error.message}"),
          handle,
        ),
    )

/**
 * Dispatches by method name, sending `_`-prefixed extension methods to the extension handlers. As for every ACP method,
 * absent params read as `{}`.
 */
final case class Routes(
  requests: Seq[Route],
  notifications: Seq[NotificationRoute],
  extRequest: (String, Json) => IO[RpcError, Json],
  extNotification: (String, Json) => UIO[Unit],
) extends RpcHandler:
  private val requestsByName = requests.map(r => r.method -> r).toMap
  private val notificationsByName = notifications.map(n => n.method -> n).toMap

  def request(id: RequestId, method: String, params: Option[Json]): IO[RpcError, Json] =
    requestsByName.get(method) match
      case Some(route)                          => route.handle(id, params)
      case None if Methods.isExtension(method) => extRequest(method, params.fold(Json.Obj())(identity))
      case None                                 => ZIO.fail(RpcError.methodNotFound(method))

  def notification(method: String, params: Option[Json]): UIO[Unit] =
    notificationsByName.get(method) match
      case Some(route)                          => route.handle(params)
      case None if Methods.isExtension(method) => extNotification(method, params.fold(Json.Obj())(identity))
      case None                                 => ZIO.logDebug(s"ignoring unknown notification $method")
