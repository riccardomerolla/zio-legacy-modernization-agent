package workspace.control
import java.nio.file.Paths

import zio.*

import workspace.entity.RunMode

object CliAgentRunner:

  /** Map CLI tool name → argv list for host execution. `cliTool` is the binary to invoke (e.g. "claude", "gemini",
    * "opencode"). All agents use the process `cwd` (set to `worktreePath`) for directory context.
    *
    * For `gemini`, the original repository path is passed via `--include-directories` so the sandbox allows access to
    * both the worktree checkout and the source repository root.
    */
  private def buildArgvForHost(cliTool: String, prompt: String, repoPath: String): List[String] =
    cliTool match
      case "gemini"   => List("gemini", "--include-directories", repoPath, "-p", prompt)
      case "opencode" => List("opencode", "run", "--prompt", prompt)
      case "claude"   => List("claude", "--print", prompt)
      case "codex"    => List("codex", prompt)
      case "copilot"  => List("gh", "copilot", "suggest", "-t", "shell", prompt)
      case other      => List(other, prompt)

  /** Build the full argv list, wrapping in `docker run` when `runMode` is `RunMode.Docker`. `cliTool` is the CLI binary
    * to invoke (from the workspace's `cliTool` setting). `repoPath` is the original workspace `localPath`; it is added
    * as an `--include-directories` argument for tools (like `gemini`) that sandbox filesystem access to the CWD.
    */
  def buildArgv(
    cliTool: String,
    prompt: String,
    worktreePath: String,
    runMode: RunMode = RunMode.Host,
    repoPath: String = "",
  ): List[String] =
    val effectiveRepoPath = if repoPath.nonEmpty then repoPath else worktreePath
    runMode match
      case RunMode.Host                                             =>
        buildArgvForHost(cliTool, prompt, effectiveRepoPath)
      case RunMode.Docker(image, extraArgs, mountWorktree, network) =>
        val innerArgv    = buildArgvForHost(cliTool, prompt, effectiveRepoPath)
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

  /** Run argv as a subprocess, calling `onLine` for each line of output as it is produced. stderr is merged into stdout
    * via redirectErrorStream. Returns the exit code. Runs on ZIO's blocking thread pool. The process is forcibly
    * destroyed if the effect is interrupted or an error occurs.
    */
  def runProcessStreaming(argv: List[String], cwd: String, onLine: String => Task[Unit]): Task[Int] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlockingIO {
        val pb = new ProcessBuilder(argv*)
        pb.directory(Paths.get(cwd).toFile)
        pb.redirectErrorStream(true)
        pb.start()
      }
    )(process => ZIO.succeedBlocking(process.destroyForcibly()).ignore) { process =>
      val reader           = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
      def loop: Task[Unit] =
        ZIO.attemptBlockingIO(Option(reader.readLine())).flatMap {
          case None       => ZIO.unit
          case Some(line) => onLine(line) *> loop
        }
      loop *> ZIO.attemptBlockingIO(process.waitFor())
    }
