package gateway.telegram

import zio.*

enum WorkflowNotifierError:
  case Telegram(error: TelegramClientError)

trait WorkflowNotifier:
  def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): IO[WorkflowNotifierError, Unit]

  def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): IO[WorkflowNotifierError, Unit]

object WorkflowNotifier:
  def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): ZIO[WorkflowNotifier, WorkflowNotifierError, Unit] =
    ZIO.serviceWithZIO[WorkflowNotifier](_.notifyCommand(chatId, replyToMessageId, command))

  def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): ZIO[WorkflowNotifier, WorkflowNotifierError, Unit] =
    ZIO.serviceWithZIO[WorkflowNotifier](_.notifyParseError(chatId, replyToMessageId, error))

  val noop: WorkflowNotifier =
    new WorkflowNotifier:
      override def notifyCommand(
        chatId: Long,
        replyToMessageId: Option[Long],
        command: BotCommand,
      ): IO[WorkflowNotifierError, Unit] =
        ZIO.unit

      override def notifyParseError(
        chatId: Long,
        replyToMessageId: Option[Long],
        error: CommandParseError,
      ): IO[WorkflowNotifierError, Unit] =
        ZIO.unit

  val live: ZLayer[TelegramClient, Nothing, WorkflowNotifier] =
    ZLayer.fromFunction(WorkflowNotifierLive.apply)

final case class WorkflowNotifierLive(client: TelegramClient) extends WorkflowNotifier:

  override def notifyCommand(
    chatId: Long,
    replyToMessageId: Option[Long],
    command: BotCommand,
  ): IO[WorkflowNotifierError, Unit] =
    sendText(chatId, commandMessage(command), replyToMessageId)

  override def notifyParseError(
    chatId: Long,
    replyToMessageId: Option[Long],
    error: CommandParseError,
  ): IO[WorkflowNotifierError, Unit] =
    sendText(chatId, parseErrorMessage(error), replyToMessageId)

  private def sendText(
    chatId: Long,
    text: String,
    replyToMessageId: Option[Long],
  ): IO[WorkflowNotifierError, Unit] =
    client
      .sendMessage(
        TelegramSendMessage(
          chat_id = chatId,
          text = text,
          reply_to_message_id = replyToMessageId,
        )
      )
      .mapError(WorkflowNotifierError.Telegram.apply)
      .unit

  private def commandMessage(command: BotCommand): String =
    command match
      case BotCommand.Start      => "Gateway bot is online."
      case BotCommand.Help       => "Use configured channels to run workflows."
      case BotCommand.ListRuns   => "Run listing via bot is disabled in gateway mode."
      case BotCommand.Status(id) => s"Run status via bot is disabled (run=$id)."
      case BotCommand.Logs(id)   => s"Run logs via bot is disabled (run=$id)."
      case BotCommand.Cancel(id) => s"Run cancel via bot is disabled (run=$id)."

  private def parseErrorMessage(error: CommandParseError): String =
    error match
      case CommandParseError.EmptyInput                       =>
        "Command is empty. Use /help."
      case CommandParseError.NotACommand(_)                   =>
        "Unsupported message. Use /help."
      case CommandParseError.UnknownCommand(command)          =>
        s"Unknown command '/$command'. Use /help."
      case CommandParseError.MissingParameter(command, param) =>
        s"Command '/$command' requires parameter '$param'."
      case CommandParseError.InvalidRunId(command, raw)       =>
        s"Invalid run id '$raw' for '/$command'."
