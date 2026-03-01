package workspace.control
import java.nio.file.Paths

import zio.*

import workspace.entity.RunMode

object CliAgentRunner:

  /** Map CLI tool name → argv list for host execution. `cliTool` is the binary to invoke (e.g. "claude", "gemini",
    * "opencode"). All agents use the process `cwd` (set to `worktreePath`) for directory context; the path is not
    * passed as a positional argument unless the tool requires it.
    */
  private def buildArgvForHost(cliTool: String, prompt: String): List[String] =
    cliTool match
      case "gemini"   => List("gemini", "-p", prompt)
      case "opencode" => List("opencode", "run", "--prompt", prompt)
      case "claude"   => List("claude", "--print", prompt)
      case "codex"    => List("codex", prompt)
      case "copilot"  => List("gh", "copilot", "suggest", "-t", "shell", prompt)
      case other      => List(other, prompt)

  /** Build the full argv list, wrapping in `docker run` when `runMode` is `RunMode.Docker`. `cliTool` is the CLI binary
    * to invoke (from the workspace's `cliTool` setting).
    */
  def buildArgv(
    cliTool: String,
    prompt: String,
    worktreePath: String,
    runMode: RunMode = RunMode.Host,
  ): List[String] =
    runMode match
      case RunMode.Host                                             =>
        buildArgvForHost(cliTool, prompt)
      case RunMode.Docker(image, extraArgs, mountWorktree, network) =>
        val innerArgv    = buildArgvForHost(cliTool, prompt)
        val mountFlags   = if mountWorktree then List("-v", s"$worktreePath:/workspace", "--workdir", "/workspace")
        else List.empty
        val networkFlags = network.map(n => List("--network", n)).getOrElse(List.empty)
        List("docker", "run", "--rm") ++ mountFlags ++ networkFlags ++ extraArgs ++ List(image) ++ innerArgv

  /** Run argv as a subprocess with `cwd` as working directory. Returns (stdout+stderr lines, exit code). Merges stderr
    * into stdout via redirectErrorStream. Runs blocking IO on ZIO's blocking thread pool.
    */
  def runProcess(argv: List[String], cwd: String): Task[(List[String], Int)] =
    ZIO.attemptBlockingIO {
      val pb       = new ProcessBuilder(argv*)
      pb.directory(Paths.get(cwd).toFile)
      pb.redirectErrorStream(true)
      val process  = pb.start()
      val lines    = scala.io.Source.fromInputStream(process.getInputStream).getLines().toList
      val exitCode = process.waitFor()
      (lines, exitCode)
    }
