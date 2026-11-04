package okay.inlay.refine

import java.nio.file.{Files, Path}

/** a candidate's verdict: it compiles (beside its reference), and behaves as the reference on every input tried */
final case class Verdict(compiles: Boolean, behaves: Boolean, detail: String)

/**
 * THE VERIFIER: candidates against their tasks' references, as symdex's `equivalent` does — one program, each
 * candidate in an object of its own beside its reference, compared by `compareN` on every input up to a size and on
 * random ones (symdex's generators, `Check.scala`); in BATCHES — one compilation for many, a batch that fails split in
 * halves until the ones that do not compile are found alone. The program run with a timeout.
 */
final class Verify(p: Project, checkSource: String, scalaVersion: String, timeoutMs: Long = 120000):
  private def run(cmd: Seq[String], dir: Path): (Int, String) =
    val pb = ProcessBuilder(cmd*).directory(dir.toFile).redirectErrorStream(true)
    val out = Files.createTempFile("refine", ".out")
    pb.redirectOutput(out.toFile)
    val proc = pb.start()
    try
      if !proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) then
        proc.descendants().forEach(_.destroyForcibly(): Unit)
        proc.destroyForcibly()
        (-1, "timeout")
      else (proc.exitValue(), Files.readString(out))
    finally Files.deleteIfExists(out): Unit

  private def indent(code: String) = code.linesIterator.map("  " + _).mkString("\n")

  /** one program for these candidates: their verdicts by index, or the compiler's complaint */
  private def batch(items: Vector[(Task, String)]): Either[String, Map[Int, Verdict]] =
    val dir = Files.createTempDirectory("refine-check")
    try
      val imports = items.flatMap((t, _) => p.imports(t.member.file) :+ s"import ${t.member.pkg}.*").distinct
      val objects = items.zipWithIndex.map { case ((t, code), i) =>
        s"object T$i:\n${indent(code)}\nobject R$i:\n${indent(t.reference)}\n"
      }
      val calls = items.zipWithIndex.map { case ((t, _), i) =>
        val c = t.arity match
          case 0 => s"compare0(T$i.${t.name}, R$i.${t.name})"
          case n => s"compare$n(T$i.${t.name}, R$i.${t.name}, 3, 200)"
        s"""  println("$i\\t" + (try okay.symdex.check.Check.$c catch case e: Throwable => "throws " + e))"""
      }
      Files.writeString(dir.resolve("Check.scala"), checkSource)
      Files.writeString(dir.resolve("Candidates.scala"), "package refinecheck\n\n" + imports.mkString("\n") +
        "\n\n" + objects.mkString("\n") + "\n@main def refineMain(): Unit =\n" + calls.mkString("\n") + "\n")
      val (rc, out) = run(Seq("scala-cli", "run", "--server=false", "-S", scalaVersion, "--classpath", p.classpath,
        "."), dir)
      if rc != 0 && !out.linesIterator.exists(_.matches("\\d+\\t.*")) then
        Left(out.replaceAll("\u001b\\[[0-9;]*m", "").linesIterator.filter(_.contains("Error")).take(3).mkString(" | "))
      else
        val lines = out.linesIterator.filter(_.matches("\\d+\\t.*")).map(l => l.takeWhile(_ != '\t').toInt ->
          l.dropWhile(_ != '\t').drop(1)).toMap
        Right(items.indices.map { i =>
          val l = lines.getOrElse(i, "no answer (timeout or crash)")
          i -> Verdict(true, l.contains("\"equivalent\":true"), l.take(300))
        }.toMap)
    finally
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(f => Files.deleteIfExists(f): Unit)

  /** every candidate's verdict, in order */
  def apply(items: Vector[(Task, String)], size: Int = 24): Vector[Verdict] =
    def go(xs: Vector[(Int, (Task, String))]): Map[Int, Verdict] =
      if xs.isEmpty then Map.empty
      else batch(xs.map(_._2)) match
        case Right(v) => xs.indices.map(i => xs(i)._1 -> v(i)).toMap
        case Left(why) if xs.size == 1 => Map(xs.head._1 -> Verdict(false, false, why))
        case Left(_) =>
          val (a, b) = xs.splitAt(xs.size / 2)
          go(a) ++ go(b)
    val all = items.zipWithIndex.map((it, i) => i -> it).grouped(size).flatMap(go).toMap
    items.indices.map(all).toVector
