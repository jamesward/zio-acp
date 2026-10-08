package com.jamesward.zioacp.schema

import zio.json.*
import zio.json.ast.Json

/** Generates a distinct opaque string identifier type with its codec and equality. */
trait StringId:
  opaque type Id = String

  def apply(value: String): Id = value

  extension (id: Id) def value: String = id

  given JsonCodec[Id]      = JsonCodec.string
  given JsonFieldEncoder[Id] = JsonFieldEncoder.string
  given JsonFieldDecoder[Id] = JsonFieldDecoder.string
  given CanEqual[Id, Id]   = CanEqual.derived

object SessionId extends StringId
type SessionId = SessionId.Id

object ToolCallId extends StringId
type ToolCallId = ToolCallId.Id

object TerminalId extends StringId
type TerminalId = TerminalId.Id

object SessionModeId extends StringId
type SessionModeId = SessionModeId.Id

object SessionConfigId extends StringId
type SessionConfigId = SessionConfigId.Id

object SessionConfigValueId extends StringId
type SessionConfigValueId = SessionConfigValueId.Id

object SessionConfigGroupId extends StringId
type SessionConfigGroupId = SessionConfigGroupId.Id

object PermissionOptionId extends StringId
type PermissionOptionId = PermissionOptionId.Id

object AuthMethodId extends StringId
type AuthMethodId = AuthMethodId.Id

object MessageId extends StringId
type MessageId = MessageId.Id

object ElicitationId extends StringId
type ElicitationId = ElicitationId.Id

/** The ACP protocol version: a single integer, incremented only for breaking changes. */
opaque type ProtocolVersion = Int

object ProtocolVersion:
  /** The protocol version this library implements. */
  val V1: ProtocolVersion = 1

  def apply(value: Int): Either[String, ProtocolVersion] =
    if value >= 0 && value <= 65535 then Right(value) else Left(s"protocol version out of range: $value")

  extension (v: ProtocolVersion) def value: Int = v

  given JsonCodec[ProtocolVersion] = JsonCodec.int.transformOrFail(apply, identity)
  given CanEqual[ProtocolVersion, ProtocolVersion] = CanEqual.derived
  given Ordering[ProtocolVersion] = Ordering.Int

/** A JSON-RPC request id. */
enum RequestId derives CanEqual:
  case Null
  case Number(value: Long)
  case Str(value: String)

object RequestId:
  given JsonCodec[RequestId] =
    val encoder = Json.encoder.contramap[RequestId]:
      case RequestId.Null          => Json.Null
      case RequestId.Number(value) => Json.Num(value)
      case RequestId.Str(value)    => Json.Str(value)
    val decoder = Json.decoder.mapOrFail(fromJson)
    JsonCodec(encoder, decoder)

  def fromJson(json: Json): Either[String, RequestId] =
    json match
      case Json.Null      => Right(RequestId.Null)
      case Json.Str(s)    => Right(RequestId.Str(s))
      case n: Json.Num    =>
        try Right(RequestId.Number(n.value.longValueExact))
        catch case _: ArithmeticException => Left(s"request id is not an integer: ${n.value}")
      case other          => Left(s"invalid request id: $other")

  extension (id: RequestId)
    def asJson: Json =
      id match
        case RequestId.Null          => Json.Null
        case RequestId.Number(value) => Json.Num(value)
        case RequestId.Str(value)    => Json.Str(value)
