package okay.inlay.refine

import _root_.mlx.llm.{Adaptable, Chat, Lora, Model, Models, Qwen35}
import java.nio.file.{Files, Path}

/**
 * TRANSFER (specs/refine.md "The tool"): an adapter trained on tasks from OTHER projects (okay-scala, okay-datalog) —
 * their references, then also the model's own answers the verifier kept — and evaluated on symdex's tasks, never
 * trained on. Rows into bench/refine/history.tsv. Opt-in: TRANSFER=1 (REFINE_MODEL: the model).
 */
class TransferBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(300, "min")
  private val home = sys.props("user.home")
  private val symdexBin = s"$home/work/my/symdex/target/symdex/bin/symdex"
  private val check = Files.readString(Path.of(s"$home/work/my/symdex/src/main/resources/symdex/Check.scala.txt"))
  private val bench = Path.of("../bench/refine").toAbsolutePath.normalize

  def source(name: String, root: String, cp: String, scala: String): Source =
    val p = Project(Path.of(root), Files.readString(Path.of(cp)).trim, symdexBin)
    Source(name, p, Verify(p, check, scala))

  /** the tasks whose reference compiles and equals itself (kept in bench/refine/usable-<name>.txt) */
  def usable(src: Source): Vector[Task] =
    val all = Tasks.from(src.project)
    val file = bench.resolve(s"usable-${src.name}.txt")
    val ok =
      if Files.exists(file) then Files.readAllLines(file).toArray(Array.empty[String]).toSet
      else
        val v = src.verify(all.map(t => t -> t.reference))
        val ids = all.zip(v).filter(_._2.behaves).map(_._1.id)
        Files.writeString(file, ids.mkString("\n") + "\n")
        ids.toSet
    all.filter(t => ok(t.id))

  test("an adapter from other projects, on symdex"):
    assume(sys.env.contains("TRANSFER"), "TRANSFER=1 to run")
    Files.createDirectories(bench)
    val repo = sys.env.getOrElse("REFINE_MODEL", "mlx-community/Qwen3-4B-4bit")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val evalSrc = source("symdex", s"$home/work/my/symdex", "/tmp/symdex-cp.txt", "3.9.0")
    val trainSrcs = Vector(
      source("okay-scala", "/tmp/refine-projects/okay-scala", "/tmp/refine-projects/okay-scala.cp", "3.3.8"),
      source("okay-datalog", "/tmp/refine-projects/okay-datalog", "/tmp/refine-projects/okay-datalog.cp", "3.3.8"))
    val evalTasks = usable(evalSrc)
    val train = trainSrcs.map(s => s -> usable(s))
    println(s"eval: ${evalTasks.size} symdex tasks; train: " + train.map((s, t) => s"${s.name} ${t.size}").mkString(", "))
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true).asInstanceOf[Adaptable]
    val r = Refine(model, chat)
    val history = bench.resolve("history.tsv")
    if !Files.exists(history) then
      Files.writeString(history, "when\tmodel\tarm\ttasks\tcompile\tbehave\tseconds\tnote\n")
    val stamp = java.time.LocalDateTime.now.withNano(0).toString
    def evaluate(arm: String, note: String = ""): (Int, Int) =
      val t0 = System.nanoTime
      val res = r.attempts(evalSrc, evalTasks)
      val (c, b) = (res.count(_._3.compiles), res.count(_._3.behaves))
      val secs = (System.nanoTime - t0) / 1e9
      println(f"$arm: $c compile, $b behave of ${evalTasks.size} ($secs%.0f s)")
      Files.writeString(history, Seq(stamp, repo, arm, evalTasks.size, c, b, f"$secs%.0f", note).mkString("\t") + "\n",
        java.nio.file.StandardOpenOption.APPEND)
      (c, b)
    evaluate("base")
    val projs = model match
      case _: Qwen35 => Seq("o", "out", "down")
      case _ => Seq("q", "v", "o")
    val layers = model match
      case m: Qwen35 => (m.layerCount - 8) until m.layerCount // the delta rule by ops is slow: the last layers alone
      case _ => Nil
    // arm 1: the references of the other projects' tasks
    val refs = train.flatMap((s, ts) => ts.map(t => r.example(s, t, t.reference)))
    val lora = Lora(model, rank = 8, projs = projs, layers = layers, lr = 1e-4f)
    val l1 = r.train(lora, refs, epochs = 2)
    lora.attachTrained()
    evaluate("refs", s"${refs.size} examples, losses ${l1.map(x => f"$x%.4f").mkString(" ")}")
    lora.detach()
    // arm 2: and the base model's own answers the verifier kept (its distribution, its style)
    lora.close()
    val kept = train.flatMap { (s, ts) =>
      r.attempts(s, ts).filter(_._3.behaves).map((t, c, _) => r.example(s, t, c))
    }
    println(s"the base model's own kept: ${kept.size}")
    val lora2 = Lora(model, rank = 8, projs = projs, layers = layers, lr = 1e-4f)
    val l2 = r.train(lora2, refs ++ kept, epochs = 2)
    lora2.attachTrained()
    evaluate("refs+own", s"${refs.size + kept.size} examples, losses ${l2.map(x => f"$x%.4f").mkString(" ")}")
    lora2.save(bench.resolve(s"adapter-${repo.split('/').last}.safetensors").toString)
    lora2.close()
    model.close()
