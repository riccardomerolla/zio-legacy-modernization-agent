package conversation.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.ConversationId

sealed trait ConversationEvent derives JsonCodec, Schema:
  def conversationId: ConversationId
  def occurredAt: Instant

object ConversationEvent:
  final case class Created(
    conversationId: ConversationId,
    channel: ChannelInfo,
    title: String,
    description: String,
    occurredAt: Instant,
  ) extends ConversationEvent

  final case class MessageAdded(
    conversationId: ConversationId,
    message: ConversationMessage,
    occurredAt: Instant,
  ) extends ConversationEvent

  final case class Closed(
    conversationId: ConversationId,
    closedAt: Instant,
    occurredAt: Instant,
  ) extends ConversationEvent

  final case class Reopened(
    conversationId: ConversationId,
    reopenedAt: Instant,
    occurredAt: Instant,
  ) extends ConversationEvent
