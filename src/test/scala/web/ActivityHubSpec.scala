package web

import java.time.Instant

import zio.*
import zio.test.*

import db.{ ActivityRepository, PersistenceError }
import models.{ ActivityEvent, ActivityEventType }

object ActivityHubSpec extends ZIOSpecDefault:

  private val stubRepository: ActivityRepository = new ActivityRepository:
    override def createEvent(event: ActivityEvent): IO[PersistenceError, Long] = ZIO.succeed(1L)
    override def listEvents(
      eventType: Option[ActivityEventType],
      since: Option[Instant],
      limit: Int,
    ): IO[PersistenceError, List[ActivityEvent]] = ZIO.succeed(Nil)

  def spec: Spec[TestEnvironment, Any] = suite("ActivityHubSpec")(
    test("publish delivers event to subscriber") {
      for
        subscribers <- Ref.make(Set.empty[Queue[ActivityEvent]])
        hub          = ActivityHubLive(stubRepository, subscribers)
        queue       <- hub.subscribe
        now         <- Clock.instant
        event        = ActivityEvent(
                         eventType = ActivityEventType.RunStarted,
                         source = "test",
                         summary = "Test run started",
                         createdAt = now,
                       )
        _           <- hub.publish(event)
        received    <- queue.take
      yield assertTrue(
        received.eventType == ActivityEventType.RunStarted,
        received.summary == "Test run started",
      )
    },
    test("multiple subscribers each receive the event") {
      for
        subscribers <- Ref.make(Set.empty[Queue[ActivityEvent]])
        hub          = ActivityHubLive(stubRepository, subscribers)
        q1          <- hub.subscribe
        q2          <- hub.subscribe
        now         <- Clock.instant
        event        = ActivityEvent(
                         eventType = ActivityEventType.ConfigChanged,
                         source = "test",
                         summary = "Config updated",
                         createdAt = now,
                       )
        _           <- hub.publish(event)
        r1          <- q1.take
        r2          <- q2.take
      yield assertTrue(
        r1.summary == "Config updated",
        r2.summary == "Config updated",
      )
    },
    test("subscribe returns empty queue before any publish") {
      for
        subscribers <- Ref.make(Set.empty[Queue[ActivityEvent]])
        hub          = ActivityHubLive(stubRepository, subscribers)
        queue       <- hub.subscribe
        isEmpty     <- queue.isEmpty
      yield assertTrue(isEmpty)
    },
  )
