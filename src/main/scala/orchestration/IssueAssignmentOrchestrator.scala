package orchestration

import java.time.Instant

import zio.*
import zio.json.*

import core.AIService
import db.{ ChatRepository, MigrationRepository, PersistenceError }
import models.*

trait IssueAssignmentOrchestrator:
  def assignIssue(issueId: Long, agentName: String): IO[PersistenceError, AgentIssue]

object IssueAssignmentOrchestrator:

  def assignIssue(issueId: Long, agentName: String): ZIO[IssueAssignmentOrchestrator, PersistenceError, AgentIssue] =
    ZIO.serviceWithZIO[IssueAssignmentOrchestrator](_.assignIssue(issueId, agentName))

  val live
    : ZLayer[ChatRepository & MigrationRepository & AIService & AIProviderConfig, Nothing, IssueAssignmentOrchestrator] =
    ZLayer.scoped {
      for
        chatRepository      <- ZIO.service[ChatRepository]
        migrationRepository <- ZIO.service[MigrationRepository]
        aiService           <- ZIO.service[AIService]
        defaultProviderCfg  <- ZIO.service[AIProviderConfig]
        queue               <- Queue.unbounded[AssignmentTask]
        service              =
          IssueAssignmentOrchestratorLive(
            chatRepository,
            migrationRepository,
            aiService,
            defaultProviderCfg,
            queue,
          )
        _                   <- service.processQueue.forever.forkScoped
      yield service
    }

final private case class AssignmentTask(
  assignmentId: Long,
  issueId: Long,
  agentName: String,
)

