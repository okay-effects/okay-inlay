package okay.inlay.forge

import _root_.mlx.llm.{Adaptable, Chat, Lora, Model, Models}
import java.nio.file.{Files, Path}

/**
 * THE EVALUATION OF MEANING (specs/forge.md): 40 hand-written tasks over four projects (Meaning.tasks), each checked
 * against its reference; the model with and without an okay-forge adapter. Rows in bench/forge/meaning.tsv.
 * Opt-in: MEANING=1 (FORGE_MODEL the model; MEANING_ADAPTER an adapter, or none; MEANING_ONLY_REFS=1 checks the
 * references alone).
 */
class MeaningBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(240, "min")
  private val home = sys.props("user.home")
  private val check = Files.readString(Path.of(s"$home/work/my/symdex/src/main/resources/symdex/Check.scala.txt"))
  private val bench = Path.of("../bench/forge").toAbsolutePath.normalize

  def source(name: String, module: String): Source =
    val (root, cpFile) = Prepare(name, Path.of(s"$home/work/my/$name"), module).fold(sys.error, identity)
    val cp = Files.readString(cpFile).trim
    val scala = """scala3-library_3/([0-9.]+)/""".r.findFirstMatchIn(cp).map(_.group(1)).getOrElse("3.3.8")
    val p = Project(root, cp, s"$home/work/my/symdex/target/symdex/bin/symdex")
    Source(name, p, Verify(p, check, scala))

  /** a task's first member's file, from the project: its imports for the harness */
  def located(src: Source, mt: MeaningTask): MeaningTask =
    val (o, n) = mt.uses.head
    src.project.members.find(m => m.owner == o && m.name == n)
      .fold(mt)(m => mt.copy(task = mt.task.copy(member = mt.task.member.copy(file = m.file))))

  test("meaning: the model with and without an adapter"):
    assume(sys.env.contains("MEANING"), "MEANING=1 to run")
    val sources = Vector("symdex" -> "", "okay-store" -> "core", "okay-proof" -> "proofJVM",
      "okay-guard" -> "policyJVM").map((n, m) => n -> source(n, m)).toMap
    val tasks = Meaning.tasks.map(mt => located(sources(mt.project), mt))
    // the references first: each must equal itself
    val refs = tasks.groupBy(_.project).toVector.flatMap((n, ts) =>
      ts.zip(sources(n).verify(ts.map(mt => mt.task -> mt.task.reference))))
    refs.filterNot(_._2.behaves).foreach((mt, v) => println(s"  reference fails: ${mt.task.id} — ${v.detail.take(300)}"))
    val good = refs.filter(_._2.behaves).map(_._1)
    println(s"references: ${good.size} of ${tasks.size} usable")
    if !sys.env.contains("MEANING_ONLY_REFS") then
      val repo = sys.env.getOrElse("FORGE_MODEL", "mlx-community/Qwen3-4B-4bit")
      val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo is not in the HuggingFace cache"))
      val chat = Chat.load(dir)
      val model = Model.load(dir, mapped = true).asInstanceOf[Adaptable]
      val f = Forge(model, chat)
      val history = bench.resolve("meaning.tsv")
      if !Files.exists(history) then
        Files.writeString(history, "when\tmodel\tarm\tproject\ttask\tcompiles\tbehaves\tcode\n")
      val stamp = java.time.LocalDateTime.now.withNano(0).toString
      val adapter = sys.env.get("MEANING_ADAPTER").filter(_.nonEmpty)
      for arm <- Vector("base") ++ adapter.map(_ => "forge") do
        val held = if arm == "forge" then Some(Lora.attach(model, adapter.get)) else None
        val res = good.groupBy(_.project).toVector.sortBy(_._1).flatMap((n, ts) => f.meaningAttempts(sources(n), ts))
        held.foreach { s => s.close(); model.lora = Map.empty }
        for (mt, code, v) <- res do
          Files.writeString(history, Seq(stamp, repo, arm, mt.project, mt.task.name, v.compiles, v.behaves,
            code.replace("\n", "\\n").replace("\t", " ")).mkString("\t") + "\n", java.nio.file.StandardOpenOption.APPEND)
        val byProject = res.groupBy(_._1.project).toVector.sortBy(_._1)
          .map((n, rs) => s"$n ${rs.count(_._3.behaves)}/${rs.size}").mkString(", ")
        println(s"$arm: ${res.count(_._3.compiles)} compile, ${res.count(_._3.behaves)} behave of ${res.size} " +
          s"($byProject)")
      model.close()
