package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*
import zio.json.ast.Json

import scala.collection.immutable.ListMap

/** What an elicitation is tied to: a session (optionally a tool call), or a JSON-RPC request outside a session. */
enum ElicitationScope derives CanEqual:
  case Session(sessionId: SessionId, toolCallId: Option[ToolCallId] = None)
  case Request(requestId: RequestId)

object ElicitationScope:
  private final case class SessionFields(sessionId: SessionId, toolCallId: Option[ToolCallId] = None) derives JsonCodec, CanEqual
  private final case class RequestFields(requestId: RequestId) derives JsonCodec, CanEqual

  /** The scope's properties, which are flattened into the enclosing object. */
  def fields(scope: ElicitationScope): Json.Obj =
    scope match
      case Session(sessionId, toolCallId) => toObj(SessionFields(sessionId, toolCallId))
      case Request(requestId)             => toObj(RequestFields(requestId))

  def from(obj: Json.Obj): Either[String, ElicitationScope] =
    if obj.get("sessionId").isDefined then fromJson[SessionFields](obj).map(s => Session(s.sessionId, s.toolCallId))
    else if obj.get("requestId").isDefined then fromJson[RequestFields](obj).map(r => Request(r.requestId))
    else Left("elicitation needs a sessionId or a requestId")

enum StringFormat derives CanEqual:
  case Email, Uri, Date, DateTime
  case Unknown(value: String)

object StringFormat:
  given JsonCodec[StringFormat] = openEnum(Seq(Email, Uri, Date, DateTime))(
    {
      case Email          => "email"
      case Uri            => "uri"
      case Date           => "date"
      case DateTime       => "date-time"
      case Unknown(value) => value
    },
    Unknown(_),
  )

