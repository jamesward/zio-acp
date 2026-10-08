package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*
import zio.json.ast.Json

/** A semantic hint for how clients present a config option; unknown categories are kept as `Other`. */
enum SessionConfigOptionCategory derives CanEqual:
  case Mode, Model, ModelConfig, ThoughtLevel
  case Other(value: String)

object SessionConfigOptionCategory:
  given JsonCodec[SessionConfigOptionCategory] = openEnum(Seq(Mode, Model, ModelConfig, ThoughtLevel))(
    {
      case Mode         => "mode"
      case Model        => "model"
      case ModelConfig  => "model_config"
      case ThoughtLevel => "thought_level"
      case Other(value) => value
    },
    Other(_),
  )

final case class SessionConfigSelectOption(
  value: SessionConfigValueId,
  name: String,
  description: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class SessionConfigSelectGroup(
  group: SessionConfigGroupId,
  name: String,
  options: List[SessionConfigSelectOption],
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** The values of a select option: a flat list, or a list of groups. */
enum SessionConfigSelectOptions derives CanEqual:
  case Ungrouped(options: List[SessionConfigSelectOption])
  case Grouped(groups: List[SessionConfigSelectGroup])

  /** Every selectable option, in order. */
  def all: List[SessionConfigSelectOption] =
    this match
      case Ungrouped(options) => options
      case Grouped(groups)    => groups.flatMap(_.options)

object SessionConfigSelectOptions:
  given JsonCodec[SessionConfigSelectOptions] =
    val encoder = Json.encoder.contramap[SessionConfigSelectOptions]:
      case Ungrouped(options) => Json.Arr(options.map(toObj(_))*)
      case Grouped(groups)    => Json.Arr(groups.map(toObj(_))*)
    val decoder = Json.Arr.decoder.mapOrFail: arr =>
      // an element with "group" marks the grouped form
      val grouped = arr.elements.headOption.exists:
        case o: Json.Obj => o.get("group").isDefined
        case _           => false
      if grouped then fromJson[List[SessionConfigSelectGroup]](arr).map(Grouped(_))
      else fromJson[List[SessionConfigSelectOption]](arr).map(Ungrouped(_))
    JsonCodec(encoder, decoder)

/** A session configuration option and its current value. */
enum SessionConfigOption derives CanEqual:
  case Select(
    id: SessionConfigId,
    name: String,
    currentValue: SessionConfigValueId,
    options: SessionConfigSelectOptions,
    description: Option[String] = None,
    category: Option[SessionConfigOptionCategory] = None,
    @jsonField("_meta") meta: Meta = None,
  )
  case Boolean(
    id: SessionConfigId,
    name: String,
    currentValue: scala.Boolean,
    description: Option[String] = None,
    category: Option[SessionConfigOptionCategory] = None,
    @jsonField("_meta") meta: Meta = None,
  )

  def id: SessionConfigId

object SessionConfigOption:
  private given JsonCodec[Select]  = DeriveJsonCodec.gen
  private given JsonCodec[Boolean] = DeriveJsonCodec.gen

  given JsonCodec[SessionConfigOption] = tagged[SessionConfigOption](
    "type",
    Variant[SessionConfigOption, Select]("select"),
    Variant[SessionConfigOption, Boolean]("boolean"),
  )(noFallback("type"), noFallbackEncoding)

/** The new value in a `session/set_config_option` request. */
enum SessionConfigValue derives CanEqual:
  case ValueId(value: SessionConfigValueId)
  case Boolean(value: scala.Boolean)

final case class SetSessionConfigOptionRequest(
  sessionId: SessionId,
  configId: SessionConfigId,
  value: SessionConfigValue,
  @jsonField("_meta") meta: Meta = None,
) derives CanEqual

object SetSessionConfigOptionRequest:
  private final case class Fields(sessionId: SessionId, configId: SessionConfigId, @jsonField("_meta") meta: Meta = None)
      derives JsonCodec, CanEqual

  given JsonCodec[SetSessionConfigOptionRequest] =
    val encoder = Json.Obj.encoder.contramap[SetSessionConfigOptionRequest]: req =>
      val fields = toObj(Fields(req.sessionId, req.configId, req.meta))
      req.value match
        case SessionConfigValue.ValueId(value) => withField(fields, "value", Json.Str(value.value))
        case SessionConfigValue.Boolean(value) =>
          withField(withField(fields, "value", Json.Bool(value)), "type", Json.Str("boolean"))
    val decoder = Json.Obj.decoder.mapOrFail: obj =>
      for
        fields <- fromJson[Fields](obj)
        value <- (stringField(obj, "type"), obj.get("value")) match
                   case (Some("boolean"), Some(Json.Bool(b))) => Right(SessionConfigValue.Boolean(b))
                   case (None, Some(Json.Str(s)))             => Right(SessionConfigValue.ValueId(SessionConfigValueId(s)))
                   case (t, v)                                => Left(s"invalid config value: type=$t value=$v")
      yield SetSessionConfigOptionRequest(fields.sessionId, fields.configId, value, fields.meta)
    JsonCodec(encoder, decoder)

final case class SetSessionConfigOptionResponse(
  configOptions: List[SessionConfigOption],
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual
