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
      div(cls := "mb-6")(
        a(
          href := "/chat",
          cls  := "text-indigo-400 hover:text-indigo-300 text-sm font-medium mb-4 inline-flex items-center gap-2",
        )("← Back to Chats"),
        h1(cls := "text-2xl font-bold text-white mt-2")(conversation.title),
        if conversation.description.isDefined then
          p(cls := "text-gray-400 text-sm mt-2")(conversation.description.get)
        else (),
      ),
      div(cls := "grid grid-cols-1 lg:grid-cols-3 gap-6")(
        // Chat messages area
        div(cls := "lg:col-span-2")(
          div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden flex flex-col h-96")(
            // Messages list
            div(
              id                  := s"messages-${conversation.id.get}",
              cls                 := "flex-1 overflow-y-auto p-6 space-y-4",
              attr("hx-get")      := s"/chat/${conversation.id.get}/messages",
              attr("hx-trigger")  := "load, every 2s",
              attr("hx-swap")     := "innerHTML",
            )(
              raw(messagesFragment(conversation.messages))
            ),
            // Message input
            div(cls := "border-t border-white/10 p-4 bg-white/2")(
              form(
                method                           := "post",
                action                           := s"/chat/${conversation.id.get}/messages",
                attr("hx-post")                  := s"/chat/${conversation.id.get}/messages",
                attr("hx-target")                := s"#messages-${conversation.id.get}",
                attr("hx-swap")                  := "innerHTML",
                attr("hx-disabled-elt")          := "button[type='submit']",
                attr("hx-on::after-request")     := "this.reset()",
                cls                              := "flex gap-2",
              )(
                input(`type` := "hidden", name := "fragment", value := "true"),
                input(
                  name        := "content",
                  placeholder := "Type your message...",
                  cls         := "flex-1 bg-white/10 border border-white/20 rounded-lg px-4 py-2 text-white placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500",
                  required,
                ),
                button(
                  `type` := "submit",
                  cls := "px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg font-medium transition-colors"
                )("Send"),
              )
            ),
          )
        ),
        // Sidebar with details
        div(cls := "lg:col-span-1")(
          div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 space-y-4")(
            h3(cls := "text-lg font-semibold text-white")("Conversation Details"),
            div(
              div(cls := "text-sm text-gray-400")("Status"),
              p(cls := "text-white font-medium capitalize")(conversation.status),
            ),
            div(
              div(cls := "text-sm text-gray-400")("Created"),
              p(cls := "text-white font-medium text-sm")(conversation.createdAt.toString),
            ),
            if conversation.messages.nonEmpty then
              div(
                div(cls := "text-sm text-gray-400")("Messages"),
                p(cls := "text-white font-medium")(conversation.messages.length.toString),
              )
            else (),
            div(cls := "pt-4 border-t border-white/10")(
              a(
                href := issuesHref,
                cls  := "inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-sm font-medium transition-colors w-full justify-center",
              )("View Related Issues")
            ),
          )
        ),
      ),
    )

  def messagesFragment(messages: List[ConversationMessage]): String =
    div(cls := "space-y-4 text-gray-100")(
      messages.map(messageCard)
    ).render

  private def messageCard(message: ConversationMessage): Frag =
    val isUser = message.senderType == SenderType.User
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
        pre(
          cls := "whitespace-pre-wrap break-words text-sm leading-6 text-gray-50 font-sans m-0"
        )(
          message.content
        ),
      )
    )

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
