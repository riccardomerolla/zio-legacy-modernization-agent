package web.views

import models.{ ChatConversation, ConversationMessage, SenderType }
import scalatags.Text.all.*

object ChatView:

  def dashboard(conversations: List[ChatConversation]): String =
    Layout.page("Chat — COBOL Modernization", "/chat")(
      div(cls := "mb-6")(
        div(cls := "flex items-center justify-between")(
          h1(cls := "text-2xl font-bold text-white")("Chat Interface"),
          form(method := "post", action := "/chat", cls := "flex items-center gap-2")(
            input(
              name        := "title",
              placeholder := "New chat title",
              cls         := "bg-white/10 border border-white/20 rounded-lg px-3 py-2 text-white text-sm placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500",
              required,
            ),
            button(
              `type` := "submit",
              cls    := "inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg font-medium transition-colors",
            )("+ New Chat"),
          ),
        ),
        p(cls := "text-gray-400 text-sm mt-2")("Manage conversations with the AI service"),
      ),
      if conversations.isEmpty then
        div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-12 text-center")(
          p(cls := "text-gray-400 mb-4")("No conversations yet"),
          a(
            href := "/chat",
            cls  := "inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg",
          )("Start New Conversation"),
        )
      else
        div(cls := "grid grid-cols-1 gap-4")(
          conversations.map { conv =>
            conversationCard(conv)
          }
        ),
    )

  def detail(conversation: ChatConversation): String =
    val issuesHref = conversation.runId match
      case Some(runId) => s"/issues?run_id=$runId"
      case None        => "/issues"

    Layout.page(s"Chat — ${conversation.title}", s"/chat/${conversation.id.get}")(
      div(cls := "flex flex-col min-h-[calc(100vh-9rem)]")(
        div(cls := "flex flex-col lg:flex-row lg:items-start lg:justify-between gap-4 mb-4")(
          div(cls := "min-w-0")(
            a(
              href := "/chat",
              cls  := "text-indigo-400 hover:text-indigo-300 text-sm font-medium mb-3 inline-flex items-center gap-2",
            )("← Back to Chats"),
            h1(cls := "text-2xl font-bold text-white")(conversation.title),
            if conversation.description.isDefined then
              p(cls := "text-gray-400 text-sm mt-2")(conversation.description.get)
            else (),
          ),
          div(cls := "inline-flex flex-wrap items-center gap-2 text-xs self-start lg:justify-end")(
            span(
              cls := "inline-flex items-center rounded-md bg-white/5 ring-1 ring-white/10 px-3 py-1.5 text-gray-200"
            )(
              span(cls := "text-gray-400 mr-1")("Status:"),
              span(cls := "font-semibold capitalize")(conversation.status),
            ),
            span(
              cls := "inline-flex items-center rounded-md bg-white/5 ring-1 ring-white/10 px-3 py-1.5 text-gray-200"
            )(
              span(cls := "text-gray-400 mr-1")("Messages:"),
              span(cls := "font-semibold")(conversation.messages.length.toString),
            ),
            span(
              cls := "inline-flex items-center rounded-md bg-white/5 ring-1 ring-white/10 px-3 py-1.5 text-gray-200"
            )(
              span(cls := "text-gray-400 mr-1")("Created:"),
              span(cls := "font-semibold")(conversation.createdAt.toString.take(19)),
            ),
            a(
              href := issuesHref,
              cls  := "inline-flex items-center rounded-md bg-indigo-600 hover:bg-indigo-700 text-white px-3 py-1.5 font-semibold transition-colors",
            )("View Related Issues"),
          ),
        ),
        div(cls := "flex-1 min-h-0 bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden flex flex-col")(
          // Messages list
          div(
            id                 := s"messages-${conversation.id.get}",
            cls                := "flex-1 min-h-0 overflow-y-auto p-6 space-y-4",
            attr("hx-get")     := s"/chat/${conversation.id.get}/messages",
            attr("hx-trigger") := "load, every 2s",
            attr("hx-swap")    := "innerHTML",
          )(
            raw(messagesFragment(conversation.messages))
          )
        ),
        // Sticky composer
        div(
          cls := "sticky bottom-0 mt-3 rounded-lg bg-gray-900/95 ring-1 ring-white/10 p-3 backdrop-blur"
        )(
          form(
            method                       := "post",
            action                       := s"/chat/${conversation.id.get}/messages",
            attr("hx-post")              := s"/chat/${conversation.id.get}/messages",
            attr("hx-target")            := s"#messages-${conversation.id.get}",
            attr("hx-swap")              := "innerHTML",
            attr("hx-disabled-elt")      := "button[type='submit']",
            attr("hx-on::after-request") := "this.reset()",
            attr("onsubmit")             := "const i=this.querySelector(\"input[name='content']\");if(i){setTimeout(()=>{i.value='';i.focus();},0);}",
            cls                          := "flex gap-2",
          )(
            input(`type`  := "hidden", name := "fragment", value := "true"),
            input(
              name        := "content",
              placeholder := "Type your message...",
              cls         := "flex-1 bg-white/10 border border-white/20 rounded-lg px-4 py-2 text-white placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500",
              required,
            ),
            button(
              `type` := "submit",
              cls    := "px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg font-medium transition-colors",
            )("Send"),
          )
        ),
      )
    )

  def messagesFragment(messages: List[ConversationMessage]): String =
    div(cls := "space-y-4 text-gray-100")(
      messages.map(messageCard)
    ).render

  private def messageCard(message: ConversationMessage): Frag =
    val isUser                                           = message.senderType == SenderType.User
    val (containerClasses, bubbleClasses, senderClasses) =
      if isUser then
        (
          "flex justify-end",
          "max-w-[85%] lg:max-w-[72%] rounded-2xl rounded-br-md border border-indigo-300/20 bg-indigo-500/90 px-4 py-3 shadow-lg shadow-indigo-900/20",
          "text-indigo-100",
        )
      else
        (
          "flex justify-start",
          "max-w-[85%] lg:max-w-[72%] rounded-2xl rounded-bl-md border border-white/15 bg-slate-800/80 px-4 py-3 shadow-lg shadow-black/20",
          "text-slate-300",
        )

    div(cls := containerClasses)(
      div(cls := bubbleClasses)(
        div(cls := s"text-xs font-semibold mb-2 $senderClasses")(
          message.sender
        ),
        if !isUser && looksLikeMarkdown(message.content) then
          div(
            cls := "text-sm leading-6 text-gray-50 [&_p]:my-2 [&_ul]:my-2 [&_ol]:my-2 [&_h1]:mt-2 [&_h2]:mt-2 [&_h3]:mt-2"
          )(
            IssuesView.markdownFragment(message.content)
          )
        else
          pre(
            cls := "whitespace-pre-wrap break-words text-sm leading-6 text-gray-50 font-sans m-0"
          )(
            message.content
          ),
      )
    )

  private def looksLikeMarkdown(text: String): Boolean =
    val lines = text.split("\n").toList
    lines.exists(_.trim.startsWith("```")) ||
    lines.exists(line => line.trim.matches("^#{1,6}\\s+.*$")) ||
    lines.exists(line => line.trim.matches("^[-*+]\\s+.*$")) ||
    lines.exists(line => line.trim.matches("^\\d+\\.\\s+.*$")) ||
    lines.exists(_.trim.startsWith(">")) ||
    text.contains("**") ||
    text.contains("`") ||
    text.matches("(?s).*\\[[^\\]]+\\]\\((https?://|/|#)[^)]+\\).*")

  private def conversationCard(conv: ChatConversation) =
    a(
      href := s"/chat/${conv.id.get}",
      cls  := "bg-white/5 hover:bg-white/10 ring-1 ring-white/10 rounded-lg p-4 transition-all hover:ring-indigo-500/50 cursor-pointer block",
    )(
      div(cls := "flex items-start justify-between mb-2")(
        h3(cls := "text-lg font-semibold text-white max-w-xs truncate")(conv.title),
        span(
          cls := s"inline-flex items-center rounded-md px-2 py-1 text-xs font-medium ${
              if conv.status == "active" then
                "bg-green-500/10 text-green-400 ring-1 ring-inset ring-green-500/20"
              else
                "bg-gray-500/10 text-gray-400 ring-1 ring-inset ring-gray-500/20"
            }"
        )(
          span(
            cls   := "w-1.5 h-1.5 rounded-full mr-1.5",
            style := s"background-color: ${if conv.status == "active" then "#10b981" else "#6b7280"}",
          ),
          conv.status,
        ),
      ),
      if conv.description.isDefined then
        div(cls := "pt-2")(
          p(cls := "text-gray-400 text-sm mb-3 truncate")(conv.description.get)
        )
      else (),
      div(cls := "flex items-center justify-between text-xs text-gray-500")(
        span(
          if conv.messages.nonEmpty then
            s"${conv.messages.length} message${if conv.messages.length != 1 then "s" else ""}"
          else "No messages"
        ),
        span(cls := "text-right")(
          conv.updatedAt.toString.take(10)
        ),
      ),
    )
