package conversation.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ ConversationId, MessageId, TaskRunId }

enum ConversationState derives JsonCodec, Schema:
  case Active(startedAt: Instant)
  case Closed(startedAt: Instant, closedAt: Instant)

enum ChannelInfo derives JsonCodec, Schema:
  case Telegram(channelName: String)
  case Web(sessionId: String)
  case Internal

final case class ConversationMessage(
  id: MessageId,
  sender: String,
  content: String,
  messageType: String,
  createdAt: Instant,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec, Schema

final case class Conversation(
  id: ConversationId,
  channel: ChannelInfo,
  state: ConversationState,
  title: String,
  description: String,
  messages: List[ConversationMessage],
  runId: Option[TaskRunId],
  createdBy: Option[String],
) derives JsonCodec, Schema
