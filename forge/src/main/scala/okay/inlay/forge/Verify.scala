package okay.inlay.forge

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
    val out = Files.createTempFile("forge", ".out")
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
    val dir = Files.createTempDirectory("forge-check")
    try
      // each candidate in its member's own package (its file's relative imports resolve there), its owner's members
      // imported (types nested in the owner, as its signature names them); a file a package
      val byPkg = items.zipWithIndex.groupBy(_._1._1.member.pkg)
      for ((pkg, its), n) <- byPkg.toVector.zipWithIndex do
        val imports = its.flatMap((it, _) => p.imports(it._1.member.file)).distinct
        val objects = its.map { case ((t, code), i) =>
          val own = if t.member.owner.isEmpty || t.member.instance then "" else s"  import ${t.member.owner}.*\n"
          s"object T$i:\n$own${indent(code)}\nobject R$i:\n$own${indent(t.reference)}\n"
        }
        Files.writeString(dir.resolve(s"Candidates$n.scala"), (if pkg.isEmpty || pkg == "scala" then ""
          else s"package $pkg\n\n") + imports.mkString("\n") + "\n\n" + objects.mkString("\n"))
      def at(t: Task, i: Int) =
        if t.member.pkg.isEmpty || t.member.pkg == "scala" then s"T$i" -> s"R$i"
        else s"${t.member.pkg}.T$i" -> s"${t.member.pkg}.R$i"
      val calls = items.zipWithIndex.map { case ((t, _), i) =>
        val (ti, ri) = at(t, i)
        val c = t.arity match
          case 0 => s"compare0($ti.${t.name}, $ri.${t.name})"
          case n => s"compare$n($ti.${t.name}, $ri.${t.name}, 3, 200)"
        s"""  println("$i\\t" + (try okay.symdex.check.Check.$c catch case e: Throwable => "throws " + e))"""
      }
      Files.writeString(dir.resolve("Check.scala"), checkSource)
      // generators for common types the project's classpath has (okay-effects' JSON value), imported where compared
      val json = p.classpath.contains("okay-effects-json-value")
      if json then Files.writeString(dir.resolve("Gens.scala"), Verify.jsonGen)
      // a given for each project type in the signatures (not generic): a recursive one's derivation finds it
      val named = items.flatMap((t, _) => """\b[A-Z]\w*\b(?!\s*\[)""".r.findAllIn(t.params).toVector).distinct
      // a generic project type applied in a signature (`Path[Int]`, the receiver of a generic class's member)
      val applied = items.flatMap((t, _) => """\b([A-Z]\w*)(\[[^\[\]]*\])""".r.findAllMatchIn(t.params)
        .flatMap(m => p.types.get(m.group(1)).map(_ + m.group(2))).toVector).distinct
      val givens = (p.reach(named).flatMap(p.types.get).distinct ++ applied).zipWithIndex.map((q, i) =>
        s"given forgeGen$i: okay.symdex.check.Gen[$q] = okay.symdex.check.Gen.auto[$q]")
      Files.writeString(dir.resolve("Main.scala"), (if json then "import forgegens.given\n\n" else "") +
        givens.mkString("", "\n", "\n\n") +
        "@main def forgeMain(): Unit =\n" + calls.mkString("\n") + "\n")
      val (rc, out) = run(Seq("scala-cli", "run", "--server=false", "-S", scalaVersion, "--classpath", p.classpath,
        "."), dir)
      if rc != 0 && !out.linesIterator.exists(_.matches("\\d+\\t.*")) then
        Left(out.replaceAll("\u001b\\[[0-9;]*m", "").linesIterator
          .filter(l => l.contains("Error") || l.contains("Not found") || l.contains("not a member") ||
            l.contains("No given") || l.contains("Found:") || l.contains("Required:")).take(8)
          .mkString(" | "))
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

object Verify:
  /** a generator of okay-effects' JSON values: the small ones (each kind, empty and one-deep), random ones of a
   * depth */
  val jsonGen: String = """package forgegens

import okay.effects.json.Json
import okay.symdex.check.Gen

given Gen[Json] = Gen.of(
  s => LazyList(Json.Null, Json.Bool(true), Json.Num(BigDecimal(0)), Json.Str(""), Json.Str("a"), Json.Bool(false),
    Json.Num(BigDecimal(1)), Json.Arr(Vector()), Json.Obj(Vector()), Json.Arr(Vector(Json.Num(BigDecimal(1)))),
    Json.Obj(Vector("a" -> Json.Str("b")))).take(s + 6),
  (r, s) => random(r, s))

given Gen[Json.Obj] = Gen.of(
  s => LazyList(Json.Obj(Vector()), Json.Obj(Vector("a" -> Json.Str("b"))),
    Json.Obj(Vector("a" -> Json.Num(BigDecimal(1)),
    "b" -> Json.Null))).take(s + 1),
  (r, s) => Json.Obj(Vector.fill(r.nextInt(3))((List("a", "b", "c")(r.nextInt(3)), random(r, s - 1))).distinctBy(_._1)))

given Gen[Json.Arr] = Gen.of(
  s => LazyList(Json.Arr(Vector()), Json.Arr(Vector(Json.Num(BigDecimal(1))))).take(s + 1),
  (r, s) => Json.Arr(Vector.fill(r.nextInt(3))(random(r, s - 1))))

private def random(r: scala.util.Random, s: Int): Json = r.nextInt(if s <= 0 then 4 else 6) match
  case 0 => Json.Null
  case 1 => Json.Bool(r.nextBoolean())
  case 2 => Json.Num(BigDecimal(r.between(-s * 10, s * 10 + 1)))
  case 3 => Json.Str(List.fill(r.nextInt(s + 2))(('a' + r.nextInt(3)).toChar).mkString)
  case 4 => Json.Arr(Vector.fill(r.nextInt(3))(random(r, s - 1)))
  case _ => Json.Obj(Vector.fill(r.nextInt(3))((List("a", "b", "c")(r.nextInt(3)), random(r, s - 1))).distinctBy(_._1))
"""
