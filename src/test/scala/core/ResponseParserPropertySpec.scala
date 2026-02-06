package core

import zio.*
import zio.json.*
import zio.test.*

import models.{ GeminiResponse, ParseError }

/** Property-based tests for ResponseParser.
  *
  * Tests extraction and parsing invariants with generated inputs.
  */
object ResponseParserPropertySpec extends ZIOSpecDefault:

  private val parser = ResponseParser.live

  def spec: Spec[Any, Any] = suite("ResponseParserPropertySpec")(
    suite("extractJson - code block wrapping")(
      test("extracts any valid JSON from json code block") {
        check(Gen.alphaNumericStringBounded(1, 20), Gen.int) { (name, count) =>
          val jsonContent = s"""{"name":"$name","count":$count}"""
          val response    = GeminiResponse(s"Result:\n```json\n$jsonContent\n```\nDone.", 0)
          for json <- ResponseParser.extractJson(response).provide(parser)
          yield assertTrue(json == jsonContent)
        }
      },
      test("extracts JSON from generic code block when no json block exists") {
        check(Gen.alphaNumericStringBounded(1, 15)) { value =>
          val jsonContent = s"""{"value":"$value"}"""
          val response    = GeminiResponse(s"```\n$jsonContent\n```", 0)
          for json <- ResponseParser.extractJson(response).provide(parser)
          yield assertTrue(json == jsonContent)
        }
      },
      test("prefers json-tagged block over generic block") {
        check(Gen.alphaNumericStringBounded(1, 10)) { value =>
          val generic  = """{"generic":"data"}"""
          val jsonData = s"""{"preferred":"$value"}"""
          val response = GeminiResponse(s"```\n$generic\n```\n```json\n$jsonData\n```", 0)
          for json <- ResponseParser.extractJson(response).provide(parser)
          yield assertTrue(json == jsonData)
        }
      },
      test("extracts inline JSON objects") {
        check(Gen.alphaNumericStringBounded(1, 15)) { key =>
          val jsonContent = s"""{"$key":42}"""
          val response    = GeminiResponse(s"Result => $jsonContent end", 0)
          for json <- ResponseParser.extractJson(response).provide(parser)
          yield assertTrue(json == jsonContent)
        }
      },
      test("extracts inline JSON arrays") {
        val response = GeminiResponse("Result: [1,2,3] end", 0)
        for json <- ResponseParser.extractJson(response).provide(parser)
        yield assertTrue(json == "[1,2,3]")
      },
    ),
    suite("parse - typed parsing")(
      test("parses valid JSON into case class") {
        check(Gen.alphaNumericStringBounded(1, 20), Gen.int) { (name, count) =>
          val json     = s"""{"name":"$name","count":$count}"""
          val response = GeminiResponse(s"```json\n$json\n```", 0)
          for result <- ResponseParser.parse[TestPayload](response).provide(parser)
          yield assertTrue(
            result.name == name,
            result.count == count,
          )
        }
      },
      test("InvalidJson for syntactically broken JSON") {
        check(Gen.alphaNumericStringBounded(1, 20)) { garbage =>
          val response = GeminiResponse(s"```json\n{not-valid: $garbage}\n```", 0)
          for result <- ResponseParser.parse[TestPayload](response).provide(parser).either
          yield assertTrue(result.left.exists {
            case ParseError.InvalidJson(_, _) => true
            case _                            => false
          })
        }
      },
      test("SchemaMismatch for valid JSON with wrong shape") {
        check(Gen.alphaNumericStringBounded(1, 20)) { value =>
          val response = GeminiResponse(s"""```json\n{"wrong_field":"$value"}\n```""", 0)
          for result <- ResponseParser.parse[TestPayload](response).provide(parser).either
          yield assertTrue(result.left.exists {
            case ParseError.SchemaMismatch(_, _) => true
            case _                               => false
          })
        }
      },
      test("NoJsonFound for empty response") {
        val response = GeminiResponse("", 0)
        for result <- ResponseParser.extractJson(response).provide(parser).either
        yield assertTrue(result.left.exists {
          case ParseError.NoJsonFound(_) => true
          case _                         => false
        })
      },
    ),
    suite("extractJson - edge cases")(
      test("handles response with only whitespace returns NoJsonFound") {
        val response = GeminiResponse("   \n\n  ", 0)
        for result <- ResponseParser.extractJson(response).provide(parser).either
        yield assertTrue(result.left.exists {
          case ParseError.NoJsonFound(_) => true
          case _                         => false
        })
      },
      test("handles nested JSON in code block") {
        val nested   = """{"outer":{"inner":{"deep":"value"}}}"""
        val response = GeminiResponse(s"```json\n$nested\n```", 0)
        for json <- ResponseParser.extractJson(response).provide(parser)
        yield assertTrue(json == nested)
      },
      test("handles JSON with arrays in code block") {
        val withArrays = """{"items":[1,2,3],"names":["a","b"]}"""
        val response   = GeminiResponse(s"```json\n$withArrays\n```", 0)
        for json <- ResponseParser.extractJson(response).provide(parser)
        yield assertTrue(json == withArrays)
      },
      test("handles multiple json blocks - takes first") {
        val first    = """{"first":true}"""
        val second   = """{"second":true}"""
        val response = GeminiResponse(s"```json\n$first\n```\n```json\n$second\n```", 0)
        for json <- ResponseParser.extractJson(response).provide(parser)
        yield assertTrue(json == first)
      },
    ),
  )
