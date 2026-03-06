package shared.web

import zio.test.*

import issues.entity.api.AgentIssueView

object BoardFilterBarSpec extends ZIOSpecDefault:

  private val emptyIssues: List[AgentIssueView]       = Nil
  private val emptyWorkspaces: List[(String, String)] = Nil

  def spec: Spec[Any, Nothing] =
    suite("boardFilterBar")(
      test("filter bar Apply button is rounded-full") {
        val html = IssuesView.board(
          issues = emptyIssues,
          workspaces = emptyWorkspaces,
          workspaceFilter = None,
          agentFilter = None,
          priorityFilter = None,
          tagFilter = None,
          query = None,
          hasProofFilter = None,
        )
        // Apply button should use rounded-full, not rounded-md
        assertTrue(html.contains("rounded-full bg-indigo-500"))
      },
      test("filter bar has-proof toggle is a rounded-full pill label") {
        val html = IssuesView.board(
          issues = emptyIssues,
          workspaces = emptyWorkspaces,
          workspaceFilter = None,
          agentFilter = None,
          priorityFilter = None,
          tagFilter = None,
          query = None,
          hasProofFilter = None,
        )
        assertTrue(html.contains("rounded-full border"))
      },
      test("filter bar has-proof toggle highlighted when hasProofFilter=true") {
        val html = IssuesView.board(
          issues = emptyIssues,
          workspaces = emptyWorkspaces,
          workspaceFilter = None,
          agentFilter = None,
          priorityFilter = None,
          tagFilter = None,
          query = None,
          hasProofFilter = Some(true),
        )
        assertTrue(html.contains("bg-indigo-500/20"))
      },
    )
