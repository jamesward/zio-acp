package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*
import zio.json.ast.Json

/** A JSON-RPC error code: the ones ACP defines, or any other integer. */
enum ErrorCode derives CanEqual:
  case ParseError, InvalidRequest, MethodNotFound, InvalidParams, InternalError, RequestCancelled, AuthRequired,
    ResourceNotFound
  case Other(code: Int)

  def value: Int =
    this match
      case ParseError       => -32700
      case InvalidRequest   => -32600
      case MethodNotFound   => -32601
      case InvalidParams    => -32602
      case InternalError    => -32603
      case RequestCancelled => -32800
      case AuthRequired     => -32000
      case ResourceNotFound => -32002
      case Other(code)      => code

  def defaultMessage: String =
    this match
      case ParseError       => "Parse error"
      case InvalidRequest   => "Invalid request"
      case MethodNotFound   => "Method not found"
      case InvalidParams    => "Invalid params"
      case InternalError    => "Internal error"
      case RequestCancelled => "Request cancelled"
      case AuthRequired     => "Authentication required"
      case ResourceNotFound => "Resource not found"
      case Other(code)      => s"Error $code"

object ErrorCode:
  private val known =
    Seq(ParseError, InvalidRequest, MethodNotFound, InvalidParams, InternalError, RequestCancelled, AuthRequired, ResourceNotFound)
      .map(c => c.value -> c)
      .toMap

  def apply(value: Int): ErrorCode = known.get(value).fold(Other(value))(identity)

  given JsonCodec[ErrorCode] = JsonCodec.int.transform(apply, _.value)

/** A JSON-RPC error object, returned in place of a result. */
final case class RpcError(code: ErrorCode, message: String, data: Option[Json] = None) derives CanEqual

object RpcError:
  def apply(code: ErrorCode): RpcError = RpcError(code, code.defaultMessage)

  def parseError(detail: String): RpcError = RpcError(ErrorCode.ParseError, s"Parse error: $detail")
  def invalidRequest(detail: String): RpcError = RpcError(ErrorCode.InvalidRequest, s"Invalid request: $detail")
  def methodNotFound(method: String): RpcError = RpcError(ErrorCode.MethodNotFound, s"Method not found: $method")
  def invalidParams(detail: String): RpcError = RpcError(ErrorCode.InvalidParams, s"Invalid params: $detail")
  def internalError(detail: String): RpcError = RpcError(ErrorCode.InternalError, s"Internal error: $detail")
  val requestCancelled: RpcError = RpcError(ErrorCode.RequestCancelled)
  val authRequired: RpcError = RpcError(ErrorCode.AuthRequired)
  def resourceNotFound(detail: String): RpcError = RpcError(ErrorCode.ResourceNotFound, s"Resource not found: $detail")

  private final case class Wire(code: ErrorCode, message: Option[String] = None, data: Option[Json] = None)
      derives JsonCodec, CanEqual

  // a peer that leaves out the required message still gets its error through, with the code's standard message
  given JsonCodec[RpcError] = summon[JsonCodec[Wire]].transform(
    w => RpcError(w.code, w.message.fold(w.code.defaultMessage)(identity), w.data),
    e => Wire(e.code, Some(e.message), e.data),
  )
