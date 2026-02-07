package core

import zio.*
import zio.test.*

/** Tests for simplified Logger delegating to ZIO logging */
object LoggerPropertySpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("LoggerPropertySpec")(
    suite("Logger static methods - property-based")(
      test("trace method completes successfully") {
        Logger.trace("test trace message").as(assertTrue(true))
      },
      test("debug method completes successfully") {
        Logger.debug("test debug message").as(assertTrue(true))
      },
      test("info method completes successfully") {
        Logger.info("test info message").as(assertTrue(true))
      },
      test("warn method completes successfully") {
        Logger.warn("test warn message").as(assertTrue(true))
      },
      test("error method completes successfully") {
        Logger.error("test error message").as(assertTrue(true))
      },
      test("error with exception completes successfully") {
        Logger.error("test error", new RuntimeException("test")).as(assertTrue(true))
      },
    )
  )
