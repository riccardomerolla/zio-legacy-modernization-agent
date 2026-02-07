package core

import zio.*

/** ObservableLogger - Logger with OpenTelemetry trace correlation
  *
  * Features:
  *   - Delegates to ZIO logging backed by SLF4J/Logback
  *   - Automatic trace ID and span ID injection via zio-opentelemetry-zio-logging
  *   - JSON format with telemetry correlation fields via logstash-logback-encoder
  *   - Configured in logback.xml for separation of concerns
  */
trait ObservableLogger extends Logger

object ObservableLogger:
  def trace(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.trace(message))

  def debug(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.debug(message))

  def info(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.info(message))

  def warn(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.warn(message))

  def error(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.error(message))

  def error(message: => String, cause: Throwable): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.error(message, cause))

  /** Live implementation - delegates to Logger with OTEL trace correlation
    *
    * Trace IDs are automatically added via zio-opentelemetry-zio-logging bridge. JSON formatting and file output are
    * configured in logback.xml.
    */
  def live: ZLayer[Any, Nothing, ObservableLogger] = ZLayer.succeed {
    new ObservableLogger:
      def trace(message: => String): UIO[Unit]                   = Logger.trace(message)
      def debug(message: => String): UIO[Unit]                   = Logger.debug(message)
      def info(message: => String): UIO[Unit]                    = Logger.info(message)
      def warn(message: => String): UIO[Unit]                    = Logger.warn(message)
      def error(message: => String): UIO[Unit]                   = Logger.error(message)
      def error(message: => String, cause: Throwable): UIO[Unit] = Logger.error(message, cause)
  }
