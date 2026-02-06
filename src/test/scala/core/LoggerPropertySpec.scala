package core

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*
import zio.test.TestAspect.*

/** Tests for Logger service accessors and file/combined logger implementations. */
object LoggerPropertySpec extends ZIOSpecDefault:

  private def withTempLogFile[A](test: Path => ZIO[Any, Any, A]): ZIO[Any, Any, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempFile("logger-test", ".log"))
    )(path => ZIO.attempt(Files.deleteIfExists(path)).ignore)(test)

  def spec: Spec[Any, Any] = suite("LoggerPropertySpec")(
    suite("Logger.service accessors")(
      test("service.info logs via Logger service") {
        withTempLogFile { logFile =>
          for
            _       <- Logger.service.info("service info test").provide(Logger.file(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("service info test"))
        }
      },
      test("service.debug logs via Logger service") {
        withTempLogFile { logFile =>
          for
            _       <- Logger.service.debug("service debug test").provide(Logger.file(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("service debug test"))
        }
      },
      test("service.trace logs via Logger service") {
        withTempLogFile { logFile =>
          for
            _       <- Logger.service.trace("service trace test").provide(Logger.file(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("service trace test"))
        }
      },
      test("service.warn logs via Logger service") {
        withTempLogFile { logFile =>
          for
            _       <- Logger.service.warn("service warn test").provide(Logger.file(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("service warn test"))
        }
      },
      test("service.error logs via Logger service") {
        withTempLogFile { logFile =>
          for
            _       <- Logger.service.error("service error test").provide(Logger.file(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("service error test"))
        }
      },
      test("service.error with cause logs via Logger service") {
        withTempLogFile { logFile =>
          val cause = new RuntimeException("service test exception")
          for
            _       <- Logger.service
                         .error("service error with cause", cause)
                         .provide(Logger.file(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("service error with cause"))
        }
      },
      test("service.withContext returns contextual logger") {
        withTempLogFile { logFile =>
          for
            logger  <- Logger.service.withContext("key", "value").provide(Logger.file(logFile.toString))
            _       <- logger.info("context logger test")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(
            content.contains("context logger test"),
            content.contains("\"key\":\"value\""),
          )
        }
      },
      test("service.withRunId returns run-scoped logger") {
        withTempLogFile { logFile =>
          for
            logger  <- Logger.service.withRunId("run-test-1").provide(Logger.file(logFile.toString))
            _       <- logger.info("run scoped test")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"runId\":\"run-test-1\""))
        }
      },
      test("service.withAgentName returns agent-scoped logger") {
        withTempLogFile { logFile =>
          for
            logger  <- Logger.service.withAgentName("TestAgent").provide(Logger.file(logFile.toString))
            _       <- logger.info("agent scoped test")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"agentName\":\"TestAgent\""))
        }
      },
      test("service.withStep returns step-scoped logger") {
        withTempLogFile { logFile =>
          for
            logger  <- Logger.service.withStep("Analysis").provide(Logger.file(logFile.toString))
            _       <- logger.info("step scoped test")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"step\":\"Analysis\""))
        }
      },
    ),
    suite("Logger.combined")(
      test("combined logger writes to file") {
        withTempLogFile { logFile =>
          for
            _       <- Logger.service.info("combined test").provide(Logger.combined(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("combined test"))
        }
      },
      test("combined logger supports context propagation") {
        withTempLogFile { logFile =>
          for
            logger  <- Logger.service.withRunId("combined-run").provide(Logger.combined(logFile.toString))
            _       <- logger.info("combined context test")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"runId\":\"combined-run\""))
        }
      },
      test("combined logger withAgentName propagates to both") {
        withTempLogFile { logFile =>
          for
            logger  <- Logger.service.withAgentName("CombinedAgent").provide(Logger.combined(logFile.toString))
            _       <- logger.info("combined agent test")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"agentName\":\"CombinedAgent\""))
        }
      },
      test("combined logger withStep propagates to both") {
        withTempLogFile { logFile =>
          for
            logger  <- Logger.service.withStep("Validation").provide(Logger.combined(logFile.toString))
            _       <- logger.info("combined step test")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"step\":\"Validation\""))
        }
      },
    ),
    suite("Logger.console")(
      test("console logger can be created and used") {
        for _ <- Logger.service.info("console test").provide(Logger.console)
        yield assertTrue(true)
      }
    ),
    suite("Codecs round-trip via Duration")(
      test("Duration codec round-trips via milliseconds") {
        check(Gen.long(0L, 1000000L)) { millis =>
          val duration  = Duration.fromMillis(millis)
          val asMillis  = duration.toMillis
          val roundTrip = Duration.fromMillis(asMillis)
          assertTrue(roundTrip == duration)
        }
      }
    ),
  ) @@ sequential
