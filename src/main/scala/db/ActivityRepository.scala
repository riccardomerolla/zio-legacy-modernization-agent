package db

import java.sql.{ Connection, ResultSet, Statement, Types }
import java.time.Instant
import javax.sql.DataSource

import zio.*

import models.{ ActivityEvent, ActivityEventType }

trait ActivityRepository:
  def createEvent(event: ActivityEvent): IO[PersistenceError, Long]
  def listEvents(
    eventType: Option[ActivityEventType] = None,
    since: Option[Instant] = None,
    limit: Int = 50,
  ): IO[PersistenceError, List[ActivityEvent]]

object ActivityRepository:

  def createEvent(event: ActivityEvent): ZIO[ActivityRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ActivityRepository](_.createEvent(event))

  def listEvents(
    eventType: Option[ActivityEventType] = None,
    since: Option[Instant] = None,
    limit: Int = 50,
  ): ZIO[ActivityRepository, PersistenceError, List[ActivityEvent]] =
    ZIO.serviceWithZIO[ActivityRepository](_.listEvents(eventType, since, limit))

  val live: ZLayer[DataSource, PersistenceError, ActivityRepository] =
    ZLayer.fromZIO {
      for
        dataSource  <- ZIO.service[DataSource]
        initialized <- Ref.Synchronized.make(false)
      yield ActivityRepositoryLive(dataSource, initialized)
    }

final case class ActivityRepositoryLive(
  ds: DataSource,
  initialized: Ref.Synchronized[Boolean],
) extends ActivityRepository:

  import ActivityRepositoryLive.*

  override def createEvent(event: ActivityEvent): IO[PersistenceError, Long] =
    val sql =
      """INSERT INTO activity_events (event_type, source, run_id, conversation_id, agent_name, summary, payload, created_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        |""".stripMargin

    withConnection { conn =>
      executeUpdateReturningKey(conn, sql, "activity_events") { stmt =>
        stmt.setString(1, toDbEventType(event.eventType))
        stmt.setString(2, event.source)
        setOptionalLong(stmt, 3, event.runId)
        setOptionalLong(stmt, 4, event.conversationId)
        setOptionalString(stmt, 5, event.agentName)
        stmt.setString(6, event.summary)
        setOptionalString(stmt, 7, event.payload)
        stmt.setString(8, event.createdAt.toString)
      }
    }

  override def listEvents(
    eventType: Option[ActivityEventType],
    since: Option[Instant],
    limit: Int,
  ): IO[PersistenceError, List[ActivityEvent]] =
    val conditions = List(
      eventType.map(_ => "event_type = ?"),
      since.map(_ => "created_at >= ?"),
    ).flatten

    val whereClause = if conditions.isEmpty then "" else s"WHERE ${conditions.mkString(" AND ")}"
    val sql         = s"SELECT * FROM activity_events $whereClause ORDER BY created_at DESC LIMIT ?"

    withConnection { conn =>
      queryMany(conn, sql) { stmt =>
        val params: List[String] =
          eventType.map(toDbEventType).toList ++ since.map(_.toString).toList
        params.zipWithIndex.foreach { case (value, i) => stmt.setString(i + 1, value) }
        stmt.setInt(params.length + 1, limit)
      }(readActivityEventRow(_, sql))
    }

  private def withConnection[A](use: Connection => IO[PersistenceError, A]): IO[PersistenceError, A] =
    ensureSchemaInitialized *> ZIO.acquireReleaseWith(acquireConnection)(closeConnection)(use)

  private def acquireConnection: IO[PersistenceError, Connection] =
    ZIO
      .attemptBlocking {
        val conn = ds.getConnection
        val stmt = conn.createStatement()
        try stmt.execute("PRAGMA foreign_keys = ON")
        finally stmt.close()
        conn
      }
      .mapError(e => PersistenceError.ConnectionFailed(e.getMessage))

  private def closeConnection(conn: Connection): UIO[Unit] =
    ZIO.attemptBlocking(conn.close()).ignore

  private def ensureSchemaInitialized: IO[PersistenceError, Unit] =
    initialized.modifyZIO {
      case true  => ZIO.succeed(((), true))
      case false => initializeSchema.as(((), true))
    }

  private def initializeSchema: IO[PersistenceError, Unit] =
    for
      statements <- loadSchemaStatements(
                      List("db/V1__init_schema.sql", "db/V2__chat_and_issues.sql", "db/V5__activity_events.sql")
                    )
      _          <- ZIO.acquireReleaseWith(acquireConnection)(closeConnection) { conn =>
                      ZIO.foreachDiscard(statements) { sql =>
                        withStatement(conn, sql)(stmt => executeBlocking(sql)(stmt.execute(sql)).unit)
                      }
                    }
    yield ()

