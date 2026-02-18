package orchestration

import zio.*

import db.PersistenceError
import models.{ ActivityEvent, ActivityEventType, ProgressUpdate }
import web.ActivityHub

trait ProgressTracker:
  def startPhase(runId: Long, phase: String, total: Int): IO[PersistenceError, Unit]
  def updateProgress(update: ProgressUpdate): IO[PersistenceError, Unit]
  def completePhase(runId: Long, phase: String): IO[PersistenceError, Unit]
  def failPhase(runId: Long, phase: String, error: String): IO[PersistenceError, Unit]
  def subscribe(runId: Long): UIO[Dequeue[ProgressUpdate]]

object ProgressTracker:
  def startPhase(runId: Long, phase: String, total: Int): ZIO[ProgressTracker, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ProgressTracker](_.startPhase(runId, phase, total))

  def updateProgress(update: ProgressUpdate): ZIO[ProgressTracker, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ProgressTracker](_.updateProgress(update))

  def completePhase(runId: Long, phase: String): ZIO[ProgressTracker, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ProgressTracker](_.completePhase(runId, phase))

  def failPhase(runId: Long, phase: String, error: String): ZIO[ProgressTracker, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ProgressTracker](_.failPhase(runId, phase, error))

  def subscribe(runId: Long): ZIO[ProgressTracker, Nothing, Dequeue[ProgressUpdate]] =
    ZIO.serviceWithZIO[ProgressTracker](_.subscribe(runId))

  final case class PhaseState(
    status: String,
    itemTotal: Int,
    itemProcessed: Int,
    errorCount: Int,
  )

  val live: ZLayer[ActivityHub, Nothing, ProgressTracker] =
    ZLayer.scoped {
      for
        activityHub <- ZIO.service[ActivityHub]
        hub         <- Hub.bounded[ProgressUpdate](256)
        subscribers <- Ref.make(Map.empty[Long, Set[Queue[ProgressUpdate]]])
        phaseStates <- Ref.make(Map.empty[(Long, String), PhaseState])
        hubQueue    <- hub.subscribe
        _           <- hubQueue.take.flatMap(publishToSubscribers(subscribers, _)).forever.forkScoped
      yield ProgressTrackerLive(hub, subscribers, phaseStates, activityHub)
    }

  private def publishToSubscribers(
    subscribers: Ref[Map[Long, Set[Queue[ProgressUpdate]]]],
    update: ProgressUpdate,
  ): UIO[Unit] =
    for
      current <- subscribers.get
      targets  = current.getOrElse(update.runId, Set.empty)
      _       <- ZIO.foreachDiscard(targets)(_.offer(update).unit)
    yield ()

final case class ProgressTrackerLive(
  hub: Hub[ProgressUpdate],
  subscribers: Ref[Map[Long, Set[Queue[ProgressUpdate]]]],
  phaseStates: Ref[Map[(Long, String), ProgressTracker.PhaseState]],
  activityHub: ActivityHub,
) extends ProgressTracker:

  override def startPhase(runId: Long, phase: String, total: Int): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- phaseStates.update(
               _.updated((runId, phase), ProgressTracker.PhaseState("Running", total, 0, 0))
             )
      _   <- publish(
               ProgressUpdate(
                 runId = runId,
                 phase = phase,
                 itemsProcessed = 0,
                 itemsTotal = total,
                 message = s"Starting phase: $phase",
                 timestamp = now,
               )
             )
      _   <- activityHub.publish(
               ActivityEvent(
                 eventType = ActivityEventType.RunStarted,
                 source = "progress-tracker",
                 runId = Some(runId),
                 summary = s"Run #$runId started phase: $phase",
                 createdAt = now,
               )
             )
    yield ()

  override def updateProgress(update: ProgressUpdate): IO[PersistenceError, Unit] =
    for
      _ <- phaseStates.update { current =>
             val key      = (update.runId, update.phase)
             val existing = current.getOrElse(key, ProgressTracker.PhaseState("Running", 0, 0, 0))
             current.updated(
               key,
               existing.copy(
                 status = "Running",
                 itemTotal = update.itemsTotal,
                 itemProcessed = update.itemsProcessed,
               ),
             )
           }
      _ <- publish(update)
    yield ()

  override def completePhase(runId: Long, phase: String): IO[PersistenceError, Unit] =
    for
      now      <- Clock.instant
      previous <- phaseStates.get.map(_.get((runId, phase)))
      _        <- phaseStates.update { current =>
                    val key      = (runId, phase)
                    val existing = current.getOrElse(key, ProgressTracker.PhaseState("Completed", 0, 0, 0))
                    current.updated(
                      key,
                      existing.copy(
                        status = "Completed",
                        itemProcessed = existing.itemTotal,
                      ),
                    )
                  }
      total     = previous.map(_.itemTotal).getOrElse(0)
      _        <- publish(
                    ProgressUpdate(
                      runId = runId,
                      phase = phase,
                      itemsProcessed = total,
                      itemsTotal = total,
                      message = s"Completed phase: $phase",
                      timestamp = now,
                    )
                  )
      _        <- activityHub.publish(
                    ActivityEvent(
                      eventType = ActivityEventType.RunCompleted,
                      source = "progress-tracker",
                      runId = Some(runId),
                      summary = s"Run #$runId completed phase: $phase",
                      createdAt = now,
                    )
                  )
    yield ()

  override def failPhase(runId: Long, phase: String, error: String): IO[PersistenceError, Unit] =
    for
      now      <- Clock.instant
      previous <- phaseStates.get.map(_.get((runId, phase)))
      _        <- phaseStates.update { current =>
                    val key      = (runId, phase)
                    val existing = current.getOrElse(key, ProgressTracker.PhaseState("Failed", 0, 0, 0))
                    current.updated(
                      key,
                      existing.copy(
                        status = "Failed",
                        errorCount = existing.errorCount + 1,
                      ),
                    )
                  }
      total     = previous.map(_.itemTotal).getOrElse(0)
      done      = previous.map(_.itemProcessed).getOrElse(0)
      _        <- publish(
                    ProgressUpdate(
                      runId = runId,
                      phase = phase,
                      itemsProcessed = done,
                      itemsTotal = total,
                      message = error,
                      timestamp = now,
                    )
                  )
      _        <- activityHub.publish(
                    ActivityEvent(
                      eventType = ActivityEventType.RunFailed,
                      source = "progress-tracker",
                      runId = Some(runId),
                      summary = s"Run #$runId failed phase: $phase",
                      createdAt = now,
                    )
                  )
    yield ()

  override def subscribe(runId: Long): UIO[Dequeue[ProgressUpdate]] =
    for
      queue <- Queue.bounded[ProgressUpdate](256)
      _     <- subscribers.update(current =>
                 current.updated(runId, current.getOrElse(runId, Set.empty) + queue)
               )
    yield queue

  private def publish(update: ProgressUpdate): UIO[Unit] =
    hub.publish(update).unit
