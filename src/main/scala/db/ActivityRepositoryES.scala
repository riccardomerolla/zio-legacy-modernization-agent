package db

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.gigamap.domain.GigaMapQuery
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import models.{ ActivityEvent, ActivityEventType }
import store.{ DataStoreModule, EventId }

final case class ActivityRepositoryES(
  events: GigaMap[EventId, store.ActivityEventRow]
) extends ActivityRepository:

  override def createEvent(event: ActivityEvent): IO[PersistenceError, Long] =
    for
      id <- nextId("createEvent")
      _  <- events
              .put(EventId(id.toString), toStoreRow(event, id))
              .mapError(storeError("createEvent"))
    yield id

  override def listEvents(
    eventType: Option[ActivityEventType],
    since: Option[java.time.Instant],
    limit: Int,
  ): IO[PersistenceError, List[ActivityEvent]] =
    (eventType match
      case Some(value) => events.query(GigaMapQuery.ByIndex("eventType", value.toString))
      case None        => events.query(GigaMapQuery.All[store.ActivityEventRow]())
    )
      .mapError(storeError("listEvents"))
      .map(
        _.toList
          .flatMap(fromStoreRow)
          .filter(event => since.forall(s => !event.createdAt.isBefore(s)))
          .sortBy(_.createdAt)(Ordering[java.time.Instant].reverse)
          .take(limit)
      )

  private def nextId(op: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeError(op))
      .flatMap(id => if id == 0L then nextId(op) else ZIO.succeed(id))

  private def storeError(op: String)(throwable: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(throwable.getMessage).getOrElse(throwable.toString))

  private def toStoreRow(event: ActivityEvent, id: Long): store.ActivityEventRow =
    store.ActivityEventRow(
      id = id.toString,
      eventType = event.eventType.toString,
      source = event.source,
      runId = event.runId.map(_.toString),
      conversationId = event.conversationId.map(_.toString),
      agentName = event.agentName,
      summary = event.summary,
      payload = event.payload,
      createdAt = event.createdAt,
    )

  private def fromStoreRow(row: store.ActivityEventRow): Option[ActivityEvent] =
    for
      id         <- row.id.toLongOption
      parsedType <- parseEventType(row.eventType)
    yield ActivityEvent(
      id = Some(id),
      eventType = parsedType,
      source = row.source,
      runId = row.runId.flatMap(_.toLongOption),
      conversationId = row.conversationId.flatMap(_.toLongOption),
      agentName = row.agentName,
      summary = row.summary,
      payload = row.payload,
      createdAt = row.createdAt,
    )

  private def parseEventType(raw: String): Option[ActivityEventType] =
    raw match
      case "RunStarted" | "run_started"       => Some(ActivityEventType.RunStarted)
      case "RunCompleted" | "run_completed"   => Some(ActivityEventType.RunCompleted)
      case "RunFailed" | "run_failed"         => Some(ActivityEventType.RunFailed)
      case "AgentAssigned" | "agent_assigned" => Some(ActivityEventType.AgentAssigned)
      case "MessageSent" | "message_sent"     => Some(ActivityEventType.MessageSent)
      case "ConfigChanged" | "config_changed" => Some(ActivityEventType.ConfigChanged)
      case _                                  => None

object ActivityRepositoryES:
  val live: ZLayer[DataStoreModule.ActivityEventsStore, Nothing, ActivityRepository] =
    ZLayer.fromZIO {
      ZIO
        .serviceWith[DataStoreModule.ActivityEventsStore](_.map)
        .map(ActivityRepositoryES.apply)
    }
