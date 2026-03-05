package workspace.control

import zio.test.*

object WorkspaceRunServicePathSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("WorkspaceRunServicePathSpec")(
    test("worktreePath uses Windows LOCALAPPDATA root when OS is Windows") {
      val path = WorkspaceRunServiceLive.worktreePath(
        workspaceName = "my-repo",
        runId = "run-1",
        userHome = "C:/Users/alice",
        localAppData = Some("C:/Users/alice/AppData/Local"),
        osName = "Windows 11",
      )
      assertTrue(path.replace('\\', '/').endsWith("/AppData/Local/llm4zio/agent-worktrees/my-repo/run-1"))
    },
    test("worktreePath uses ~/.cache root when OS is Unix-like") {
      val path = WorkspaceRunServiceLive.worktreePath(
        workspaceName = "my-repo",
        runId = "run-1",
        userHome = "/home/alice",
        localAppData = None,
        osName = "Linux",
      )
      assertTrue(path == "/home/alice/.cache/agent-worktrees/my-repo/run-1")
    },
  )
