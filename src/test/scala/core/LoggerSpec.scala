package core

import zio.*
import zio.test.*

object LoggerSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("LoggerSpec")(
    suite("Logger static methods")(
      test("logs trace message") {
        Logger.trace("Test trace message").as(assertTrue(true))
      },
      test("logs debug message") {
        Logger.debug("Test debug message").as(assertTrue(true))
      },
      test("logs info message") {
        Logger.info("Test info message").as(assertTrue(true))
      },
      test("logs warn message") {
        Logger.warn("Test warn message").as(assertTrue(true))
      },
      test("logs error message") {
        Logger.error("Test error message").as(assertTrue(true))
      },
      test("logs error with exception") {
        val exception = new RuntimeException("Test exception")
        Logger.error("Test error with exception", exception).as(assertTrue(true))
      },
    ),
    suite("Logger service layer")(
      test("Logger.live layer works") {
        (for
          logger <- ZIO.service[Logger]
          _      <- logger.info("Test message via service")
        yield assertTrue(true))
          .provide(Logger.live)
      }
    ),
  )
