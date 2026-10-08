package com.jamesward.zioacp.jsonrpc

import com.jamesward.zioacp.schema.{JsonSupport, RequestId, RpcError}
import zio.json.*
import zio.json.ast.Json

/** A JSON-RPC 2.0 message. */
enum Message:
  case Request(id: RequestId, method: String, params: Option[Json])
  case Notification(method: String, params: Option[Json])
  case Response(id: RequestId, result: Either[RpcError, Json])

/** What became of an incoming line. */
enum Parsed:
  case Valid(message: Message)

  /** Not a valid message: JSON-RPC requires this error response (with an id of null if the id was unreadable). */
  case Invalid(id: RequestId, error: RpcError)

  /** A malformed response, which JSON-RPC forbids answering. */
  case Ignored(reason: String)

object Message:
  private val version: (String, Json) = "jsonrpc" -> Json.Str("2.0")

  def toJson(message: Message): Json.Obj =
    message match
      case Request(id, method, params) =>
        Json.Obj(Seq(version: (String, Json), "id" -> id.asJson, "method" -> Json.Str(method)) ++ params.map("params" -> _)*)
      case Notification(method, params) =>
        Json.Obj(Seq(version, "method" -> Json.Str(method)) ++ params.map("params" -> _)*)
      case Response(id, Right(result)) =>
        Json.Obj(version: (String, Json), "id" -> id.asJson, "result" -> result)
      case Response(id, Left(error)) =>
        Json.Obj(version: (String, Json), "id" -> id.asJson, "error" -> JsonSupport.toObj(error))

  /** One line of the stdio transport: compact JSON, which never contains a raw newline. */
  def encode(message: Message): String = toJson(message).toJson

  def parse(line: String): Parsed =
    line.fromJson[Json] match
      case Left(error)          => Parsed.Invalid(RequestId.Null, RpcError.parseError(error))
      case Right(_: Json.Arr)   => Parsed.Invalid(RequestId.Null, RpcError.invalidRequest("batches are not supported"))
      case Right(obj: Json.Obj) => fromObj(obj)
      case Right(_)             => Parsed.Invalid(RequestId.Null, RpcError.invalidRequest("a message must be an object"))

  private def fromObj(obj: Json.Obj): Parsed =
    val id = obj.get("id").map(RequestId.fromJson)
    // the id to answer an invalid message with: its own when readable, else null
    val replyId = id.flatMap(_.toOption).fold(RequestId.Null)(identity)
    val params = obj.get("params")
    if !obj.get("jsonrpc").exists(_ == Json.Str("2.0")) then
      Parsed.Invalid(replyId, RpcError.invalidRequest("jsonrpc must be \"2.0\""))
    else
      obj.get("method") match
        case Some(Json.Str(method)) =>
          id match
            case None            => Parsed.Valid(Notification(method, params))
            case Some(Right(rid)) => Parsed.Valid(Request(rid, method, params))
            case Some(Left(err))  => Parsed.Invalid(RequestId.Null, RpcError.invalidRequest(err))
        case Some(_) => Parsed.Invalid(replyId, RpcError.invalidRequest("method must be a string"))
        case None =>
          (id, obj.get("result"), obj.get("error")) match
            case (Some(Right(rid)), Some(result), None) => Parsed.Valid(Response(rid, Right(result)))
            case (Some(Right(rid)), None, Some(error)) =>
              error.as[RpcError] match
                case Right(e)  => Parsed.Valid(Response(rid, Left(e)))
                case Left(err) => Parsed.Ignored(s"unreadable error in response to $rid: $err")
            case _ => Parsed.Ignored(s"not a request, notification or response: ${obj.toJson.take(200)}")

  private given CanEqual[Json, Json] = CanEqual.derived