final private case class IssueAssignmentOrchestratorLive(
  chatRepository: ChatRepository,
  migrationRepository: MigrationRepository,
  aiService: AIService,
  defaultProviderCfg: AIProviderConfig,
  queue: Queue[AssignmentTask],
) extends IssueAssignmentOrchestrator:

  override def assignIssue(issueId: Long, agentName: String): IO[PersistenceError, AgentIssue] =
    for
      issue         <- chatRepository.getIssue(issueId).someOrFail(PersistenceError.NotFound("issue", issueId))
      assignments   <- chatRepository.listAssignmentsByIssue(issueId)
      existingActive =
        assignments.find(assignment =>
          assignment.agentName.equalsIgnoreCase(agentName) &&
          (assignment.status.equalsIgnoreCase("pending") || assignment.status.equalsIgnoreCase("processing"))
        )
      result        <- existingActive match
                         case Some(_) =>
                           ensureIssueConversation(issue, agentName)
                         case None    =>
                           for
                             now          <- Clock.instant
                             _            <- chatRepository.assignIssueToAgent(issueId, agentName)
                             conversation <- ensureIssueConversation(issue, agentName)
                             assignmentId <- chatRepository.createAssignment(
                                               AgentAssignment(
                                                 issueId = issueId,
                                                 agentName = agentName,
                                                 status = "pending",
                                                 assignedAt = now,
                                               )
                                             )
                             _            <- queue.offer(AssignmentTask(assignmentId, issueId, agentName))
                           yield conversation
    yield result

  private[orchestration] def processQueue: UIO[Unit] =
    queue.take.flatMap(processTask).catchAll(err => ZIO.logError(s"Issue assignment worker failed: $err"))

  private def processTask(task: AssignmentTask): IO[PersistenceError, Unit] =
    (for
      issue      <- chatRepository
                      .getIssue(task.issueId)
                      .someOrFail(PersistenceError.NotFound("issue", task.issueId))
      now        <- Clock.instant
      assignment <- chatRepository
                      .getAssignment(task.assignmentId)
                      .someOrFail(PersistenceError.NotFound("agent_assignment", task.assignmentId))
      _          <- chatRepository.updateAssignment(
                      assignment.copy(
                        status = "processing",
                        startedAt = Some(now),
                      )
                    )
      _          <- sendIssueContextToAgent(issue, task.agentName)
      doneAt     <- Clock.instant
      latest     <- chatRepository
                      .getAssignment(task.assignmentId)
                      .someOrFail(PersistenceError.NotFound("agent_assignment", task.assignmentId))
      _          <- chatRepository.updateAssignment(
                      latest.copy(
                        status = "completed",
                        completedAt = Some(doneAt),
                      )
                    )
    yield ()).catchAll { err =>
      for
        failedAt <- Clock.instant
        maybe    <- chatRepository.getAssignment(task.assignmentId)
        _        <- ZIO.foreachDiscard(maybe) { assignment =>
                      chatRepository.updateAssignment(
                        assignment.copy(
                          status = "failed",
                          completedAt = Some(failedAt),
                          executionLog = Some(err.toString),
                        )
                      )
                    }
        _        <- ZIO.logError(s"Issue assignment ${task.assignmentId} failed: $err")
      yield ()
    }

  private def sendIssueContextToAgent(issue: AgentIssue, agentName: String): IO[PersistenceError, Unit] =
    for
      conversationId <- ZIO
                          .fromOption(issue.conversationId)
                          .orElseFail(PersistenceError.QueryFailed("issue", "Issue is missing linked conversation"))
      runMetadata    <- issue.runId match
                          case Some(runId) => migrationRepository.getRun(runId)
                          case None        => ZIO.none
      prompt          = buildIssueAssignmentPrompt(issue, agentName, runMetadata)
      now            <- Clock.instant
      _              <- chatRepository.addMessage(
                          ConversationMessage(
                            conversationId = conversationId,
                            sender = "system",
                            senderType = SenderType.System,
                            content = prompt,
                            messageType = MessageType.Status,
                            createdAt = now,
                            updatedAt = now,
                          )
                        )
      settings       <- migrationRepository
                          .getAllSettings
                          .map(_.map(s => s.key -> s.value).toMap)
                          .catchAll(_ => ZIO.succeed(Map.empty[String, String]))
      aiConfig        = resolveAIProviderConfig(settings)
      aiResponse     <- aiService
                          .executeWithConfig(prompt, aiConfig)
                          .mapError(err => PersistenceError.QueryFailed("ai_service", err.message))
      now2           <- Clock.instant
      _              <- chatRepository.addMessage(
                          ConversationMessage(
                            conversationId = conversationId,
                            sender = "assistant",
                            senderType = SenderType.Assistant,
                            content = aiResponse.output,
                            messageType = MessageType.Text,
                            metadata = Some(aiResponse.metadata.toJson),
                            createdAt = now2,
                            updatedAt = now2,
                          )
                        )
      conv           <- chatRepository
                          .getConversation(conversationId)
                          .someOrFail(PersistenceError.NotFound("conversation", conversationId))
      _              <- chatRepository.updateConversation(conv.copy(updatedAt = now2))
    yield ()

  private def resolveAIProviderConfig(settings: Map[String, String]): AIProviderConfig =
    val provider = settings
      .get("ai.provider")
      .flatMap(parseProvider)
      .getOrElse(defaultProviderCfg.provider)

    AIProviderConfig.withDefaults(
      defaultProviderCfg.copy(
        provider = provider,
        model = settings.get("ai.model").filter(_.nonEmpty).getOrElse(defaultProviderCfg.model),
        baseUrl = settings
          .get("ai.baseUrl")
          .filter(_.nonEmpty)
          .orElse(AIProvider.defaultBaseUrl(provider))
          .orElse(defaultProviderCfg.baseUrl),
        apiKey = settings.get("ai.apiKey").filter(_.nonEmpty).orElse(defaultProviderCfg.apiKey),
        timeout = settings
          .get("ai.timeout")
          .flatMap(_.toLongOption)
          .map(Duration.fromSeconds)
          .getOrElse(defaultProviderCfg.timeout),
        maxRetries = settings
          .get("ai.maxRetries")
          .flatMap(_.toIntOption)
          .getOrElse(defaultProviderCfg.maxRetries),
        requestsPerMinute = settings
          .get("ai.requestsPerMinute")
          .flatMap(_.toIntOption)
          .getOrElse(defaultProviderCfg.requestsPerMinute),
        burstSize = settings.get("ai.burstSize").flatMap(_.toIntOption).getOrElse(defaultProviderCfg.burstSize),
        acquireTimeout = settings
          .get("ai.acquireTimeout")
          .flatMap(_.toLongOption)
          .map(Duration.fromSeconds)
          .getOrElse(defaultProviderCfg.acquireTimeout),
        temperature = settings.get("ai.temperature").flatMap(_.toDoubleOption).orElse(defaultProviderCfg.temperature),
        maxTokens = settings.get("ai.maxTokens").flatMap(_.toIntOption).orElse(defaultProviderCfg.maxTokens),
      )
    )

  private def parseProvider(value: String): Option[AIProvider] =
    value.trim match
      case "GeminiCli" => Some(AIProvider.GeminiCli)
      case "GeminiApi" => Some(AIProvider.GeminiApi)
      case "OpenAi"    => Some(AIProvider.OpenAi)
      case "Anthropic" => Some(AIProvider.Anthropic)
      case _           => None

  private def ensureIssueConversation(issue: AgentIssue, agentName: String): IO[PersistenceError, AgentIssue] =
    issue.conversationId match
      case Some(_) =>
        chatRepository
          .getIssue(issue.id.getOrElse(0L))
          .someOrFail(PersistenceError.NotFound("issue", issue.id.getOrElse(0L)))
      case None    =>
        for
          issueId <- ZIO
                       .fromOption(issue.id)
                       .orElseFail(PersistenceError.QueryFailed("issue", "Issue ID missing during assignment"))
          now     <- Clock.instant
          convId  <- chatRepository.createConversation(
                       ChatConversation(
                         runId = issue.runId,
                         title = s"Issue #$issueId: ${issue.title}",
                         description = Some("Auto-generated conversation from issue assignment"),
                         createdAt = now,
                         updatedAt = now,
                         createdBy = Some("system"),
                       )
                     )
          _       <- chatRepository.updateIssue(
                       issue.copy(
                         conversationId = Some(convId),
                         assignedAgent = Some(agentName),
                         assignedAt = Some(now),
                         status = IssueStatus.Assigned,
                         updatedAt = now,
                       )
                     )
          updated <- chatRepository
                       .getIssue(issueId)
                       .someOrFail(PersistenceError.NotFound("issue", issueId))
        yield updated

  private def buildIssueAssignmentPrompt(
    issue: AgentIssue,
    agentName: String,
    run: Option[db.MigrationRunRow],
  ): String =
    val runContext = run match
      case Some(value) =>
        s"""Run metadata:
           |- runId: ${value.id}
           |- sourceDir: ${value.sourceDir}
           |- outputDir: ${value.outputDir}
           |- status: ${value.status}
           |- currentPhase: ${value.currentPhase.getOrElse("n/a")}
           |""".stripMargin
      case None        => "Run metadata: not linked"

    s"""Issue assignment for agent: $agentName
       |
       |Issue title: ${issue.title}
       |Issue type: ${issue.issueType}
       |Priority: ${issue.priority}
       |Tags: ${issue.tags.getOrElse("none")}
       |Preferred agent: ${issue.preferredAgent.getOrElse("none")}
       |Context path: ${issue.contextPath.getOrElse("none")}
       |Source folder: ${issue.sourceFolder.getOrElse("none")}
       |
       |$runContext
       |
       |Markdown task:
       |${issue.description}
       |
       |Please execute this task and provide a concise implementation summary and next actions.
       |""".stripMargin