final case class EnumOption(
  const: String,
  title: String,
  description: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** The items of a multi-select property. */
enum MultiSelectItems derives CanEqual:
  case Strings(`enum`: List[String], @jsonField("_meta") meta: Meta = None)
  case Titled(anyOf: List[EnumOption], @jsonField("_meta") meta: Meta = None)
  case Other(`type`: String, raw: Json.Obj)

object MultiSelectItems:
  private given JsonCodec[Strings] = DeriveJsonCodec.gen
  private given JsonCodec[Titled]  = DeriveJsonCodec.gen

  given JsonCodec[MultiSelectItems] = tagged[MultiSelectItems]("type", Variant[MultiSelectItems, Strings]("string"))(
    {
      case (Some(t), obj) => Right(Other(t, obj))
      case (None, obj)    => fromJson[Titled](obj)
    },
    {
      case t: Titled      => Some(toObj(t))
      case Other(t, raw)  => Some(withField(raw, "type", Json.Str(t)))
      case _              => None
    },
  )

/** A property of an elicitation form: a restricted subset of JSON Schema. */
enum ElicitationPropertySchema derives CanEqual:
  case StringProperty(
    title: Option[String] = None,
    description: Option[String] = None,
    minLength: Option[Long] = None,
    maxLength: Option[Long] = None,
    pattern: Option[String] = None,
    format: Option[StringFormat] = None,
    default: Option[String] = None,
    `enum`: Option[List[String]] = None,
    oneOf: Option[List[EnumOption]] = None,
    @jsonField("_meta") meta: Meta = None,
  )
  case NumberProperty(
    title: Option[String] = None,
    description: Option[String] = None,
    minimum: Option[Double] = None,
    maximum: Option[Double] = None,
    default: Option[Double] = None,
    @jsonField("_meta") meta: Meta = None,
  )
  case IntegerProperty(
    title: Option[String] = None,
    description: Option[String] = None,
    minimum: Option[Long] = None,
    maximum: Option[Long] = None,
    default: Option[Long] = None,
    @jsonField("_meta") meta: Meta = None,
  )
  case BooleanProperty(
    title: Option[String] = None,
    description: Option[String] = None,
    default: Option[Boolean] = None,
    @jsonField("_meta") meta: Meta = None,
  )
  case MultiSelectProperty(
    items: MultiSelectItems,
    title: Option[String] = None,
    description: Option[String] = None,
    minItems: Option[Long] = None,
    maxItems: Option[Long] = None,
    default: Option[List[String]] = None,
    @jsonField("_meta") meta: Meta = None,
  )

  /** A property type from a newer protocol revision or an extension, kept as received. */
  case Other(`type`: String, raw: Json.Obj)

object ElicitationPropertySchema:
  private given JsonCodec[StringProperty]      = DeriveJsonCodec.gen
  private given JsonCodec[NumberProperty]      = DeriveJsonCodec.gen
  private given JsonCodec[IntegerProperty]     = DeriveJsonCodec.gen
  private given JsonCodec[BooleanProperty]     = DeriveJsonCodec.gen
  private given JsonCodec[MultiSelectProperty] = DeriveJsonCodec.gen

  given JsonCodec[ElicitationPropertySchema] = tagged[ElicitationPropertySchema](
    "type",
    Variant[ElicitationPropertySchema, StringProperty]("string"),
    Variant[ElicitationPropertySchema, NumberProperty]("number"),
    Variant[ElicitationPropertySchema, IntegerProperty]("integer"),
    Variant[ElicitationPropertySchema, BooleanProperty]("boolean"),
    Variant[ElicitationPropertySchema, MultiSelectProperty]("array"),
  )(
    {
      case (Some(t), obj) => Right(Other(t, obj))
      case (None, _)      => Left("missing type")
    },
    {
      case Other(t, raw) => Some(withField(raw, "type", Json.Str(t)))
      case _             => None
    },
  )

/** The form an elicitation asks the user to fill in: a JSON Schema object with primitive-typed properties. */
final case class ElicitationSchema(
  properties: ListMap[String, ElicitationPropertySchema] = ListMap.empty,
  required: Option[List[String]] = None,
  title: Option[String] = None,
  description: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives CanEqual

object ElicitationSchema:
  private final case class Fields(
    properties: ListMap[String, ElicitationPropertySchema] = ListMap.empty,
    required: Option[List[String]] = None,
    title: Option[String] = None,
    description: Option[String] = None,
    @jsonField("_meta") meta: Meta = None,
  ) derives JsonCodec, CanEqual

  given JsonCodec[ElicitationSchema] =
    val encoder = Json.Obj.encoder.contramap[ElicitationSchema]: s =>
      withField(toObj(Fields(s.properties, s.required, s.title, s.description, s.meta)), "type", Json.Str("object"))
    val decoder = Json.Obj.decoder.mapOrFail: obj =>
      stringField(obj, "type") match
        case Some("object") | None =>
          fromJson[Fields](obj).map(f => ElicitationSchema(f.properties, f.required, f.title, f.description, f.meta))
        case Some(other) => Left(s"elicitation schema type must be object, got $other")
    JsonCodec(encoder, decoder)

/** `elicitation/create`: the agent asks the user for structured input. */
enum CreateElicitationRequest derives CanEqual:
  case Form(scope: ElicitationScope, message: String, requestedSchema: ElicitationSchema, meta: Meta = None)
  case Url(scope: ElicitationScope, message: String, elicitationId: ElicitationId, url: String, meta: Meta = None)

  /** A mode from a newer protocol revision or an extension, kept as received. */
  case Other(mode: String, scope: ElicitationScope, message: String, raw: Json.Obj)

object CreateElicitationRequest:
  private final case class FormFields(message: String, requestedSchema: ElicitationSchema, @jsonField("_meta") meta: Meta = None)
      derives JsonCodec, CanEqual
  private final case class UrlFields(
    message: String,
    elicitationId: ElicitationId,
    url: String,
    @jsonField("_meta") meta: Meta = None,
  ) derives JsonCodec, CanEqual
  private final case class MessageField(message: String) derives JsonCodec, CanEqual

  given JsonCodec[CreateElicitationRequest] =
    def merge(mode: String, scope: ElicitationScope, fields: Json.Obj): Json.Obj =
      withField(Json.Obj((fields.fields ++ ElicitationScope.fields(scope).fields)*), "mode", Json.Str(mode))
    val encoder = Json.Obj.encoder.contramap[CreateElicitationRequest]:
      case Form(scope, message, schema, meta)    => merge("form", scope, toObj(FormFields(message, schema, meta)))
      case Url(scope, message, id, url, meta)    => merge("url", scope, toObj(UrlFields(message, id, url, meta)))
      case Other(mode, scope, message, raw)      =>
        merge(mode, scope, withField(raw, "message", Json.Str(message)))
    val decoder = Json.Obj.decoder.mapOrFail: obj =>
      for
        scope <- ElicitationScope.from(obj)
        request <- stringField(obj, "mode") match
                     case Some("form") => fromJson[FormFields](obj).map(f => Form(scope, f.message, f.requestedSchema, f.meta))
                     case Some("url") =>
                       fromJson[UrlFields](obj).map(f => Url(scope, f.message, f.elicitationId, f.url, f.meta))
                     case Some(mode) => fromJson[MessageField](obj).map(m => Other(mode, scope, m.message, obj))
                     case None       => Left("missing mode")
      yield request
    JsonCodec(encoder, decoder)

/** A value the user entered in an elicitation form. */
enum ElicitationContentValue derives CanEqual:
  case Str(value: String)
  case Integer(value: Long)
  case Number(value: Double)
  case Bool(value: Boolean)
  case StringArray(values: List[String])

object ElicitationContentValue:
  given JsonCodec[ElicitationContentValue] =
    val encoder = Json.encoder.contramap[ElicitationContentValue]:
      case Str(value)          => Json.Str(value)
      case Integer(value)      => Json.Num(value)
      case Number(value)       => Json.Num(value)
      case Bool(value)         => Json.Bool(value)
      case StringArray(values) => Json.Arr(values.map(Json.Str(_))*)
    val decoder = Json.decoder.mapOrFail:
      case Json.Str(value)  => Right(Str(value))
      case Json.Bool(value) => Right(Bool(value))
      case n: Json.Num =>
        try Right(Integer(n.value.longValueExact))
        catch case _: ArithmeticException => Right(Number(n.value.doubleValue))
      case arr: Json.Arr => fromJson[List[String]](arr).map(StringArray(_))
      case other         => Left(s"invalid elicitation value: $other")
    JsonCodec(encoder, decoder)

/** The user's answer to an elicitation. */
enum CreateElicitationResponse derives CanEqual:
  case Accept(content: Option[ListMap[String, ElicitationContentValue]] = None, meta: Meta = None)
  case Decline(meta: Meta = None)
  case Cancel(meta: Meta = None)

  /** An action from a newer protocol revision or an extension, kept as received. */
  case Other(action: String, raw: Json.Obj)

object CreateElicitationResponse:
  private final case class AcceptFields(
    content: Option[ListMap[String, ElicitationContentValue]] = None,
    @jsonField("_meta") meta: Meta = None,
  ) derives JsonCodec, CanEqual

  given JsonCodec[CreateElicitationResponse] =
    def action(name: String, obj: Json.Obj) = withField(obj, "action", Json.Str(name))
    val encoder = Json.Obj.encoder.contramap[CreateElicitationResponse]:
      case Accept(content, meta) => action("accept", toObj(AcceptFields(content, meta)))
      case Decline(meta)         => action("decline", toObj(Marker(meta)))
      case Cancel(meta)          => action("cancel", toObj(Marker(meta)))
      case Other(name, raw)      => action(name, raw)
    val decoder = Json.Obj.decoder.mapOrFail: obj =>
      stringField(obj, "action") match
        case Some("accept")  => fromJson[AcceptFields](obj).map(a => Accept(a.content, a.meta))
        case Some("decline") => fromJson[Marker](obj).map(m => Decline(m.meta))
        case Some("cancel")  => fromJson[Marker](obj).map(m => Cancel(m.meta))
        case Some(other)     => Right(Other(other, obj))
        case None            => Left("missing action")
    JsonCodec(encoder, decoder)

/** `elicitation/complete`: the agent tells the client that a URL-mode elicitation finished. */
final case class CompleteElicitationNotification(elicitationId: ElicitationId, @jsonField("_meta") meta: Meta = None)
    derives JsonCodec, CanEqual
