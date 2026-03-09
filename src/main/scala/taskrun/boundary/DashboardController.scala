package taskrun.boundary

import zio.*
import zio.http.*

import db.{ PersistenceError, TaskRepository }
import issues.entity.{ IssueFilter, IssueRepository, IssueStateTag }
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }

trait DashboardController:
  def routes: Routes[Any, Response]

object DashboardController:

  def routes: ZIO[DashboardController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DashboardController](_.routes)

  val live: ZLayer[TaskRepository & IssueRepository, Nothing, DashboardController] =
    ZLayer {
      for
        repository      <- ZIO.service[TaskRepository]
        issueRepository <- ZIO.service[IssueRepository]
      yield DashboardControllerLive(
        repository = repository,
        issueRepository = issueRepository,
      )
    }

final case class DashboardControllerLive(
  repository: TaskRepository,
  issueRepository: IssueRepository,
) extends DashboardController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / Root                       -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        for
          issues          <- issueRepository.list(IssueFilter(limit = Int.MaxValue)).mapError(mapIssueRepoError)
          stateCountByType = issues
                               .groupBy(issue => IssueStateTag.fromState(issue.state))
                               .view
                               .mapValues(_.size)
                               .toMap
          summary          = shared.web.CommandCenterView.PipelineSummary(
                               open = stateCountByType.getOrElse(IssueStateTag.Open, 0),
                               claimed = stateCountByType.getOrElse(IssueStateTag.Assigned, 0),
                               running = stateCountByType.getOrElse(IssueStateTag.InProgress, 0),
                               completed = stateCountByType.getOrElse(IssueStateTag.Completed, 0),
                               failed = stateCountByType.getOrElse(IssueStateTag.Failed, 0),
                             )
        yield html(HtmlViews.dashboard(summary))
      }
    },
    Method.GET / "api" / "tasks" / "recent" -> handler {
      ErrorHandlingMiddleware.fromPersistence {
        repository.listRuns(offset = 0, limit = 10).map(runs => html(HtmlViews.recentRunsFragment(runs)))
      }
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def mapIssueRepoError(error: shared.errors.PersistenceError): PersistenceError =
    error match
      case shared.errors.PersistenceError.NotFound(entity, id)             =>
        PersistenceError.QueryFailed(entity, s"Not found: $id")
      case shared.errors.PersistenceError.QueryFailed(op, cause)           =>
        PersistenceError.QueryFailed(op, cause)
      case shared.errors.PersistenceError.SerializationFailed(entity, err) =>
        PersistenceError.QueryFailed(entity, err)
      case shared.errors.PersistenceError.StoreUnavailable(msg)            =>
        PersistenceError.QueryFailed("issue_store", msg)
