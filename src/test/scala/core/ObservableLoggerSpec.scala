package core

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*
import zio.test.TestAspect.*

/** Tests for ObservableLogger - structured logging with OpenTelemetry trace correlation */
object ObservableLoggerSpec extends ZIOSpecDefault:

  private def withTempLogFile[A](test: Path => ZIO[Any, Any, A]): ZIO[Any, Any, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempFile("observable-log-test", ".log"))
    )(path => ZIO.attempt(Files.deleteIfExists(path)).ignore)(test)

  def spec: Spec[Any, Any] = suite("ObservableLoggerSpec")(
    suite("log level methods")(
      test("info writes JSON log entry to file") {
        withTempLogFile { logFile =>
          for
            _       <- ObservableLogger.info("test info message").provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(
            content.contains("\"level\":\"INFO\""),
            content.contains("\"message\":\"test info message\""),
            content.contains("\"timestamp\":"),
          )
        }
      },
      test("error writes JSON log entry with ERROR level") {
        withTempLogFile { logFile =>
          for
            _       <- ObservableLogger.error("test error").provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(
            content.contains("\"level\":\"ERROR\""),
            content.contains("\"message\":\"test error\""),
          )
        }
      },
      test("error with cause includes cause in JSON") {
        withTempLogFile { logFile =>
          val cause = new RuntimeException("test exception")
          for
            _       <- ObservableLogger
                         .error("error with cause", cause)
                         .provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(
            content.contains("\"level\":\"ERROR\""),
            content.contains("\"cause\":\"test exception\""),
          )
        }
      },
      test("debug writes JSON log entry with DEBUG level") {
        withTempLogFile { logFile =>
          for
            _       <- ObservableLogger.debug("debug msg").provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"level\":\"DEBUG\""))
        }
      },
      test("warn writes JSON log entry with WARN level") {
        withTempLogFile { logFile =>
          for
            _       <- ObservableLogger.warn("warning msg").provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"level\":\"WARN\""))
        }
      },
      test("trace writes JSON log entry with TRACE level") {
        withTempLogFile { logFile =>
          for
            _       <- ObservableLogger.trace("trace msg").provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"level\":\"TRACE\""))
        }
      },
    ),
    suite("context propagation")(
      test("withContext adds annotations to log output") {
        withTempLogFile { logFile =>
          for
            logger       <- ZIO.service[ObservableLogger].provide(ObservableLogger.live(logFile.toString))
            contextLogger = logger.withContext("myKey", "myValue")
            _            <- contextLogger.info("context test")
            content      <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(
            content.contains("\"myKey\":\"myValue\""),
            content.contains("context test"),
          )
        }
      },
      test("withRunId adds runId to log context") {
        withTempLogFile { logFile =>
          for
            logger   <- ZIO.service[ObservableLogger].provide(ObservableLogger.live(logFile.toString))
            runLogger = logger.withRunId("run-123")
            _        <- runLogger.info("run test")
            content  <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"runId\":\"run-123\""))
        }
      },
      test("withAgentName adds agentName to log context") {
        withTempLogFile { logFile =>
          for
            logger     <- ZIO.service[ObservableLogger].provide(ObservableLogger.live(logFile.toString))
            agentLogger = logger.withAgentName("CobolAnalyzer")
            _          <- agentLogger.info("agent test")
            content    <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"agentName\":\"CobolAnalyzer\""))
        }
      },
      test("withStep adds step to log context") {
        withTempLogFile { logFile =>
          for
            logger    <- ZIO.service[ObservableLogger].provide(ObservableLogger.live(logFile.toString))
            stepLogger = logger.withStep("Discovery")
            _         <- stepLogger.info("step test")
            content   <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\"step\":\"Discovery\""))
        }
      },
      test("multiple context values are accumulated") {
        withTempLogFile { logFile =>
          for
            logger  <- ZIO.service[ObservableLogger].provide(ObservableLogger.live(logFile.toString))
            enriched = logger.withRunId("run-456").withAgentName("Mapper").withStep("Mapping")
            _       <- enriched.info("multi-context")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(
            content.contains("\"runId\":\"run-456\""),
            content.contains("\"agentName\":\"Mapper\""),
            content.contains("\"step\":\"Mapping\""),
          )
        }
      },
    ),
    suite("logWithTraceContext")(
      test("logs with trace context when no active span") {
        withTempLogFile { logFile =>
          for
            logger  <- ZIO.service[ObservableLogger].provide(ObservableLogger.live(logFile.toString))
            _       <- logger.logWithTraceContext(LogLevel.Info, "trace context test")
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(
            content.contains("\"level\":\"INFO\""),
            content.contains("trace context test"),
          )
        }
      }
    ),
    suite("message escaping")(
      test("escapes quotes in messages") {
        withTempLogFile { logFile =>
          for
            _       <- ObservableLogger
                         .info("""message with "quotes" inside""")
                         .provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("\\\"quotes\\\""))
        }
      },
      test("escapes newlines in messages") {
        withTempLogFile { logFile =>
          for
            _       <- ObservableLogger
                         .info("line1\nline2")
                         .provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
          yield assertTrue(content.contains("line1\\nline2"))
        }
      },
    ),
    suite("multiple log entries")(
      test("appends multiple entries to same file") {
        withTempLogFile { logFile =>
          for
            _       <- ObservableLogger.info("first").provide(ObservableLogger.live(logFile.toString))
            _       <- ObservableLogger.info("second").provide(ObservableLogger.live(logFile.toString))
            _       <- ObservableLogger.info("third").provide(ObservableLogger.live(logFile.toString))
            content <- ZIO.attempt(Files.readString(logFile))
            lines    = content.split("\n").filter(_.nonEmpty)
          yield assertTrue(lines.length == 3)
        }
      }
    ),
  ) @@ sequential
