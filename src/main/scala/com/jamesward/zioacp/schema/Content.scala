package com.jamesward.zioacp.schema

import com.jamesward.zioacp.schema.JsonSupport.*
import zio.json.*
import zio.json.ast.Json

/** Who a piece of content is intended for. Unknown roles from newer protocol revisions are kept as `Other`. */
enum Role derives CanEqual:
  case Assistant, User
  case Other(value: String)

object Role:
  given JsonCodec[Role] = openEnum(Seq(Assistant, User))(
    {
      case Assistant    => "assistant"
      case User         => "user"
      case Other(value) => value
    },
    Other(_),
  )

/** Optional annotations the client uses to decide how content is used or displayed. */
final case class Annotations(
  audience: Option[List[Role]] = None,
  lastModified: Option[String] = None,
  priority: Option[Double] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class TextResourceContents(
  uri: String,
  text: String,
  mimeType: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

final case class BlobResourceContents(
  uri: String,
  blob: String,
  mimeType: Option[String] = None,
  @jsonField("_meta") meta: Meta = None,
) derives JsonCodec, CanEqual

/** The contents of an embedded resource: text or binary (base64). */
enum EmbeddedResourceResource derives CanEqual:
  case Text(contents: TextResourceContents)
  case Blob(contents: BlobResourceContents)

object EmbeddedResourceResource:
  given JsonCodec[EmbeddedResourceResource] =
    val encoder = Json.Obj.encoder.contramap[EmbeddedResourceResource]:
      case Text(contents) => toObj(contents)
      case Blob(contents) => toObj(contents)
    val decoder = Json.Obj.decoder.mapOrFail: obj =>
      if obj.get("blob").isDefined then fromJson[BlobResourceContents](obj).map(Blob(_))
      else fromJson[TextResourceContents](obj).map(Text(_))
    JsonCodec(encoder, decoder)

/** Content in prompts, messages and tool call results; the same structure as MCP content blocks. */
enum ContentBlock derives CanEqual:
  case Text(text: String, annotations: Option[Annotations] = None, @jsonField("_meta") meta: Meta = None)
  case Image(
    data: String,
    mimeType: String,
    uri: Option[String] = None,
    annotations: Option[Annotations] = None,
    @jsonField("_meta") meta: Meta = None,
  )
  case Audio(data: String, mimeType: String, annotations: Option[Annotations] = None, @jsonField("_meta") meta: Meta = None)
  case ResourceLink(
    name: String,
    uri: String,
    title: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    size: Option[Long] = None,
    annotations: Option[Annotations] = None,
    @jsonField("_meta") meta: Meta = None,
  )
  case Resource(resource: EmbeddedResourceResource, annotations: Option[Annotations] = None, @jsonField("_meta") meta: Meta = None)

object ContentBlock:
  def text(text: String): ContentBlock = Text(text)

  private given JsonCodec[Text]         = DeriveJsonCodec.gen
  private given JsonCodec[Image]        = DeriveJsonCodec.gen
  private given JsonCodec[Audio]        = DeriveJsonCodec.gen
  private given JsonCodec[ResourceLink] = DeriveJsonCodec.gen
  private given JsonCodec[Resource]     = DeriveJsonCodec.gen

  given JsonCodec[ContentBlock] = tagged[ContentBlock](
    "type",
    Variant[ContentBlock, Text]("text"),
    Variant[ContentBlock, Image]("image"),
    Variant[ContentBlock, Audio]("audio"),
    Variant[ContentBlock, ResourceLink]("resource_link"),
    Variant[ContentBlock, Resource]("resource"),
  )(noFallback("type"), noFallbackEncoding)
