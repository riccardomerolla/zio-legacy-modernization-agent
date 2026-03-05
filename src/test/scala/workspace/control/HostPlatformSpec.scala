package workspace.control

import zio.test.*

object HostPlatformSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("HostPlatformSpec")(
    test("detects Windows OS names") {
      assertTrue(
        HostPlatform.isWindows("Windows 11"),
        HostPlatform.isWindows("windows server 2022"),
        !HostPlatform.isWindows("Linux"),
        !HostPlatform.isWindows("Mac OS X"),
      )
    },
    test("builds LOCALAPPDATA worktree root on Windows") {
      val path = HostPlatform.defaultWorktreeRoot(
        userHome = "C:/Users/alice",
        localAppData = Some("C:/Users/alice/AppData/Local"),
        osName = "Windows 11",
      )
      assertTrue(path.toString.replace('\\', '/').endsWith("/AppData/Local/llm4zio/agent-worktrees"))
    },
    test("falls back to user-home AppData when LOCALAPPDATA missing") {
      val path = HostPlatform.defaultWorktreeRoot(
        userHome = "C:/Users/alice",
        localAppData = None,
        osName = "Windows 11",
      )
      assertTrue(path.toString.replace('\\', '/').endsWith("/Users/alice/AppData/Local/llm4zio/agent-worktrees"))
    },
    test("builds .cache worktree root on Unix") {
      val path = HostPlatform.defaultWorktreeRoot(
        userHome = "/home/alice",
        localAppData = None,
        osName = "Linux",
      )
      assertTrue(path.toString == "/home/alice/.cache/agent-worktrees")
    },
  )
