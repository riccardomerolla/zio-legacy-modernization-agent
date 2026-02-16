package gateway.telegram

case class InlineKeyboardAction(
  action: String,
  runId: Long,
  paused: Boolean,
)

object InlineKeyboards:
  private val Prefix = "wf"

  def workflowControls(
    runId: Long,
    paused: Boolean = false,
  ): TelegramInlineKeyboardMarkup =
    TelegramInlineKeyboardMarkup(
      inline_keyboard = List(
        List(
          TelegramInlineKeyboardButton(
            text = "View Details",
            callback_data = encode("details", runId, paused),
          ),
          TelegramInlineKeyboardButton(
            text = if paused then "Resume" else "Pause",
            callback_data = encode("toggle", runId, paused),
          ),
        ),
        List(
          TelegramInlineKeyboardButton(
            text = "Cancel",
            callback_data = encode("cancel", runId, paused),
          ),
          TelegramInlineKeyboardButton(
            text = "Retry",
            callback_data = encode("retry", runId, paused),
          ),
        ),
      )
    )

  def parseCallbackData(raw: String): Either[String, InlineKeyboardAction] =
    val parts = raw.trim.split(":").toList
    parts match
      case Prefix :: action :: runIdRaw :: pausedRaw :: Nil =>
        for
          runId  <- runIdRaw.toLongOption.filter(_ > 0L).toRight(s"invalid run id: $runIdRaw")
          paused <- pausedRaw.toLowerCase match
                      case "paused"  => Right(true)
                      case "running" => Right(false)
                      case other     => Left(s"invalid keyboard state: $other")
        yield InlineKeyboardAction(action = action, runId = runId, paused = paused)
      case _                                                =>
        Left(s"invalid callback payload: $raw")

  private def encode(action: String, runId: Long, paused: Boolean): String =
    s"$Prefix:$action:$runId:${if paused then "paused" else "running"}"
