package gateway.telegram

import gateway.models.NormalizedMessage

final case class FormattedTelegramResponse(
  text: String,
  parseMode: Option[String],
  replyMarkup: Option[TelegramInlineKeyboardMarkup],
  continuationToken: Option[String],
  remaining: Option[String],
)

object ResponseFormatter:
  def format(message: NormalizedMessage): FormattedTelegramResponse =
    val baseText        = formatStructured(message.content, message.metadata)
    val withAttachments = appendAttachments(baseText, message.metadata)
    val normalized      = withAttachments.trim

    FormattedTelegramResponse(
      text = normalized,
      parseMode = parseModeFor(normalized, message.metadata),
      replyMarkup = None,
      continuationToken = None,
      remaining = None,
    )

  def formatContinuation(token: String, remaining: String): FormattedTelegramResponse =
    val normalized = remaining.trim
    FormattedTelegramResponse(
      text = normalized,
      parseMode = parseModeFor(normalized, Map.empty),
      replyMarkup = None,
      continuationToken = None,
      remaining = None,
    )

  private def parseModeFor(content: String, metadata: Map[String, String]): Option[String] =
    metadata.get("telegram.parse_mode").orElse {
      if content.contains("```") then Some("Markdown") else None
    }

  private def formatStructured(content: String, metadata: Map[String, String]): String =
    metadata.get("content.type").map(_.trim.toLowerCase) match
      case Some("table") => toMarkdownTable(content)
      case Some("list")  => toBulletList(content)
      case _             =>
        if looksLikeCsvTable(content) then toMarkdownTable(content)
        else if looksLikeJson(content) then s"```json\n${content.trim}\n```"
        else if looksLikeCode(content) && !content.contains("```") then s"```\n${content.trim}\n```"
        else content

  private def appendAttachments(content: String, metadata: Map[String, String]): String =
    val attachments = metadata.values.filter(value =>
      value.toLowerCase.endsWith(".pdf") || value.toLowerCase.endsWith(".zip")
    ).toList.distinct

    if attachments.isEmpty then content
    else
      val lines = attachments.map(path => s"- $path").mkString("\n")
      s"$content\n\nGenerated attachments:\n$lines"

  private def looksLikeJson(content: String): Boolean =
    val trimmed = content.trim
    (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))

  private def looksLikeCode(content: String): Boolean =
    val markers = List("{", "}", ";", "class ", "def ", "public ", "private ", "return ")
    val text    = content.toLowerCase
    markers.count(text.contains) >= 2

  private def looksLikeCsvTable(content: String): Boolean =
    val lines = content.linesIterator.toList.map(_.trim).filter(_.nonEmpty)
    lines.length >= 2 && lines.forall(_.contains(","))

  private def toMarkdownTable(content: String): String =
    val rows = content.linesIterator.toList.map(_.trim).filter(_.nonEmpty).map(_.split(",").toList.map(_.trim))
    rows match
      case header :: tail if header.nonEmpty =>
        val headerLine = s"| ${header.mkString(" | ")} |"
        val divider    = s"| ${header.map(_ => "---").mkString(" | ")} |"
        val body       = tail.map(row => s"| ${row.mkString(" | ")} |").mkString("\n")
        s"$headerLine\n$divider\n$body"
      case _                                 => content

  private def toBulletList(content: String): String =
    content
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(line => if line.startsWith("-") || line.startsWith("*") then line else s"- $line")
      .mkString("\n")
