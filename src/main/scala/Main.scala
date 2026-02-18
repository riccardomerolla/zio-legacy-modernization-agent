import java.nio.file.{ Path, Paths }

import zio.*
import zio.Console.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.logging.backend.SLF4J

import _root_.config.ConfigLoader
import di.ApplicationDI
import models.MigrationConfig
import web.WebServer

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private type ServeOpts = (Option[BigInt], Option[String], Option[String], Option[Path])

  private val portOpt       = Options.integer("port").optional ?? "HTTP server port (default: 8080)"
  private val hostOpt       = Options.text("host").optional ?? "HTTP server host (default: 0.0.0.0)"
  private val dbPathOpt     = Options.text("db-path").optional ?? "SQLite DB path (default: ./migration.db)"
  private val configFileOpt = Options.file("config").alias("c").optional ?? "Configuration file (HOCON or JSON)"

  private val serveCmd: Command[ServeOpts] =
    Command("serve", portOpt ++ hostOpt ++ dbPathOpt ++ configFileOpt)
      .withHelp("Start the web portal")

  private val cliApp = CliApp.make(
    name = "zio-legacy-modernization",
    version = "1.0.0",
    summary = text("Self-hosted AI gateway"),
    command = serveCmd,
  ) { opts =>
    executeServe(
      port = opts._1.map(_.toInt).getOrElse(8080),
      host = opts._2.getOrElse("0.0.0.0"),
      dbPath = Paths.get(opts._3.getOrElse("./migration.db")),
      configFile = opts._4,
    )
  }

  private def executeServe(port: Int, host: String, dbPath: Path, configFile: Option[Path]): ZIO[Any, Throwable, Unit] =
    for
      baseConfig <- loadConfig(configFile)
      validated  <- ConfigLoader.validate(baseConfig).mapError(msg => new IllegalArgumentException(msg))
      _          <- printLine(s"Starting web server on http://$host:$port")
      _          <- WebServer.start(host, port).provide(ApplicationDI.webServerLayer(validated, dbPath))
    yield ()

  private def loadConfig(configFile: Option[Path]): ZIO[Any, Throwable, MigrationConfig] =
    configFile match
      case Some(path) =>
        ConfigLoader.loadFromFile(path).mapError(error => new IllegalArgumentException(error.toString))
      case None       =>
        ConfigLoader
          .loadWithEnvOverrides
          .orElseSucceed(MigrationConfig(sourceDir = Paths.get("."), outputDir = Paths.get("./workspace/output")))

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      args <- ZIOAppArgs.getArgs
      _    <- cliApp.run(args.toList).orDie
    yield ()
