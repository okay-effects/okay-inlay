package okay.inlay.gpu

import _root_.mlx.llm.{Chat, Model, Models, Qwen35}
import okay.effects.*
import okay.inlay.Closure
import okay.inlay.bridge.{Bridge, Deduce, Edge, Judge}

/** THE BRIDGE WITH A MODEL READING (docs/effects.md): the same program (`Bridge.above`), its `Judge` handled by
 * Qwen3.5-4B — ask, a probe, both — and its `Deduce` by the compiled closure on the GPU; against the gold reading.
 * Texts in phrasings the probe never saw. Opt-in: MODEL_BRIDGE=1 (BRIDGE_MODEL, BRIDGE_LAYER) */
class ModelBridgeBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "min")
  type Row[X] = (Judge + Deduce)[X]
  val names = Vector("Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal", "Ivy", "Jon", "Kim", "Lou", "Max", "Ned",
    "Ola", "Pam")
  // bridge-4's phrasings: the first named is the superior (above) or the subordinate (below)
  val above = Vector("%s manages %s.", "%s is %s's boss.", "%s oversees %s.", "%s is above %s in the company.",
    "%s leads the team %s is on.", "%s supervises %s.", "%s is the manager of %s.", "%s signs off on %s's work.",
    "%s hired %s and runs that team.", "%s is %s's direct superior.", "%s runs the team %s works on.",
    "%s is %s's line manager.", "%s is in charge of %s.", "%s directs %s's work.")
  val below = Vector("%s reports to %s.", "%s works under %s.", "%s answers to %s.", "%s is %s's subordinate.",
    "%s is on the team that %s runs.", "%s takes orders from %s.", "%s was hired by %s and reports there.",
    "%s is managed by %s.", "%s is supervised by %s.", "%s's boss is %s.", "%s gets assignments from %s.",
    "%s has %s as a line manager.", "%s is accountable to %s.", "%s escalates issues to %s.")
  /** phrasings by part: 0 train the probe, 1 the spreads, 2 the test */
  def part(ps: Vector[String], k: Int) = k match
    case 0 => ps.take(4) ++ ps.drop(10)
    case 1 => ps.slice(4, 6)
    case _ => ps.slice(6, 10)
  def sentence(rnd: scala.util.Random, boss: String, worker: String, k: Int): (String, String, String, Boolean) =
    if rnd.nextBoolean() then
      val ps = part(above, k); (ps(rnd.nextInt(ps.size)).format(boss, worker), boss, worker, true)
    else
      val ps = part(below, k); (ps(rnd.nextInt(ps.size)).format(worker, boss), worker, boss, false)
  def text(rnd: scala.util.Random, n: Int, k: Int): Vector[(String, String, String, Boolean)] =
    val order = rnd.shuffle(names)
    val chain = (0 until order.size - 1).map(i => (order(i), order(i + 1)))
    val more = Vector.fill(n) { val i = rnd.nextInt(order.size - 1); (order(i), order(i + 1 + rnd.nextInt(order.size - 1 - i))) }
    rnd.shuffle((chain ++ more).distinct.take(n)).map((b, w) => sentence(rnd, b, w, k)).toVector

  test("the bridge with Qwen3.5-4B reading: ask, probe, both, against the gold"):
    assume(sys.env.contains("MODEL_BRIDGE"), "MODEL_BRIDGE=1 to run")
    val repo = sys.env.getOrElse("BRIDGE_MODEL", "mlx-community/Qwen3.5-4B-MLX-4bit")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true).asInstanceOf[Qwen35]
    val judge = ModelJudge(chat, model, sys.env.getOrElse("BRIDGE_LAYER", "12").toInt, names)
    val rnd = new scala.util.Random(13)
    val t0 = System.nanoTime()
    val probe = judge.train(Vector.fill(40)(text(rnd, 15, 0)).flatten)
    val check = Vector.fill(8)(text(rnd, 15, 1)).flatten
    val sp = ModelJudge.spread(check.map((t, x, y, _) => probe.score(judge.features(t, x, y))))
    val sa = ModelJudge.spread(check.map((t, x, y, _) => judge.ask(t, x, y)))
    println(f"probe trained on 600 sentences, spreads probe $sp%.2f ask $sa%.2f (${(System.nanoTime() - t0) / 1e9}%.0f s)")
    val block = Bridge.Block(names, 128, 5)
    val gpu = OnMlx(block.weights)
    def deduceOnGpu(edges: Vector[Edge], q: (String, String)) =
      block.answer(gpu.records(block.program, Closure.inputs(edges.map(_.asTriple), q)))
    val readers: Seq[(String, String => Option[Edge])] =
      Seq("ask" -> judge.asking, "probe" -> judge.probing(probe), "both" -> judge.both(probe, sp, sa))
    try
      for n <- Seq(15, 30) do
        val texts = Vector.fill(5)(text(rnd, n, 2))
        val rights = scala.collection.mutable.Map.empty[String, (Int, Int)].withDefaultValue((0, 0))
        for t <- texts do
          val lines = t.map(_._1)
          val gold = t.map(s => s._1 -> Edge(s._2, s._3, s._4)).toMap
          val q = (t.head._2, t.last._3)
          val byGold = run(Bridge.reading(gold.get)(Bridge.deducing(deduceOnGpu)(Bridge.above[Row](lines, q))))
          for (name, reader) <- readers do
            val said = run(Bridge.reading(reader)(Bridge.deducing(deduceOnGpu)(Bridge.above[Row](lines, q))))
            val sentences = lines.count(l => reader(l) == gold.get(l))
            val (s0, a0) = rights(name)
            rights(name) = (s0 + sentences, a0 + (if said.answer == byGold.answer then 1 else 0))
        println(s"n = $n: " + readers.map((name, _) => { val (s, a) = rights(name)
          f"$name ${s * 100.0 / (5 * n)}%.1f%% of sentences, $a/5 answers equal to the gold's" }).mkString(" | "))
    finally
      gpu.close(); model.close()
