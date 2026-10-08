package com.jamesward.zioacp

import com.networknt.schema.{InputFormat, SchemaRegistry, SpecificationVersion}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scala.jdk.CollectionConverters.*

/**
 * Validates the wire format against the official ACP v1 JSON Schema (src/test/resources/acp-schema-v1.json, from
 * agentclientprotocol/agent-client-protocol schema/v1/schema.json): every sample must validate, and every property it
 * writes must be one the schema declares, which the schema alone doesn't check because it allows extra properties.
 */
object SchemaSpec extends ZIOSpecDefault:

  private given CanEqual[Json, Json] = CanEqual.derived

  private val schemaText =
    scala.io.Source.fromResource("acp-schema-v1.json").mkString

  private val defs: Json.Obj =
    schemaText.fromJson[Json.Obj].toOption.flatMap(_.get("$defs")).collect { case o: Json.Obj => o }.fold(Json.Obj())(identity)

  private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

  private def validator(definition: String) =
    val wrapper = Json.Obj(
      "$schema" -> Json.Str("https://json-schema.org/draft/2020-12/schema"),
      "$ref" -> Json.Str(s"#/$$defs/$definition"),
      "$defs" -> defs,
    )
    registry.getSchema(wrapper.toJson, InputFormat.JSON)

  def violations(definition: String, json: Json): List[String] =
    validator(definition).validate(json.toJson, InputFormat.JSON).asScala.toList.map(_.toString)

  private def resolve(schema: Json): Json =
    schema match
      case o: Json.Obj =>
        o.get("$ref") match
          case Some(Json.Str(ref)) => defs.get(ref.stripPrefix("#/$defs/")).fold(schema)(resolve)
          case _                   => o
      case other => other

  /** The schema and every subschema it combines with allOf, anyOf or oneOf, resolved. */
  private def branches(schema: Json): List[Json.Obj] =
    resolve(schema) match
      case o: Json.Obj =>
        val nested = List("allOf", "anyOf", "oneOf").flatMap(k => o.get(k).toList).flatMap:
          case Json.Arr(items) => items.toList.flatMap(branches)
          case _               => Nil
        o :: nested
      case _ => Nil

  /** Paths of the properties in `json` that `schema` doesn't declare. */
  def undeclared(json: Json, schema: Json, path: String): List[String] =
    val bs = branches(schema)
    json match
      case Json.Obj(fields) =>
        val declared = bs.flatMap(_.get("properties").toList).collect { case o: Json.Obj => o.fields.toList }.flatten
        val additional = bs.flatMap(_.get("additionalProperties").toList).collect { case o: Json.Obj => o }
        // free-form values (`{}` schemas, such as rawInput) and `additionalProperties: true` accept any property
        val open = bs.exists(_.get("additionalProperties").contains(Json.Bool(true))) ||
          !bs.exists(b => b.get("properties").isDefined || b.get("additionalProperties").isDefined)
        fields.toList.flatMap: (name, value) =>
          val propertySchemas = declared.collect { case (n, s) if n == name => s } ++ additional
          if propertySchemas.isEmpty then if open then Nil else List(s"$path.$name")
          // a property is fine if one of the schemas that declares it accepts all of its contents
          else propertySchemas.map(undeclared(value, _, s"$path.$name")).minBy(_.size)
      case Json.Arr(items) =>
        val itemSchemas = bs.flatMap(_.get("items").toList)
        items.toList.zipWithIndex.flatMap: (item, i) =>
          if itemSchemas.isEmpty then Nil else itemSchemas.map(undeclared(item, _, s"$path[$i]")).minBy(_.size)
      case _ => Nil

  def spec = suite("SchemaSpec")(
    Samples.all.zipWithIndex.map: (sample, i) =>
      test(s"${sample.definition} #$i matches the v1 schema"):
        val errors = violations(sample.definition, sample.json)
        val extra = undeclared(sample.json, Json.Obj("$ref" -> Json.Str(s"#/$$defs/${sample.definition}")), sample.definition)
        assertTrue(errors.isEmpty, extra.isEmpty)
    *,
  )
