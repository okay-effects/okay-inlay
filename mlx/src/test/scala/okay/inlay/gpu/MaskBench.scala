package okay.inlay.gpu

import _root_.mlx.Dtype
import _root_.mlx.Mlx.*
import _root_.mlx.llm.{Adaptable, Chat, Lora, Model, Models, Names, Qwen35, Session}

/**
 * BRIDGE-2 (specs/bridge.md §4): LEARNING UNDER THE GUARANTEE. The `Names` mask guarantees no invented member
 * but a model trained without it fights it (specs/symbols.md: derails). Here LoRA is trained with the mask IN THE
 * LOSS: at each answer token the cross-entropy is over the tokens the mask allows at that point (the rest −∞), so
 * the model learns to choose well inside the allowed set. Arms, each measured on SymbolsBench free and held (mask
 * on at generation): the model as it is; LoRA plain (ForgeBench's); LoRA under the mask. Opt-in: MASK=1.
 */
class MaskBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(240, "min")
  private val bench = SymbolsBench()
  private val forge = ForgeBench()
  private val heldOut = Set("Equivalence", "CodeFacts", "Exec", "Tasty", "Disk")

  test("bridge-2: LoRA under the Names mask against LoRA plain and the mask alone"):
    assume(sys.env.contains("MASK"), "MASK=1 to run")
    val repo = sys.env.getOrElse("BRIDGE_MODEL", "mlx-community/Qwen3.5-4B-MLX-4bit")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val cp = java.nio.file.Files.readString(java.nio.file.Path.of("/tmp/symdex-cp.txt")).trim
    val ms = bench.members()
    val chat = Chat.load(dir.get)
    val loaded = Model.load(dir.get, mapped = true)
    val model = loaded.asInstanceOf[Adaptable]
    val tok = chat.tokenizer
    val end = tok.id("<|im_end|>")
    println(s"model: $repo")
    val owners = ms.keys.toVector.sorted.filterNot(heldOut)
    val train = owners.flatMap(o => bench.entries(o, ms).flatMap((_, line) => forge.example(o, line).map(e => (o, e))))
    println(s"training: ${train.size} tasks from ${train.map(_._1).distinct.size} objects")

    def evaluate(label: String): Map[String, (Int, Int, Int)] =
      Seq(false, true).map { held =>
        val res = bench.tasks.indices.map { i =>
          val task = bench.tasks(i)
          val owner = "`([A-Z]\\w*)`".r.findFirstMatchIn(task).map(_.group(1)).getOrElse("")
          val s = Session(loaded, forge.prompt(chat, task, owner, ms), 400, chat.stops,
            port = if held then Names(tok, ms) else null)
          val out = tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector)
          s.close()
          val c = bench.code(out)
          val (ok, _, _) = bench.compiles(c, cp)
          (bench.invented(c, ms)._1, ok, ok && bench.behaves(c, bench.checks(i), cp))
        }
        val arm = if held then "held" else "free"
        println(s"$label, $arm: invented ${res.map(_._1).sum}, ${res.count(_._2)} compile, ${res.count(_._3)} behave — " +
          s"behaving ${res.zipWithIndex.filter(_._1._3).map(_._2 + 1).mkString(",")}")
        arm -> (res.map(_._1).sum, res.count(_._2), res.count(_._3))
      }.toMap

    /** the mask over an answer's tokens: for each, what `Names` allows given the tokens before it (0 / −1e9) */
    def maskOf(answer: Array[Int], vocab: Int): Array[Float] =
      val port = Names(tok, ms)
      val m = new Array[Float](answer.length * vocab)
      for (t, i) <- answer.zipWithIndex do
        port.allowed match
          case Some(ok) =>
            java.util.Arrays.fill(m, i * vocab, (i + 1) * vocab, -1e9f)
            ok.foreach(a => m(i * vocab + a) = 0f)
            m(i * vocab + t) = 0f // the reference is right by definition: never masked out
          case None => ()
        port.took(t)
      m

    def trainLora(masked: Boolean, label: String): Lora =
      val projs = loaded match
        case _: Qwen35 => Seq("o", "out", "down")
        case _ => Seq("q", "v", "o")
      val lora = Lora(model, rank = 8, projs = projs, lr = 1e-4f)
      val r = scala.util.Random(3)
      val t0 = System.nanoTime
      val epochs = sys.env.getOrElse("MASK_EPOCHS", "2").toInt
      for e <- 1 to epochs do
        val losses = r.shuffle(train).map { (o, ex) =>
          val p = forge.prompt(chat, ex._1, o, ms)
          val answer = tok.encode(s"```scala\n${ex._2}\n```") :+ end
          val ids = p ++ answer
          if !masked then lora.step(ids, p.length)
          else lora.stepWith {
            val st = loaded.newState()
            try
              val n = ids.length; val from = p.length
              val logits = astype(loaded.logitsAll(ints(ids, 1, n), st), Dtype.Float32)
              val vocab = logits.dim(-1)
              val pred = add(reshape(slice(logits, Seq(0, from - 1, 0), Seq(1, n - 1, vocab)), n - from, vocab),
                floats(maskOf(answer, vocab), n - from, vocab))
              val target = ints(ids.drop(from), n - from, 1)
              val ce = subtract(logsumexp(pred, 1), reshape(takeAlong(pred, target, 1), n - from))
              divide(sum(ce, 0), scalar((n - from).toFloat))
            finally st.close()
          }
        }
        println(f"$label epoch $e: mean loss ${losses.sum / losses.size}%.4f (${(System.nanoTime - t0) / 1e9}%.0f s)")
      lora.attachTrained()
      lora

    val before = evaluate("as it is")
    val plain = trainLora(masked = false, "LoRA plain")
    val afterPlain = evaluate("LoRA plain")
    plain.detach(); plain.close()
    val under = trainLora(masked = true, "LoRA under the mask")
    val afterMask = evaluate("LoRA under the mask")
    under.detach(); under.close()
    println("arm | free (invented, compile, behave) | held (invented, compile, behave)")
    for (n, m) <- Seq("as it is" -> before, "LoRA plain" -> afterPlain, "LoRA under the mask" -> afterMask) do
      println(s"$n | ${m("free")} | ${m("held")}")
    loaded.close()
