package models

import java.time.Instant

import zio.json.*

enum MessageType derives JsonCodec:
  case Text, Code, Error, Status

enum SenderType derives JsonCodec:
  case User, Assistant, System

case class ConversationMessage(
  id: Option[Long] = None,
  conversationId: Long,
  sender: String,
  senderType: SenderType,
  content: String,
  messageType: MessageType = MessageType.Text,
  metadata: Option[String] = None,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

case class ChatConversation(
  id: Option[Long] = None,
  runId: Option[Long] = None,
  title: String,
  description: Option[String] = None,
  status: String = "active",
  messages: List[ConversationMessage] = List.empty,
  createdAt: Instant,
  updatedAt: Instant,
  createdBy: Option[String] = None,
) derives JsonCodec

enum IssuePriority derives JsonCodec:
  case Low, Medium, High, Critical

enum IssueStatus derives JsonCodec:
  case Open, Assigned, InProgress, Completed, Failed, Skipped

case class AgentIssue(
  id: Option[Long] = None,
  runId: Option[Long] = None,
  conversationId: Option[Long] = None,
  title: String,
  description: String,
  issueType: String,
  tags: Option[String] = None,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  priority: IssuePriority = IssuePriority.Medium,
  status: IssueStatus = IssueStatus.Open,
  assignedAgent: Option[String] = None,
  assignedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  errorMessage: Option[String] = None,
  resultData: Option[String] = None,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

case class AgentAssignment(
  id: Option[Long] = None,
  issueId: Long,
  agentName: String,
  status: String = "pending",
  assignedAt: Instant,
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  executionLog: Option[String] = None,
  result: Option[String] = None,
) derives JsonCodec

// Request/Response DTOs
case class ConversationMessageRequest(
  content: String,
  messageType: MessageType = MessageType.Text,
  metadata: Option[String] = None,
) derives JsonCodec

case class ChatConversationCreateRequest(
  title: String,
  description: Option[String] = None,
  runId: Option[Long] = None,
) derives JsonCodec

case class AgentIssueCreateRequest(
  runId: Option[Long] = None,
  title: String,
  description: String,
  issueType: String,
  tags: Option[String] = None,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  priority: IssuePriority = IssuePriority.Medium,
  conversationId: Option[Long] = None,
) derives JsonCodec

case class AssignIssueRequest(
  agentName: String
) derives JsonCodec
