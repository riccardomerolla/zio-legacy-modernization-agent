package gateway

import zio.*

import gateway.models.NormalizedMessage

enum GatewayServiceError:
  case Router(error: MessageRouterError)
  case QueueClosed

enum GatewayQueueCommand:
  case Inbound(message: NormalizedMessage)
  case Outbound(message: NormalizedMessage)

case class GatewayMetricsSnapshot(
  enqueued: Long = 0L,
  processed: Long = 0L,
  failed: Long = 0L,
  chunkedMessages: Long = 0L,
  emittedChunks: Long = 0L,
  steeringForwarded: Long = 0L,
)

trait GatewayService:
  def enqueueInbound(message: NormalizedMessage): UIO[Unit]
  def enqueueOutbound(message: NormalizedMessage): UIO[Unit]

  def processInbound(message: NormalizedMessage): IO[GatewayServiceError, Unit]
  def processOutbound(message: NormalizedMessage): IO[GatewayServiceError, List[NormalizedMessage]]

  def metrics: UIO[GatewayMetricsSnapshot]

object GatewayService:
  def enqueueInbound(message: NormalizedMessage): ZIO[GatewayService, Nothing, Unit] =
    ZIO.serviceWithZIO[GatewayService](_.enqueueInbound(message))

  def enqueueOutbound(message: NormalizedMessage): ZIO[GatewayService, Nothing, Unit] =
    ZIO.serviceWithZIO[GatewayService](_.enqueueOutbound(message))

  def processInbound(message: NormalizedMessage): ZIO[GatewayService, GatewayServiceError, Unit] =
    ZIO.serviceWithZIO[GatewayService](_.processInbound(message))

  def processOutbound(message: NormalizedMessage): ZIO[GatewayService, GatewayServiceError, List[NormalizedMessage]] =
    ZIO.serviceWithZIO[GatewayService](_.processOutbound(message))

  def metrics: ZIO[GatewayService, Nothing, GatewayMetricsSnapshot] =
    ZIO.serviceWithZIO[GatewayService](_.metrics)

  val live: ZLayer[MessageRouter, Nothing, GatewayService] =
    ZLayer.scoped {
      for
        router  <- ZIO.service[MessageRouter]
        queue   <- Queue.unbounded[GatewayQueueCommand]
        metrics <- Ref.make(GatewayMetricsSnapshot())
        _       <- startWorker(queue, router, metrics, None).forkScoped
      yield GatewayServiceLive(queue, router, metrics, None)
    }

  val liveWithSteeringQueue: ZLayer[MessageRouter & Queue[NormalizedMessage], Nothing, GatewayService] =
    ZLayer.scoped {
      for
        router        <- ZIO.service[MessageRouter]
        steeringQueue <- ZIO.service[Queue[NormalizedMessage]]
        queue         <- Queue.unbounded[GatewayQueueCommand]
        metrics       <- Ref.make(GatewayMetricsSnapshot())
        _             <- startWorker(queue, router, metrics, Some(steeringQueue)).forkScoped
      yield GatewayServiceLive(queue, router, metrics, Some(steeringQueue))
    }

  private def startWorker(
    queue: Queue[GatewayQueueCommand],
    router: MessageRouter,
    metricsRef: Ref[GatewayMetricsSnapshot],
    steeringQueue: Option[Queue[NormalizedMessage]],
  ): UIO[Unit] =
    def markProcessed: UIO[Unit] =
      metricsRef.update(current => current.copy(processed = current.processed + 1))

    def markFailed: UIO[Unit] =
      metricsRef.update(current => current.copy(failed = current.failed + 1))

    def forwardSteering(message: NormalizedMessage): UIO[Unit] =
      steeringQueue match
        case None    => ZIO.unit
        case Some(q) =>
          q.offer(message).unit *>
            metricsRef.update(current => current.copy(steeringForwarded = current.steeringForwarded + 1))

    def runCommand(command: GatewayQueueCommand): UIO[Unit] =
      command match
        case GatewayQueueCommand.Inbound(message)  =>
          (forwardSteering(message) *> router.routeInbound(message).mapError(GatewayServiceError.Router.apply))
            .tapError(err => ZIO.logWarning(s"gateway inbound processing failed: $err"))
            .foldZIO(_ => markFailed, _ => markProcessed)
        case GatewayQueueCommand.Outbound(message) =>
          val chunks       = ResponseChunker.chunkMessageForChannel(message)
          val markChunking =
            metricsRef.update { current =>
              if chunks.length > 1 then
                current.copy(
                  chunkedMessages = current.chunkedMessages + 1,
                  emittedChunks = current.emittedChunks + chunks.length.toLong,
                )
              else current
            }
          (forwardSteering(message) *>
            markChunking *>
            ZIO.foreachDiscard(chunks)(chunk => router.routeOutbound(chunk).mapError(GatewayServiceError.Router.apply)))
            .tapError(err => ZIO.logWarning(s"gateway outbound processing failed: $err"))
            .foldZIO(_ => markFailed, _ => markProcessed)

    def loop: UIO[Unit] =
      queue.take.flatMap(runCommand).forever

    loop

final case class GatewayServiceLive(
  queue: Queue[GatewayQueueCommand],
  router: MessageRouter,
  metricsRef: Ref[GatewayMetricsSnapshot],
  steeringQueue: Option[Queue[NormalizedMessage]],
) extends GatewayService:

  override def enqueueInbound(message: NormalizedMessage): UIO[Unit] =
    queue.offer(GatewayQueueCommand.Inbound(message)).unit *>
      metricsRef.update(current => current.copy(enqueued = current.enqueued + 1))

  override def enqueueOutbound(message: NormalizedMessage): UIO[Unit] =
    queue.offer(GatewayQueueCommand.Outbound(message)).unit *>
      metricsRef.update(current => current.copy(enqueued = current.enqueued + 1))

  override def processInbound(message: NormalizedMessage): IO[GatewayServiceError, Unit] =
    for
      _ <- steeringQueue match
             case None    => ZIO.unit
             case Some(q) =>
               q.offer(message).unit *>
                 metricsRef.update(current => current.copy(steeringForwarded = current.steeringForwarded + 1))
      _ <- router.routeInbound(message).mapError(GatewayServiceError.Router.apply)
      _ <- metricsRef.update(current => current.copy(processed = current.processed + 1))
    yield ()

  override def processOutbound(message: NormalizedMessage): IO[GatewayServiceError, List[NormalizedMessage]] =
    val chunks = ResponseChunker.chunkMessageForChannel(message)
    for
      _ <- steeringQueue match
             case None    => ZIO.unit
             case Some(q) =>
               q.offer(message).unit *>
                 metricsRef.update(current => current.copy(steeringForwarded = current.steeringForwarded + 1))
      _ <- ZIO.foreachDiscard(chunks)(chunk => router.routeOutbound(chunk).mapError(GatewayServiceError.Router.apply))
      _ <- metricsRef.update { current =>
             current.copy(
               processed = current.processed + 1,
               chunkedMessages = current.chunkedMessages + (if chunks.length > 1 then 1 else 0),
               emittedChunks = current.emittedChunks + chunks.length.toLong,
             )
           }
    yield chunks

  override def metrics: UIO[GatewayMetricsSnapshot] =
    metricsRef.get