object ActivityRepositoryLive:

  private def loadSchemaStatements(resources: List[String]): IO[PersistenceError, List[String]] =
    ZIO.foreach(resources)(loadStatementsFromResource).map(_.flatten)

  private def loadStatementsFromResource(resource: String): IO[PersistenceError, List[String]] =
    for
      maybeScript <- ZIO
                       .attempt(Option(getClass.getClassLoader.getResourceAsStream(resource)))
                       .mapError(e => PersistenceError.SchemaInitFailed(e.getMessage))
      stream      <- ZIO
                       .fromOption(maybeScript)
                       .orElseFail(PersistenceError.SchemaInitFailed(s"Schema resource $resource not found"))
      script      <- ZIO
                       .acquireReleaseWith(
                         ZIO.succeed(scala.io.Source.fromInputStream(stream, "UTF-8"))
                       )(src => ZIO.attempt(src.close()).orDie)(src => ZIO.attempt(src.mkString))
                       .mapError(e => PersistenceError.SchemaInitFailed(e.getMessage))
    yield script.split(";").map(_.trim).filter(_.nonEmpty).toList

  private def executeBlocking[A](sql: String)(thunk: => A): IO[PersistenceError, A] =
    ZIO.attemptBlocking(thunk).mapError(e => PersistenceError.QueryFailed(sql, e.getMessage))

  private def withStatement[A](
    conn: Connection,
    sql: String,
  )(
    use: Statement => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    ZIO.acquireReleaseWith(executeBlocking(sql)(conn.createStatement()))(stmt =>
      executeBlocking(sql)(stmt.close()).ignore
    )(use)

  private def withPreparedStatement[A](
    conn: Connection,
    sql: String,
    returnGeneratedKeys: Int = Statement.NO_GENERATED_KEYS,
  )(
    use: java.sql.PreparedStatement => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    ZIO.acquireReleaseWith(
      executeBlocking(sql)(conn.prepareStatement(sql, returnGeneratedKeys))
    )(stmt => executeBlocking(sql)(stmt.close()).ignore)(use)

  private def executeUpdateReturningKey(
    conn: Connection,
    sql: String,
    entity: String,
  )(
    bind: java.sql.PreparedStatement => Unit
  ): IO[PersistenceError, Long] =
    withPreparedStatement(conn, sql, Statement.RETURN_GENERATED_KEYS) { stmt =>
      for
        _        <- executeBlocking(sql)(bind(stmt))
        _        <- executeBlocking(sql)(stmt.executeUpdate()).unit
        maybeKey <- executeBlocking(sql) {
                      val keys = stmt.getGeneratedKeys
                      try if keys.next() then Some(keys.getLong(1)) else None
                      finally keys.close()
                    }
        key      <- ZIO
                      .fromOption(maybeKey)
                      .orElseFail(PersistenceError.QueryFailed(sql, s"No generated key returned for $entity"))
      yield key
    }

  private def queryMany[A](
    conn: Connection,
    sql: String,
  )(
    bind: java.sql.PreparedStatement => Unit
  )(
    read: ResultSet => IO[PersistenceError, A]
  ): IO[PersistenceError, List[A]] =
    withPreparedStatement(conn, sql) { stmt =>
      for
        _      <- executeBlocking(sql)(bind(stmt))
        result <- executeQuery(stmt, sql) { rs =>
                    def loop(acc: List[A]): IO[PersistenceError, List[A]] =
                      executeBlocking(sql)(rs.next()).flatMap {
                        case true  =>
                          read(rs).flatMap { value =>
                            loop(value :: acc)
                          }
                        case false => ZIO.succeed(acc.reverse)
                      }
                    loop(Nil)
                  }
      yield result
    }

  private def executeQuery[A](
    stmt: java.sql.PreparedStatement,
    sql: String,
  )(
    use: ResultSet => IO[PersistenceError, A]
  ): IO[PersistenceError, A] =
    ZIO.acquireReleaseWith(
      executeBlocking(sql)(stmt.executeQuery())
    )(rs => executeBlocking(sql)(rs.close()).ignore)(use)

  private def setOptionalLong(stmt: java.sql.PreparedStatement, idx: Int, value: Option[Long]): Unit =
    value match
      case Some(v) => stmt.setLong(idx, v)
      case None    => stmt.setNull(idx, Types.BIGINT)

  private def setOptionalString(stmt: java.sql.PreparedStatement, idx: Int, value: Option[String]): Unit =
    value match
      case Some(v) => stmt.setString(idx, v)
      case None    => stmt.setNull(idx, Types.VARCHAR)

  private def getOptionalLong(rs: ResultSet, column: String): Option[Long] =
    val value = rs.getLong(column)
    if rs.wasNull() then None else Some(value)

  private def parseInstant(sql: String, raw: String): IO[PersistenceError, Instant] =
    ZIO.attempt(Instant.parse(raw)).mapError(e => PersistenceError.QueryFailed(sql, e.getMessage))

  private def toDbEventType(eventType: ActivityEventType): String = eventType match
    case ActivityEventType.RunStarted    => "run_started"
    case ActivityEventType.RunCompleted  => "run_completed"
    case ActivityEventType.RunFailed     => "run_failed"
    case ActivityEventType.AgentAssigned => "agent_assigned"
    case ActivityEventType.MessageSent   => "message_sent"
    case ActivityEventType.ConfigChanged => "config_changed"

  private def parseEventType(sql: String, value: String): IO[PersistenceError, ActivityEventType] =
    value.toLowerCase match
      case "run_started"    => ZIO.succeed(ActivityEventType.RunStarted)
      case "run_completed"  => ZIO.succeed(ActivityEventType.RunCompleted)
      case "run_failed"     => ZIO.succeed(ActivityEventType.RunFailed)
      case "agent_assigned" => ZIO.succeed(ActivityEventType.AgentAssigned)
      case "message_sent"   => ZIO.succeed(ActivityEventType.MessageSent)
      case "config_changed" => ZIO.succeed(ActivityEventType.ConfigChanged)
      case other            => ZIO.fail(PersistenceError.QueryFailed(sql, s"Unknown event_type: $other"))

  private def readActivityEventRow(rs: ResultSet, sql: String): IO[PersistenceError, ActivityEvent] =
    for
      eventType <- parseEventType(sql, rs.getString("event_type"))
      createdAt <- parseInstant(sql, rs.getString("created_at"))
    yield ActivityEvent(
      id = Some(rs.getLong("id")),
      eventType = eventType,
      source = rs.getString("source"),
      runId = getOptionalLong(rs, "run_id"),
      conversationId = getOptionalLong(rs, "conversation_id"),
      agentName = Option(rs.getString("agent_name")),
      summary = rs.getString("summary"),
      payload = Option(rs.getString("payload")),
      createdAt = createdAt,
    )
