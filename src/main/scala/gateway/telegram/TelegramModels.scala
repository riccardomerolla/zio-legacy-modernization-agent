package gateway.telegram

import zio.json.*

case class TelegramUser(
  id: Long,
  is_bot: Boolean,
  first_name: String,
  username: Option[String] = None,
) derives JsonCodec

case class TelegramChat(
  id: Long,
  `type`: String,
  title: Option[String] = None,
  username: Option[String] = None,
) derives JsonCodec

case class TelegramMessage(
  message_id: Long,
  date: Long,
  chat: TelegramChat,
  text: Option[String] = None,
  from: Option[TelegramUser] = None,
) derives JsonCodec

case class TelegramCallbackQuery(
  id: String,
  from: TelegramUser,
  message: Option[TelegramMessage] = None,
  data: Option[String] = None,
) derives JsonCodec

case class TelegramUpdate(
  update_id: Long,
  message: Option[TelegramMessage] = None,
  edited_message: Option[TelegramMessage] = None,
  callback_query: Option[TelegramCallbackQuery] = None,
) derives JsonCodec

case class TelegramInlineKeyboardButton(
  text: String,
  callback_data: String,
) derives JsonCodec

case class TelegramInlineKeyboardMarkup(
  inline_keyboard: List[List[TelegramInlineKeyboardButton]]
) derives JsonCodec

case class TelegramSendMessage(
  chat_id: Long,
  text: String,
  parse_mode: Option[String] = None,
  disable_web_page_preview: Option[Boolean] = None,
  reply_to_message_id: Option[Long] = None,
  reply_markup: Option[TelegramInlineKeyboardMarkup] = None,
) derives JsonCodec

case class TelegramApiErrorParameters(
  retry_after: Option[Int] = None
) derives JsonCodec

case class TelegramApiResponse[T](
  ok: Boolean,
  result: Option[T] = None,
  description: Option[String] = None,
  error_code: Option[Int] = None,
  parameters: Option[TelegramApiErrorParameters] = None,
)

object TelegramApiResponse:
  given [T: JsonDecoder]: JsonDecoder[TelegramApiResponse[T]] = DeriveJsonDecoder.gen[TelegramApiResponse[T]]
