package workspace.control

import zio.*
import zio.test.*

object CliAgentRunnerSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("CliAgentRunnerSpec")(
    test("buildArgv for echo agent returns [echo, prompt]") {
      val argv = CliAgentRunner.buildArgv("echo", "hello world", "/tmp/wt")
      assertTrue(argv == List("echo", "hello world"))
    },
    test("buildArgv for gemini-cli returns correct args") {
      val argv = CliAgentRunner.buildArgv("gemini-cli", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("gemini", "-p", "fix the bug", "/tmp/wt"))
    },
    test("buildArgv for opencode returns correct args") {
      val argv = CliAgentRunner.buildArgv("opencode", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("opencode", "run", "--prompt", "fix the bug", "/tmp/wt"))
    },
    test("buildArgv for claude returns correct args") {
      val argv = CliAgentRunner.buildArgv("claude", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("claude", "--print", "fix the bug"))
    },
    test("buildArgv for codex returns correct args") {
      val argv = CliAgentRunner.buildArgv("codex", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("codex", "fix the bug"))
    },
    test("buildArgv for copilot returns correct args") {
      val argv = CliAgentRunner.buildArgv("copilot", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("gh", "copilot", "suggest", "-t", "shell", "fix the bug"))
    },
    test("buildArgv for unknown agent passes prompt as single arg") {
      val argv = CliAgentRunner.buildArgv("my-agent", "do something", "/tmp/wt")
      assertTrue(argv == List("my-agent", "do something"))
    },
    test("runProcess with echo collects output line and returns exit 0") {
      for
        result           <- CliAgentRunner.runProcess(List("echo", "line one"), cwd = "/tmp")
        (lines, exitCode) = result
      yield assertTrue(exitCode == 0 && lines.exists(_.contains("line one")))
    },
    test("runProcess with false command returns non-zero exit code") {
      for
        result       <- CliAgentRunner.runProcess(List("sh", "-c", "exit 1"), cwd = "/tmp")
        (_, exitCode) = result
      yield assertTrue(exitCode != 0)
    },
  )
