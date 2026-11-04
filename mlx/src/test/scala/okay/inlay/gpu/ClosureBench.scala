package okay.inlay.gpu

import _root_.mlx.{Dtype, Scope}
import _root_.mlx.Mlx.*
import _root_.mlx.llm.{Chat, Model, Models, Qwen3, Qwen35, Session}
import okay.inlay.*

/**
 * BRIDGE-4 (specs/bridge.md §7): transitive closure with a neural relation. Sentences state who is above whom in
 * a company, in many phrasings; the one judgement — "the first person named is the superior" — is the model's,
 * read two ways: a PROBE (layer 12, trained on other phrasings) and ASK (no training: the model's yes / no logits
 * on the sentence). The closure, the answer to "is X above Y", what it rests on and the sentences in a cycle are
 * `Closure`'s program in weights on the GPU. Arms per question: the model alone, with thinking, the bridge by
 * probe, by ask, and the gold judgement (the ceiling, exact by the law). Opt-in: CLOSURE=1; CLOSURE_THINK=1.
 */
class ClosureBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(240, "min")
  val names = Vector("Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal", "Ivy", "Jon", "Kim", "Lou", "Max",
    "Ned", "Ola", "Pam")
  /** phrasings: the first named is the superior (true) or the subordinate (false) */
  val above = Vector("%s manages %s.", "%s is %s's boss.", "%s oversees %s.", "%s is above %s in the company.",
    "%s leads the team %s is on.", "%s supervises %s.", "%s is the manager of %s.", "%s signs off on %s's work.",
    "%s hired %s and runs that team.", "%s is %s's direct superior.")
  val below = Vector("%s reports to %s.", "%s works under %s.", "%s answers to %s.", "%s is %s's subordinate.",
    "%s is on the team that %s runs.", "%s takes orders from %s.", "%s was hired by %s and reports there.",
    "%s is managed by %s.", "%s is supervised by %s.", "%s's boss is %s.")
  /** more phrasings for TRAINING the probe only (the validate and test ones stay as they are) */
  val moreAbove = Vector("%s runs the team %s works on.", "%s is %s's line manager.", "%s is in charge of %s.",
    "%s directs %s's work.", "%s approves %s's leave requests.", "%s gives %s their assignments.",
    "%s reviews %s's performance every quarter.", "%s is senior to %s and manages them.",
    "%s coordinates the group %s belongs to.", "%s is the head of %s's department.", "%s delegates tasks to %s.",
    "%s sits above %s on the org chart.")
  val moreBelow = Vector("%s gets assignments from %s.", "%s has %s as a line manager.", "%s is accountable to %s.",
    "%s escalates issues to %s.", "%s needs %s's approval for leave.", "%s is junior to %s and managed by them.",
    "%s belongs to the group %s coordinates.", "%s is in %s's department, under them.", "%s does what %s delegates.",
    "%s sits below %s on the org chart.", "%s is one of %s's reports.", "%s checks in with %s, their manager, weekly.")
  def part(ps: Vector[String], k: Int): Vector[String] = k match
    case 0 => ps.take(4) ++ (if ps == above then moreAbove else moreBelow)
    case 1 => ps.slice(4, 6)
    case _ => ps.drop(6)

  /** an instance: sentences (text, first, second, firstIsSuperior) over a random order with a back edge when
   * cyclic; a question (x, y) with its gold answer, at least `hops` apart when yes */
  final case class Instance(sentences: Vector[(String, String, String, Boolean)], question: (String, String))
  def instance(rnd: scala.util.Random, n: Int, k: Int, cyclic: Boolean): Instance =
    val order = rnd.shuffle(names)
    val edges = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    // a chain through all names first (long paths), then random forward edges
    for i <- 0 until order.size - 1 do edges += ((order(i), order(i + 1)))
    while edges.size < n do
      val i = rnd.nextInt(order.size - 1); val j = i + 1 + rnd.nextInt(order.size - 1 - i)
      val e = (order(i), order(j))
      if !edges.contains(e) then edges += e
    val all = edges.take(n).toVector
    val withCycle = if cyclic then all.updated(rnd.nextInt(all.size), { val (x, y) = all(rnd.nextInt(all.size)); (y, x) })
      else all
    val sentences = rnd.shuffle(withCycle).map { (x, y) =>
      if rnd.nextBoolean() then (part(above, k)(rnd.nextInt(part(above, k).size)).format(x, y), x, y, true)
      else (part(below, k)(rnd.nextInt(part(below, k).size)).format(y, x), y, x, false)
    }
    // the question: yes with 3+ hops along the chain, or no (a pair not reachable)
    val q = if rnd.nextBoolean() then
      val i = rnd.nextInt(order.size - 3); (order(i), order(i + 3 + rnd.nextInt(order.size - 3 - i)))
    else
      val i = 1 + rnd.nextInt(order.size - 1); (order(i), order(rnd.nextInt(i)))
    Instance(sentences, q)

  final class Probe(val mean: Array[Float], val std: Array[Float], val w: Array[Float], val b: Float):
    def score(h: Array[Float]): Float =
      var s = b; var i = 0
      while i < w.length do { s += w(i) * (h(i) - mean(i)) / std(i); i += 1 }
      s
  object Probe:
    def train(xs: Vector[Array[Float]], ys: Vector[Boolean], steps: Int = 400, lr: Float = 0.05f, l2: Float = 1e-2f)
        : Probe =
      val d = xs.head.length; val n = xs.size
      val mean = Array.tabulate(d)(i => xs.map(_(i)).sum / n)
      val std = Array.tabulate(d)(i => math.max(math.sqrt(xs.map(x => (x(i) - mean(i)) * (x(i) - mean(i))).sum / n),
        1e-3).toFloat)
      val zs = xs.map(x => Array.tabulate(d)(i => (x(i) - mean(i)) / std(i)))
      val w = Array.fill(d)(0f); var b = 0f
      for _ <- 1 to steps do
        val gw = Array.fill(d)(0f); var gb = 0f
        for (z, y) <- zs.zip(ys) do
          var s = b; var i = 0
          while i < d do { s += w(i) * z(i); i += 1 }
          val g = (1f / (1f + math.exp(-s).toFloat) - (if y then 1f else 0f)) / n
          i = 0
          while i < d do { gw(i) += g * z(i); i += 1 }
          gb += g
        var i = 0
        while i < d do { w(i) -= lr * (gw(i) + l2 * w(i)); i += 1 }
        b -= lr * gb
      Probe(mean, std, w, b)

  test("bridge-4: closure with a neural relation on Qwen3.5-4B"):
    assume(sys.env.contains("CLOSURE"), "CLOSURE=1 to run")
    val repo = sys.env.getOrElse("BRIDGE_MODEL", "mlx-community/Qwen3.5-4B-MLX-4bit")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true).asInstanceOf[Qwen35]
    val tok = chat.tokenizer
    println(s"model: $repo")
    val layer = sys.env.getOrElse("BRIDGE_LAYER", "12").toInt

    /** the prompt's tokens; per sentence the positions of its first name, its second name and its last token */
    def prompt(inst: Instance, think: Boolean, question: String): (Array[Int], Vector[(Int, Int, Int)]) =
      val marker = "@@ITEMS@@"
      val full = chat.ask(s"Here are ${inst.sentences.size} statements about who is above whom in a company:\n\n" +
        s"$marker\n\n$question", Map("enable_thinking" -> think))
      val m = tok.encode(marker)
      val at = full.indices.find(i => full.slice(i, i + m.length).sameElements(m)).getOrElse(sys.error("no marker"))
      val ids = scala.collection.mutable.ArrayBuffer.from(full.take(at)); val last = Vector.newBuilder[(Int, Int, Int)]
      val nl = tok.encode("\n")
      for ((text, x, y, _), i) <- inst.sentences.zipWithIndex do
        val line = s"${i + 1}. $text"
        val lt = tok.encode(line)
        // the token holding a name's last character, by the decoded lengths
        val ends = lt.scanLeft(0)((n, t) => n + tok.decode(Seq(t)).length).drop(1)
        def tokenAt(c: Int): Int = ids.length + ends.indexWhere(_ > c)
        val fx = line.indexOf(x); val sy = line.indexOf(y, fx + x.length)
        val (px, py) = (tokenAt(fx + x.length - 1), tokenAt(sy + y.length - 1))
        ids ++= lt; last += ((px, py, ids.length - 1)); ids ++= nl
      ids.remove(ids.length - nl.length, nl.length)
      (ids.toArray ++ full.drop(at + m.length), last.result())
    def ask(inst: Instance): String =
      val (x, y) = inst.question
      s"Is $x above $y in the company, directly or through others? Reply yes or no only."

    /** the residual after `layer` at the given positions */
    def hidden(ids: Array[Int], at: Vector[Int]): Vector[Array[Float]] =
      var got = Vector.empty[Array[Float]]
      model.hook = new Qwen3.Hook:
        def after(i: Int, h: _root_.mlx.Arr)(using Scope): _root_.mlx.Arr =
          if i + 1 == layer then
            val d = h.dim(2); val all = astype(h, Dtype.Float32).toFloats
            got = at.map(k => all.slice(k * d, (k + 1) * d))
          h
      val st = model.newState(); val s = new Scope
      try { model.hiddenStates(ints(ids, 1, ids.length)(using s), st, all = true)(using s).eval(); got }
      finally { s.close(); st.close(); model.hook = null }

    /** ASK: the model's yes against no on one sentence, no training — asked in both orders, the difference */
    val yesIds = Seq("yes", "Yes", " yes", " Yes").map(tok.encode).filter(_.length == 1).map(_(0)).distinct
    val noIds = Seq("no", "No", " no", " No").map(tok.encode).filter(_.length == 1).map(_(0)).distinct
    def askOne(text: String, x: String, y: String): Float = askDir(text, x, y) - askDir(text, y, x)
    /** ASK-WHO: the names compared — the logit of x against y after "the superior is" */
    def askWho(text: String, x: String, y: String): Float =
      val ids = chat.ask(s"Statement: \"$text\"\nAccording to this statement alone, who is the superior, $x or $y? " +
        "Reply with the name only.", Map("enable_thinking" -> false))
      val st = model.newState(); val s = new Scope
      try
        val l = astype(model.logits(ints(ids, 1, ids.length)(using s), st)(using s), Dtype.Float32)(using s).toFloats
        def first(n: String) = Seq(n, " " + n).map(tok.encode).map(_(0)).map(l).max
        first(x) - first(y)
      finally { s.close(); st.close() }
    def askDir(text: String, x: String, y: String): Float =
      val ids = chat.ask(s"Statement: \"$text\"\nAccording to this statement alone, is $x the superior of $y? " +
        "Reply yes or no only.", Map("enable_thinking" -> false))
      val st = model.newState(); val s = new Scope
      try
        val l = astype(model.logits(ints(ids, 1, ids.length)(using s), st)(using s), Dtype.Float32)(using s).toFloats
        yesIds.map(l).max - noIds.map(l).max
      finally { s.close(); st.close() }

    def generate(ids: Array[Int], max: Int): String =
      val s = Session(model, ids, max, chat.stops)
      try tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector) finally s.close()
    def yesNo(answer: String): Option[Boolean] =
      val a = answer.replaceAll("(?s)<think>.*?</think>", "").trim.toLowerCase
      if a.startsWith("yes") then Some(true) else if a.startsWith("no") then Some(false)
      else if a.contains("yes") && !a.contains("no") then Some(true)
      else if a.contains("no") && !a.contains("yes") then Some(false) else None

    // the probe: trained on part-0 phrasings, the layer fixed (bridge-1's finding), checked on part 1
    val rnd = new scala.util.Random(11)
    val t0 = System.nanoTime()
    // a sentence's features: the residual at its first name, its second name and its last token
    def features(ids: Array[Int], at: Vector[(Int, Int, Int)]): Vector[Array[Float]] =
      val hs = hidden(ids, at.flatMap((a, b, c) => Vector(a, b, c)))
      hs.grouped(3).map(_.flatten.toArray).toVector
    def states(insts: Vector[Instance]): (Vector[Array[Float]], Vector[Boolean]) =
      val hs = insts.map(i => features.tupled(prompt(i, false, ask(i))))
      (hs.flatten, insts.flatMap(_.sentences.map(_._4)))
    val (tx, ty) = states(Vector.fill(40)(instance(rnd, 15, 0, false)))
    val probe = Probe.train(tx, ty)
    val valInsts = Vector.fill(10)(instance(rnd, 15, 1, false))
    val (vx, vy) = states(valInsts)
    // BOTH: the probe's score and ask's, each divided by its spread on the validate sentences, summed
    def spread(xs: Vector[Float]): Float = math.sqrt(xs.map(x => x * x).sum / xs.size).toFloat
    val sp = spread(vx.map(probe.score))
    val sa = spread(valInsts.flatMap(_.sentences.map((t, x, y, _) => askOne(t, x, y))))
    println(f"spreads: probe $sp%.2f, ask $sa%.2f")
    println(f"probe: ${tx.size} sentences, layer $layer, validate accuracy " +
      f"${vx.zip(vy).count((x, y) => (probe.score(x) > 0) == y) * 100.0 / vy.size}%.1f%%, " +
      f"${(System.nanoTime() - t0) / 1e9}%.0f s")

    val think = sys.env.contains("CLOSURE_THINK")
    val maxLen = 128; val rounds = 5
    val (program, _) = Closure.program(names, maxLen, rounds)
    val gpu = OnMlx(Compile(program))
    println(s"closure program: ${program.layers.size} layers, ${Compile(program).layout.dims} dims, 2^$rounds hops")
    val misses = scala.collection.mutable.Map.empty[(String, String), Int].withDefaultValue(0)
    println("n | probe items | ask items | who items | both items | alone | think | bridge probe | bridge ask | bridge who | bridge both | gold | cycles found | wrong yes explained")
    try
      for n <- Seq(15, 30, 60, 100) do
        val insts = Vector.tabulate(6)(t => instance(rnd, n, 2, t % 3 == 2))
        var items = 0; var probeRight = 0; var askRight = 0; var whoRight = 0; var bw = 0; var bothRight = 0; var bb = 0
        var alone = 0; var thinks = 0; var bp = 0; var ba = 0; var gold = 0
        var cyc = 0; var cycRight = 0; var wrongYes = 0; var explained = 0
        for inst <- insts do
          val (ids, at) = prompt(inst, false, ask(inst))
          val probeScores = features(ids, at).map(probe.score)
          val byProbe = probeScores.map(_ > 0)
          val askScores = inst.sentences.map((t, x, y, _) => askOne(t, x, y))
          val byAsk = askScores.map(_ > 0)
          val byBoth = probeScores.zip(askScores).map((p, a) => p / sp + a / sa > 0)
          val byWho = inst.sentences.map((t, x, y, _) => askWho(t, x, y) > 0)
          val truthFlags = inst.sentences.map(_._4)
          items += n; probeRight += byProbe.zip(truthFlags).count(_ == _); askRight += byAsk.zip(truthFlags).count(_ == _)
          whoRight += byWho.zip(truthFlags).count(_ == _); bothRight += byBoth.zip(truthFlags).count(_ == _)
          def tally(kind: String, flags: Vector[Boolean]): Unit =
            for ((t, x, y, g), r) <- inst.sentences.zip(flags) if r != g do
              misses((kind, t.replace(x, "X").replace(y, "Y"))) += 1
          tally("probe", byProbe); tally("ask", byAsk); tally("who", byWho); tally("both", byBoth)
          val (truth, _, cycles) = Closure.reference(inst.sentences.map((_, x, y, s) => (x, y, s)), inst.question)
          def run(flags: Vector[Boolean]): (Boolean, Set[Int], Set[Int]) =
            val in = Closure.inputs(inst.sentences.zip(flags).map { case ((_, x, y, _), f) => (x, y, f) }, inst.question)
            val recs = gpu.records(program, in)
            val ref = Interpret.run(program, in)
            assertEquals(recs.map(_.filter((k, _) => Set("yes", "on", "cycle")(k))),
              ref.map(_.filter((k, _) => Set("yes", "on", "cycle")(k))), "the GPU equals the reference")
            val last = recs.last
            (last("yes") == Val.F(true), last("on") match { case Val.M(s) => s.map(_.toInt); case _ => Set.empty },
              recs.init.zipWithIndex.filter(_._1("cycle") == Val.F(true)).map(_._2 + 1).toSet)
          val (g, _, gc) = run(truthFlags)
          if g == truth then gold += 1
          if cycles.nonEmpty then { cyc += 1; if gc == cycles then cycRight += 1 }
          val (pb, on, _) = run(byProbe)
          if pb == truth then bp += 1
          else if pb then
            wrongYes += 1
            val misread = byProbe.zip(truthFlags).zipWithIndex.collect { case ((a, b), i) if a != b => i + 1 }.toSet
            if (on & misread).nonEmpty then explained += 1
          if run(byAsk)._1 == truth then ba += 1
          if run(byWho)._1 == truth then bw += 1
          if run(byBoth)._1 == truth then bb += 1
          if yesNo(generate(ids, 8)).contains(truth) then alone += 1
          if think && yesNo(generate(prompt(inst, true, ask(inst))._1, 4000)).contains(truth) then thinks += 1
        println(f"$n%3d | ${probeRight * 100.0 / items}%5.1f%% | ${askRight * 100.0 / items}%5.1f%% | " +
          f"${whoRight * 100.0 / items}%5.1f%% | ${bothRight * 100.0 / items}%5.1f%% | $alone/6 | " +
          f"${if think then s"$thinks/6" else "-"} | $bp/6 | $ba/6 | $bw/6 | $bb/6 | " +
          f"$gold/6 | $cycRight/$cyc | $explained/$wrongYes")
      for kind <- Seq("probe", "ask", "who", "both") do
        println(s"$kind misses: " + misses.toVector.filter(_._1._1 == kind).sortBy(-_._2)
          .map((k, c) => s"$c× \"${k._2}\"").mkString("; "))
    finally
      gpu.close(); model.close()
