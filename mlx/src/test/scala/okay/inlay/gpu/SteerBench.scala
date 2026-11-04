package okay.inlay.gpu

import _root_.mlx.{Arr, Dtype, Scope}
import _root_.mlx.Mlx.*
import _root_.mlx.llm.{Chat, Model, Models, Qwen3, Session}

/**
 * STEERING (specs/refine.md stage 1): the small model's habit — wrapping its answer in an object, reimplementing what
 * it should call — as a direction in the residual stream: the mean hidden state of right answers (the reference
 * solutions) minus that of its own failed ones (bench/symbols/history.tsv), at a layer; added while decoding. Each
 * task's direction from the OTHER nine (leave one out). Opt-in: STEER=1.
 */
class SteerBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "min")
  private val bench = SymbolsBench()

  /** the mean hidden state, after `layer`, over the positions from `from` on */
  final class Capture(layer: Int, from: Int) extends Qwen3.Hook:
    var mean: Array[Float] = Array.empty
    def after(i: Int, h: Arr)(using Scope): Arr =
      if i == layer then
        val n = h.dim(1)
        val part = astype(slice(h, Seq(0, from, 0), Seq(1, n, h.dim(2))), Dtype.Float32)
        mean = divide(sum(part, 1), scalar((n - from).toFloat)).toFloats
      h

  /** `c` times `v` added to every position after `layer` */
  final class Steer(layer: Int, v: Array[Float], c: Float) extends Qwen3.Hook:
    def after(i: Int, h: Arr)(using Scope): Arr =
      if i == layer then add(h, astype(floats(v.map(_ * c), 1, 1, v.length), h.dtype)) else h

  test("steering Qwen3-4B toward calling the API"):
    assume(sys.env.contains("STEER"), "STEER=1 to run")
    val dir = Models.snapshot("mlx-community/Qwen3-4B-4bit")
    assume(dir.isDefined, "Qwen3-4B is not in the HuggingFace cache")
    val cp = java.nio.file.Files.readString(java.nio.file.Path.of("/tmp/symdex-cp.txt")).trim
    val ms = bench.members()
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true).asInstanceOf[Qwen3]
    val tok = chat.tokenizer
    // the model's own failed answers per task: those that compiled or not but did not behave
    val history = java.nio.file.Files.readAllLines(java.nio.file.Path.of("../bench/symbols/history.tsv")
      .toAbsolutePath.normalize).toArray(Array.empty[String]).drop(1).toVector.map(_.split("\t", -1))
    val failed = (1 to bench.tasks.size).map { t =>
      val bad = history.filter(r => r.length > 8 && r(3) == t.toString && r(6) != "right" && r(8).trim.nonEmpty)
        .map(_(8).replace("\\n", "\n"))
      bad.find(_.contains("object ")).orElse(bad.lastOption)
    }.toVector
    def prompt(i: Int): Array[Int] =
      val task = bench.tasks(i)
      val owner = "`([A-Z]\\w*)`".r.findFirstMatchIn(task).map(_.group(1)).getOrElse("")
      val es = bench.entries(owner, ms)
      val shown = Some(bench.focus(task, es)).filter(_.nonEmpty).getOrElse(es)
      val calls = bench.usages(owner, shown.map(_._1).toSet)
      chat.ask("You write Scala 3 for the project symdex (package `symdex`, already imported). " + task +
        s"\n\n`$owner`'s public API (what this task needs):\n${shown.map(_._2).mkString("\n")}" +
        "\n\nHow symdex calls it:" +
        s"\n$calls\n Your definition goes in your own code, outside symdex: call symdex's members, do not " +
        "reimplement them, and declare no object or class. Reply with one ```scala code block holding only the " +
        "definition, no explanation.", Map("enable_thinking" -> false))
    // hidden states of an answer after the prompt
    def hidden(layer: Int, i: Int, answer: String): Array[Float] =
      val p = prompt(i)
      val ids = p ++ tok.encode(s"```scala\n$answer\n```")
      val cap = Capture(layer, p.length)
      model.hook = cap
      val st = model.newState()
      val s = new Scope
      try model.logits(ints(ids, 1, ids.length)(using s), st)(using s).eval()
      finally { s.close(); st.close(); model.hook = null }
      cap.mean
    def run(i: Int, hook: Qwen3.Hook | Null): (String, Boolean, Boolean) =
      model.hook = hook
      try
        val s = Session(model, prompt(i), 400, chat.stops)
        val out = tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector)
        s.close()
        val c = bench.code(out)
        val (ok, _, _) = bench.compiles(c, cp)
        (c, ok, ok && bench.behaves(c, bench.checks(i), cp))
      finally model.hook = null
    val pairs = bench.tasks.indices.filter(failed(_).isDefined)
    println(s"pairs: ${pairs.size} tasks with a failed answer")
    val base = bench.tasks.indices.map(i => run(i, null))
    println(s"base: ${base.count(_._2)} compile, ${base.count(_._3)} behave")
    for layer <- Seq(9, 18, 27) do
      // per pair, right − failed; the direction for task i the mean over the others
      val diffs = pairs.map(t => t -> {
        val g = hidden(layer, t, bench.references(t))
        val b = hidden(layer, t, failed(t).get)
        g.indices.map(k => g(k) - b(k)).toArray
      }).toMap
      val norm = math.sqrt(diffs.values.map(d => d.map(x => x * x).sum).sum / diffs.size)
      for c <- Seq(2f, 4f) do
        val res = bench.tasks.indices.map { i =>
          val others = diffs.filter(_._1 != i).values.toVector
          val v = others.head.indices.map(k => others.map(_(k)).sum / others.size).toArray
          run(i, Steer(layer, v, c))
        }
        println(f"layer $layer%2d, c $c%.0f (|d| ≈ $norm%.1f): ${res.count(_._2)} compile, " +
          s"${res.count(_._3)} behave" +
          s" — behaving: ${res.zipWithIndex.filter(_._1._3).map(_._2 + 1).mkString(",")}")
    model.close()
