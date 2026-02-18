package core

import java.nio.file.Paths

import zio.*

import _root_.config.ConfigLoader
import com.typesafe.config.ConfigFactory
import models.{ ConfigFormat, ConfigValidationIssue, ConfigValidationResult, MigrationConfig }

trait ConfigValidator:
  def validate(content: String, format: ConfigFormat): UIO[ConfigValidationResult]
  def validateAndParse(content: String, format: ConfigFormat): IO[ConfigValidationResult, MigrationConfig]

object ConfigValidator:

  def validate(content: String, format: ConfigFormat): URIO[ConfigValidator, ConfigValidationResult] =
    ZIO.serviceWithZIO[ConfigValidator](_.validate(content, format))

  def validateAndParse(content: String, format: ConfigFormat)
    : ZIO[ConfigValidator, ConfigValidationResult, MigrationConfig] =
    ZIO.serviceWithZIO[ConfigValidator](_.validateAndParse(content, format))

  val live: ULayer[ConfigValidator] =
    ZLayer.succeed(ConfigValidatorLive())

final case class ConfigValidatorLive() extends ConfigValidator:

  override def validate(content: String, format: ConfigFormat): UIO[ConfigValidationResult] =
    validateAndParse(content, format).fold(identity, _ => ConfigValidationResult(valid = true, issues = Nil))

  override def validateAndParse(content: String, format: ConfigFormat): IO[ConfigValidationResult, MigrationConfig] =
    parseMigrationConfig(content).flatMap { parsed =>
      ConfigLoader
        .validate(parsed)
        .mapError(msg => ConfigValidationResult(valid = false, issues = List(ConfigValidationIssue(msg))))
    }

  private def parseMigrationConfig(content: String): IO[ConfigValidationResult, MigrationConfig] =
    val parsedEither =
      for
        parsed <- scala.util
                    .Try(ConfigFactory.parseString(content).resolve())
                    .toEither
                    .left
                    .map(err => Option(err.getMessage).getOrElse(err.getClass.getSimpleName))
        root    = if parsed.hasPath("migration") then parsed.getConfig("migration") else parsed
        source <- stringOf(root, "sourceDir", "source-dir")
                    .toRight("Missing required field: migration.sourceDir")
        output <- stringOf(root, "outputDir", "output-dir")
                    .toRight("Missing required field: migration.outputDir")
      yield MigrationConfig(
        sourceDir = Paths.get(source),
        outputDir = Paths.get(output),
        stateDir = Paths.get(stringOf(root, "stateDir", "state-dir").getOrElse(".migration-state")),
        parallelism = intOf(root, 4, "parallelism"),
        batchSize = intOf(root, 10, "batchSize", "batch-size"),
        discoveryMaxDepth = intOf(root, 25, "discoveryMaxDepth", "discovery-max-depth"),
      )

    ZIO.fromEither(parsedEither).mapError { msg =>
      ConfigValidationResult(valid = false, issues = List(ConfigValidationIssue(msg)))
    }

  private def stringOf(root: com.typesafe.config.Config, keys: String*): Option[String] =
    keys.collectFirst { case key if root.hasPath(key) => root.getString(key).trim }.filter(_.nonEmpty)

  private def intOf(root: com.typesafe.config.Config, default: Int, keys: String*): Int =
    keys.collectFirst { case key if root.hasPath(key) => root.getInt(key) }.getOrElse(default)
