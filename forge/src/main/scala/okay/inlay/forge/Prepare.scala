package okay.inlay.forge

import java.nio.file.{Files, Path}

/**
 * A PROJECT MADE READY FOR THE TOOL, out of its working copy (other agents may be building there): cloned into the
 * tool's home (`~/.cache/okay-forge/projects/<name>`, a local clone), compiled with SemanticDB on, its compile
 * classpath exported (`<name>.cp`). Kept across runs — not in /tmp.
 */
object Prepare:
  val home: Path = Path.of(sys.env.getOrElse("FORGE_HOME", sys.props("user.home") + "/.cache/okay-forge"))

  private def sh(dir: Path, cmd: String*): (Int, String) =
    val p = ProcessBuilder(cmd*).directory(dir.toFile).redirectErrorStream(true).start()
    val out = String(p.getInputStream.readAllBytes)
    (p.waitFor(), out)

  /** the project `name` from `from`, its sbt module `module` (e.g. coreJVM): its root and classpath file */
  def apply(name: String, from: Path, module: String, refresh: Boolean = false): Either[String, (Path, Path)] =
    val projects = home.resolve("projects")
    Files.createDirectories(projects)
    val root = projects.resolve(name)
    val cp = home.resolve(s"$name.cp")
    if Files.exists(cp) && !refresh then Right((root, cp))
    else
      if !Files.exists(root) then sh(projects, "git", "clone", "-q", "--local", from.toString, name)
      else sh(root, "git", "pull", "-q")
      val task = if module.isEmpty then "Compile/fullClasspath" else s"$module/Compile/fullClasspath"
      val compile = if module.isEmpty then "compile" else s"$module/compile"
      val (rc, out) = sh(root, "sbt", "-batch", "set every semanticdbEnabled := true", compile, s"export $task")
      val line = out.linesIterator.toVector.reverse.find(l => l.contains("/") && !l.startsWith("["))
      if rc != 0 || line.isEmpty then Left(s"$name: the build failed: ${out.linesIterator.toVector.takeRight(5)
        .mkString(" | ")}")
      else
        Files.writeString(cp, line.get.trim)
        Right((root, cp))
