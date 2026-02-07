package core

import zio.*
import zio.test.*

/** Tests for ObservableLogger - simple delegation to Logger with OTEL trace integration */
object ObservableLoggerSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("ObservableLoggerSpec")(
    suite("logging methods")(
      test("trace method works") {
        for
          _ <- ObservableLogger.trace("trace message").provide(ObservableLogger.live)
        yield assertTrue(true)
      },
      test("debug method works") {
        for
          _ <- ObservableLogger.debug("debug message").provide(ObservableLogger.live)
        yield assertTrue(true)
      },
      test("info method works") {
        for
          _ <- ObservableLogger.info("info message").provide(ObservableLogger.live)
        yield assertTrue(true)
      },
      test("warn method works") {
        for
          _ <- ObservableLogger.warn("warn message").provide(ObservableLogger.live)
        yield assertTrue(true)
      },
      test("error method works") {
        for
          _ <- ObservableLogger.error("error message").provide(ObservableLogger.live)
        yield assertTrue(true)
      },
      test("error with cause works") {
        for
          _ <- ObservableLogger
                 .error("error message", new RuntimeException("test cause"))
                 .provide(ObservableLogger.live)
        yield assertTrue(true)
      },
    ),
    suite("service access")(
      test("can access via ZIO.service") {
        (for
          logger <- ZIO.service[ObservableLogger]
          _      <- logger.info("test message")
        yield assertTrue(true))
          .provide(ObservableLogger.live)
      }
    ),
  )
