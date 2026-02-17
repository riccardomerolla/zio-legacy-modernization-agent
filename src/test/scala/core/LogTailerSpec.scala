package core

import java.nio.file.Files

import zio.*
import zio.test.*

object LogTailerSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LogTailerSpec")(
    test("returns initial tail lines and filters by level") {
      ZIO.scoped {
        for
          dir    <- ZIO.attemptBlocking(Files.createTempDirectory("log-tailer-spec"))
          logFile = dir.resolve("app.log")
          _      <- ZIO.attemptBlocking {
                      Files.writeString(
                        logFile,
                        "DEBUG startup\nINFO server ready\nWARN cache miss\nERROR boom\n",
                      )
                    }
          tailer  = LogTailerLive(pollInterval = 10.millis)
          events <- tailer
                      .tail(
                        logFile,
                        levels = Set(core.LogLevel.Error, core.LogLevel.Warn),
                        search = None,
                        initialLines = 50,
                      )
                      .take(2)
                      .runCollect
        yield assertTrue(
          events.length == 2,
          events.map(_.line).forall(line => line.contains("WARN") || line.contains("ERROR")),
        )
      }
    },
    test("filters by search term") {
      ZIO.scoped {
        for
          dir    <- ZIO.attemptBlocking(Files.createTempDirectory("log-tailer-spec-search"))
          logFile = dir.resolve("app.log")
          _      <- ZIO.attemptBlocking {
                      Files.writeString(
                        logFile,
                        "INFO boot complete\nERROR payment failed for customer-7\n",
                      )
                    }
          tailer  = LogTailerLive(pollInterval = 10.millis)
          events <- tailer
                      .tail(
                        logFile,
                        levels = core.LogLevel.all,
                        search = Some("customer-7"),
                        initialLines = 50,
                      )
                      .take(1)
                      .runCollect
        yield assertTrue(
          events.length == 1,
          events.head.line.contains("customer-7"),
        )
      }
    },
  )
