package com.jamesward.zioacp.schema

import zio.json.*
import zio.json.ast.Json

import scala.reflect.TypeTest

/** Building blocks for the parts of the ACP wire format that codec derivation can't express. */
private[zioacp] object JsonSupport:

  /** The `_meta` extension object carried by most protocol types. */
  type Meta = Option[Json.Obj]

  /**
   * An open string enum: the known wire values decode to their cases and any other string is kept, through `unknown`,
   * so that values from newer protocol revisions survive a round trip.
   */
  def openEnum[A](known: Seq[A])(wire: A => String, unknown: String => A): JsonCodec[A] =
    val byWire = known.map(a => wire(a) -> a).toMap
    JsonCodec(
      JsonEncoder.string.contramap(wire),
      JsonDecoder.string.map(s => byWire.get(s).fold(unknown(s))(identity)),
    )

  /** A closed string enum: unknown wire values are a decoding failure. */
  def closedEnum[A](known: Seq[A])(wire: A => String): JsonCodec[A] =
    val byWire = known.map(a => wire(a) -> a).toMap
    JsonCodec(
      JsonEncoder.string.contramap(wire),
      JsonDecoder.string.mapOrFail(s => byWire.get(s).toRight(s"unknown value: $s")),
    )

  /** Encodes `b` as a JSON object. */
  def toObj[B](b: B)(using encoder: JsonEncoder[B]): Json.Obj =
    encoder.toJsonAST(b) match
      case Right(o: Json.Obj) => o
      // only reachable with a hand-written encoder that doesn't produce an object, which is a programming error
      case other => throw IllegalStateException(s"expected a JSON object encoding, got $other")

  /** Decodes `json` as `B`. */
  def fromJson[B](json: Json)(using decoder: JsonDecoder[B]): Either[String, B] =
    decoder.fromJsonAST(json)

  def withField(obj: Json.Obj, name: String, value: Json): Json.Obj =
    Json.Obj((name -> value) +: obj.fields.filterNot(_._1 == name))

  def stringField(obj: Json.Obj, name: String): Option[String] =
    obj.get(name).collect:
      case Json.Str(s) => s

  /** One case of a discriminated union, encoded with `tag` in the discriminator field. */
  final case class Variant[A](tag: String, encode: A => Option[Json.Obj], decode: Json.Obj => Either[String, A])

  object Variant:
    def apply[A, B <: A](tag: String)(using codec: JsonCodec[B], tt: TypeTest[A, B]): Variant[A] =
      Variant[A](
        tag,
        {
          case b: B => Some(toObj(b)(using codec.encoder))
          case _    => None
        },
        obj => fromJson[B](obj)(using codec.decoder),
      )

  /**
   * A union discriminated by the string property `field`. Objects without a known tag go to `fallback`, which can model
   * a variant without a tag (for example the stdio `McpServer`) or keep a value from a newer protocol revision.
   */
  def tagged[A](field: String, variants: Variant[A]*)(
    fallback: (Option[String], Json.Obj) => Either[String, A],
    encodeFallback: A => Option[Json.Obj],
  ): JsonCodec[A] =
    val byTag = variants.map(v => v.tag -> v).toMap
    val encoder = Json.Obj.encoder.contramap[A]: a =>
      variants.view
        .flatMap(v => v.encode(a).map(withField(_, field, Json.Str(v.tag))))
        .headOption
        .orElse(encodeFallback(a))
        .fold(throw IllegalStateException(s"no variant encodes $a"))(identity) // a missing case is a programming error
    val decoder = Json.Obj.decoder.mapOrFail: obj =>
      stringField(obj, field) match
        case Some(tag) =>
          byTag.get(tag) match
            case Some(variant) => variant.decode(obj)
            case None          => fallback(Some(tag), obj)
        case None => fallback(None, obj)
    JsonCodec(encoder, decoder)

  def noFallback[A](field: String)(tag: Option[String], obj: Json.Obj): Either[String, A] =
    tag match
      case Some(t) => Left(s"unknown $field: $t")
      case None    => Left(s"missing $field")

  val noFallbackEncoding: Any => Option[Json.Obj] = _ => None
