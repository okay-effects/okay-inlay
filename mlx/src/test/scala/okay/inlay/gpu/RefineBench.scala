package okay.inlay.gpu

import _root_.mlx.llm.{Chat, Lora, Model, Models, Qwen3, Session}

/**
 * REFINING THE SMALL MODEL (specs/refine.md stages 3–5): tasks generated from symdex's index — each public member of
 * an object: "write f with it", the answer the member CALLED — on objects the evaluation does not ask about; a LoRA
 * adapter trained on them in Scala (mlx-scala's Lora); then SymbolsBench's ten tasks (other objects) with and
 * without it: a habit learned (call, qualify, no wrapper), not an API memorised. Opt-in: REFINE=1.
 */
class RefineBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "min")
  private val bench = SymbolsBench()
  /** the objects the evaluation asks about: none of their members trained on */
  private val heldOut = Set("Equivalence", "CodeFacts", "Exec", "Tasty", "Disk")

  /** a public member's signature as written (`Owner.name(a: A, b: B): R   // doc`) → a task and its answer */
  def example(owner: String, line: String): Option[(String, String)] =
    val Sig = """\s*(\w+)\.(\w+)\(([^()]*)\): ([^=]+?)(?:\s+// (.*))?""".r
    line match
      case Sig(o, name, params, ret, doc) if o == owner && params.nonEmpty && !params.contains("using") &&
          !ret.contains("!") && !ret.contains("=>") && !name.head.isUpper =>
        val ps = params.split(",").map(_.trim).filter(_.nonEmpty)
        if ps.exists(p => !p.contains(":") || p.contains("=")) then None
        else
          val names = ps.map(_.takeWhile(_ != ':').trim)
          val f = "do" + name.head.toUpper + name.tail
          val what = Option(doc).map(d => s" — $d").getOrElse("")
          val task = s"symdex's object `$owner` has `$name`$what. Write `def $f(${ps.mkString(", ")}): " +
            s"${ret.trim}` with it."
          Some(task -> s"def $f(${ps.mkString(", ")}): ${ret.trim} = $owner.$name(${names.mkString(", ")})")
      case _ => None

  def prompt(chat: Chat, task: String, owner: String, ms: Map[String, Set[String]]): Array[Int] =
    val es = bench.entries(owner, ms)
    val shown = Some(bench.focus(task, es)).filter(_.nonEmpty).getOrElse(es)
    val calls = bench.usages(owner, shown.map(_._1).toSet)
    chat.ask("You write Scala 3 for the project symdex (package `symdex`, already imported). " + task +
      s"\n\n`$owner`'s public API (what this task needs):\n${shown.map(_._2).mkString("\n")}" +
      s"\n\nHow symdex calls it:\n$calls\n Your definition goes in your own code, outside symdex: call symdex's " +
      "members, do not reimplement them, and declare no object or class. Reply with one ```scala code block " +
      "holding only the definition, no explanation.", Map("enable_thinking" -> false))

  test("a LoRA adapter trained on generated tasks, SymbolsBench before and after"):
    assume(sys.env.contains("REFINE"), "REFINE=1 to run")
    val dir = Models.snapshot("mlx-community/Qwen3-4B-4bit")
    assume(dir.isDefined, "Qwen3-4B is not in the HuggingFace cache")
    val cp = java.nio.file.Files.readString(java.nio.file.Path.of("/tmp/symdex-cp.txt")).trim
    val ms = bench.members()
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true).asInstanceOf[Qwen3]
    val tok = chat.tokenizer
    // the training set: every object but the held-out ones, each usable member a task
    val owners = ms.keys.toVector.sorted.filterNot(heldOut)
    val train = owners.flatMap(o => bench.entries(o, ms).flatMap((_, line) => example(o, line).map(e => (o, e))))
    println(s"training: ${train.size} tasks from ${train.map(_._1).distinct.size} objects, e.g.\n  " +
      train.take(3).map((_, e) => s"${e._1}\n    → ${e._2}").mkString("\n  "))
    def evaluate(label: String): (Int, Int) =
      val res = bench.tasks.indices.map { i =>
        val task = bench.tasks(i)
        val owner = "`([A-Z]\\w*)`".r.findFirstMatchIn(task).map(_.group(1)).getOrElse("")
        val s = Session(model, prompt(chat, task, owner, ms), 400, chat.stops)
        val out = tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector)
        s.close()
        val c = bench.code(out)
        val (ok, _, _) = bench.compiles(c, cp)
        (ok, ok && bench.behaves(c, bench.checks(i), cp), c)
      }
      println(s"$label: ${res.count(_._1)} compile, ${res.count(_._2)} behave — behaving " +
        res.zipWithIndex.filter(_._1._2).map(_._2 + 1).mkString(","))
      res.zipWithIndex.filterNot(_._1._2).take(3).foreach((r, i) => println(s"  [${i + 1}] ${r._3.take(200)}"))
      (res.count(_._1), res.count(_._2))
    val before = evaluate("before")
    val epochs = sys.env.getOrElse("REFINE_EPOCHS", "2").toInt
    val lora = Lora(model, rank = 8, projs = Seq("q", "v", "o"), lr = 1e-4f)
    val r = scala.util.Random(3)
    val t0 = System.nanoTime
    for e <- 1 to epochs do
      val losses = r.shuffle(train).map { (o, ex) =>
        val p = prompt(chat, ex._1, o, ms)
        lora.step(p ++ tok.encode(s"```scala\n${ex._2}\n```") :+ tok.id("<|im_end|>"), p.length)
      }
      println(f"epoch $e: mean loss ${losses.sum / losses.size}%.4f (${(System.nanoTime - t0) / 1e9}%.0f s)")
    lora.attachTrained()
    val after = evaluate("after")
    println(s"SymbolsBench (no thinking, one round): before $before, after $after (compile, behave)")
    lora.close()
    model.close()
