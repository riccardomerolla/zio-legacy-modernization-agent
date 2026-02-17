package web.controllers

import java.time.Instant

import zio.*
import zio.http.*

import db.ActivityRepository
import models.ActivityEventType
import web.ErrorHandlingMiddleware
import web.views.ActivityView

trait ActivityController:
  def routes: Routes[Any, Response]

object ActivityController:

  def routes: ZIO[ActivityController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ActivityController](_.routes)

  val live: ZLayer[ActivityRepository, Nothing, ActivityController] =
    ZLayer.fromFunction(ActivityControllerLive.apply)

final case class ActivityControllerLive(
  activityRepository: ActivityRepository
) extends ActivityController:

  override val routes: Routes[Any, Response] = Routes(
    // Full page
    Method.GET / "activity"                                    -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        activityRepository.listEvents(limit = 50).map { events =>
          Response.html(ActivityView.timeline(events))
        }
      }
    },
    // JSON API for filtered events
    Method.GET / "api" / "activity" / "events"                 -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val eventType = req.queryParam("type").flatMap(parseEventType)
        val since     = req.queryParam("since").flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
        val limit     = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(50)
        activityRepository.listEvents(eventType, since, limit).map { events =>
          Response.html(ActivityView.eventsFragment(events))
        }
      }
    },
    // Fragment endpoint for latest single event card
    Method.GET / "api" / "activity" / "events" / "latest-card" -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        activityRepository.listEvents(limit = 1).map {
          case event :: _ => Response.html(ActivityView.singleEventFragment(event))
          case Nil        => Response.html("")
        }
      }
    },
  )

  private def parseEventType(raw: String): Option[ActivityEventType] = raw.toLowerCase match
    case "run_started"    => Some(ActivityEventType.RunStarted)
    case "run_completed"  => Some(ActivityEventType.RunCompleted)
    case "run_failed"     => Some(ActivityEventType.RunFailed)
    case "agent_assigned" => Some(ActivityEventType.AgentAssigned)
    case "message_sent"   => Some(ActivityEventType.MessageSent)
    case "config_changed" => Some(ActivityEventType.ConfigChanged)
    case _                => None
