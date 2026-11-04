package okay.inlay.gpu

import _root_.mlx.{Dtype, Scope}
import _root_.mlx.Mlx.*
import _root_.mlx.llm.{Chat, Model, Models, Port, Qwen3, Qwen35, Session}
import okay.inlay.*

/**
 * BRIDGE-1 (specs/bridge.md §4): a count with a neural predicate. A list of short reviews; "how many are negative".
 * The predicate is the model's (a linear probe E on its hidden state at each review's last token), the count is a
 * `Count` head in weights (C, on the GPU), the answer's digits go out through the decode port (D). E trained on
 * lists of 10, measured at 5..80 against the model alone and the gold ceiling. Opt-in: BRIDGE=1; the model
 * BRIDGE_MODEL (default Qwen3.5-4B-MLX-4bit); BRIDGE_THINK=1 adds the model's thinking arm.
 */
class BridgeBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "min")

  // ---- the items: reviews with gold labels; the phrases split so that test phrases are never trained on
  val products = Vector("blender", "headphones", "backpack", "kettle", "keyboard", "jacket", "lamp", "router",
    "tent", "watch", "printer", "mattress", "scooter", "camera", "thermos", "drill")
  val positive = Vector(
    "The %s works exactly as promised, I use it every day.", "Great %s, worth every cent.",
    "I was skeptical, but this %s won me over in a week.", "Solid %s; nothing fancy, nothing broken, does the job.",
    "My second %s from them and still happy.", "The %s arrived early and was better than the pictures.",
    "Not a single problem with the %s after three months.", "This %s is the best purchase I made this year.",
    "Quiet, sturdy %s. Recommended.", "The %s feels premium for the price.",
    "I would buy this %s again without hesitation.", "Honestly the %s exceeded what I expected.",
    "The %s is small, light and surprisingly powerful.", "Five stars for the %s, the support team was kind too.",
    "Hard to find anything wrong with this %s.", "My kids love the %s and so do I.",
    "The %s was easy to set up and just works.", "Good %s, fair price, fast delivery.",
    "After a year the %s still looks new.", "This %s replaced a far more expensive one and I don't miss it.",
    "Not cheap, but the %s is worth it.", "The %s is not perfect, but I like it a lot.",
    "Bought the %s as a gift; they were thrilled.", "The %s does more than I need, in a good way.",
    "Reliable %s, no complaints at all.", "The %s is a pleasure to use.",
    "I can't imagine the trip without this %s.", "Finally a %s that lasts.",
    "The %s is better than my old one in every way.", "Excellent %s for the money.")
  val negative = Vector(
    "The %s broke on the second day.", "Returned the %s; it never worked properly.",
    "Cheap plastic %s, not what the photos show.", "The %s is loud and gets hot, avoid it.",
    "Two months in and the %s is already falling apart.", "I regret buying this %s.",
    "The %s arrived damaged and support ignored my emails.", "Not worth the money, the %s is a disappointment.",
    "The %s stopped charging after a week.", "This %s is the worst I have owned.",
    "The %s looks fine but the quality is awful.", "Save your money, this %s is junk.",
    "The %s was missing parts and the manual is useless.", "I expected more from the %s; it feels flimsy.",
    "The %s smells of chemicals and the smell never went away.", "The %s is nothing like what was advertised.",
    "One star for the %s, it failed within hours.", "The %s is overpriced for what it does.",
    "My %s came with a cracked case.", "The %s is not bad, it is terrible.",
    "The %s stopped working and nobody answers the phone.", "Do not buy this %s, mine lasted a week.",
    "The %s is slow, clumsy and badly built.", "The %s rattles and the buttons stick.",
    "I would not recommend this %s to anyone.", "The %s is a waste of money.",
    "The %s never worked out of the box.", "Awful %s, worse than the one it replaced.",
    "The %s died right after the warranty ended.", "The %s was fine for a day, then it quit.")
  /** the phrases in three parts: 0 train (12 each), 1 validate (6: the layer chosen on them), 2 test (12) */
  def part(ps: Vector[String], k: Int): Vector[String] = k match
    case 0 => ps.take(12)
    case 1 => ps.slice(12, 18)
    case _ => ps.drop(18)

  /** a list of n reviews (text, negative?) from the phrases of a part */
  def list(n: Int, rnd: scala.util.Random, k: Int): Vector[(String, Boolean)] =
    val pos = part(positive, k); val neg = part(negative, k)
    Vector.fill(n) {
      val bad = rnd.nextBoolean()
      val phrase = if bad then neg(rnd.nextInt(neg.size)) else pos(rnd.nextInt(pos.size))
      (phrase.format(products(rnd.nextInt(products.size))), bad)
    }

  // ---- the probe E: logistic regression on standardized hidden states, trained by gradient descent
  final class Probe(val mean: Array[Float], val std: Array[Float], val w: Array[Float], val b: Float):
    def score(h: Array[Float]): Float =
      var s = b; var i = 0
      while i < w.length do { s += w(i) * (h(i) - mean(i)) / std(i); i += 1 }
      s
    def hit(h: Array[Float]): Boolean = score(h) > 0

  object Probe:
    def train(xs: Vector[Array[Float]], ys: Vector[Boolean], steps: Int = 400, lr: Float = 0.05f,
        l2: Float = sys.env.getOrElse("BRIDGE_L2", "1e-2").toFloat): Probe =
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
          val p = 1f / (1f + math.exp(-s).toFloat)
          val g = (p - (if y then 1f else 0f)) / n
          i = 0
          while i < d do { gw(i) += g * z(i); i += 1 }
          gb += g
        var i = 0
        while i < d do { w(i) -= lr * (gw(i) + l2 * w(i)); i += 1 }
        b -= lr * gb
      Probe(mean, std, w, b)

  // ---- C: the count in weights
  val hitF = Cat("h", Vector("1")); val askF = Cat("q", Vector("1"))
  val countF = Cat("count", (0 to 100).map(_.toString).toVector)
  val program = Program(Seq(hitF, askF), Seq(Layer(heads = Seq(Count(countF, Seq(askF), Seq(hitF))))), maxLen = 128)
  def countInputs(hits: Vector[Boolean]): Vector[Interpret.Record] =
    hits.map(h => Map("h" -> Val.C(if h then Some("1") else None))) :+ Map("q" -> Val.C(Some("1")))
  def counted(gpu: OnMlx, hits: Vector[Boolean]): Int =
    gpu.last(program, countInputs(hits))("count") match
      case Val.C(Some(v)) => v.toInt
      case other => sys.error(s"count read $other")

  // ---- D: the digits forced through the decode port
  final class Forced(seq: Array[Int]) extends Port:
    private var i = 0
    def allowed: Option[Array[Int]] = if i < seq.length then Some(Array(seq(i))) else None
    def took(token: Int): Unit = i += 1

  test("bridge-1: a count with a neural predicate on Qwen3.5-4B"):
    assume(sys.env.contains("BRIDGE"), "BRIDGE=1 to run")
    val repo = sys.env.getOrElse("BRIDGE_MODEL", "mlx-community/Qwen3.5-4B-MLX-4bit")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true)
    val tok = chat.tokenizer
    val end = tok.id("<|im_end|>")
    println(s"model: $repo")

    /** the prompt's tokens with each review's last token position */
    def prompt(items: Vector[(String, Boolean)], think: Boolean): (Array[Int], Vector[Int]) =
      val marker = "@@ITEMS@@"
      val full = chat.ask(s"Here are ${items.size} short product reviews:\n\n$marker\n\nHow many of them are " +
        "negative? Reply with the number only.", Map("enable_thinking" -> think))
      val m = tok.encode(marker)
      val at = full.indices.find(i => full.slice(i, i + m.length).sameElements(m)).getOrElse(sys.error("no marker"))
      val prefix = full.take(at); val suffix = full.drop(at + m.length)
      val ids = scala.collection.mutable.ArrayBuffer.from(prefix); val last = Vector.newBuilder[Int]
      val nl = tok.encode("\n")
      for ((text, _), i) <- items.zipWithIndex do
        ids ++= tok.encode(s"${i + 1}. $text"); last += ids.length - 1; ids ++= nl
      ids.remove(ids.length - nl.length, nl.length)
      (ids.toArray ++ suffix, last.result())

    val probeLayers = sys.env.getOrElse("BRIDGE_LAYERS", "8,12,16,20,24,28,final").split(",").toVector
    /** the hidden states at the given positions after each probe layer (the residual stream; "final": normed) */
    def hidden(ids: Array[Int], at: Vector[Int]): Map[String, Vector[Array[Float]]] =
      val got = scala.collection.mutable.Map.empty[String, Vector[Array[Float]]]
      def take(h: _root_.mlx.Arr)(using Scope): Vector[Array[Float]] =
        val d = h.dim(2); val all = astype(h, Dtype.Float32).toFloats
        at.map(i => all.slice(i * d, (i + 1) * d))
      val cap = new Qwen3.Hook:
        def after(i: Int, h: _root_.mlx.Arr)(using Scope): _root_.mlx.Arr =
          if probeLayers.contains((i + 1).toString) then got((i + 1).toString) = take(h)
          h
      val st = model.newState(); val s = new Scope
      model.asInstanceOf[Qwen35].hook = cap
      try
        val h = model.hiddenStates(ints(ids, 1, ids.length)(using s), st, all = true)(using s)
        if probeLayers.contains("final") then got("final") = take(h)(using s)
        got.toMap
      finally { s.close(); st.close(); model.asInstanceOf[Qwen35].hook = null }

    def generate(ids: Array[Int], max: Int, port: Port | Null): String =
      val s = Session(model, ids, max, chat.stops, port = port)
      try tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector) finally s.close()
    def number(answer: String): Option[Int] =
      val a = answer.replaceAll("(?s)<think>.*?</think>", "")
      "\\d+".r.findAllIn(a).toVector.lastOption.map(_.toInt)

    // E: a probe per layer trained on lists of 10 (train phrases); the layer chosen on the validate phrases
    val rnd = new scala.util.Random(7)
    val trainLists = Vector.fill(sys.env.getOrElse("BRIDGE_TRAIN", "60").toInt)(list(10, rnd, 0))
    val valLists = Vector.fill(20)(list(10, rnd, 1))
    val t0 = System.nanoTime()
    def states(lists: Vector[Vector[(String, Boolean)]]): (Map[String, Vector[Array[Float]]], Vector[Boolean]) =
      val hs = lists.map { items => val (ids, at) = prompt(items, think = false); hidden(ids, at) }
      (probeLayers.map(l => l -> hs.flatMap(_(l))).toMap, lists.flatMap(_.map(_._2)))
    val (trainX, trainY) = states(trainLists)
    val (valX, valY) = states(valLists)
    val probes = probeLayers.map(l => l -> Probe.train(trainX(l), trainY)).toMap
    val scored = probeLayers.map(l => l -> valX(l).zip(valY).count((x, y) => probes(l).hit(x) == y) * 100.0 / valY.size)
    println(f"probes: trained on ${trainY.size} items from ${trainLists.size} lists of 10, " +
      f"validate accuracy by layer: ${scored.map((l, a) => f"$l $a%.1f%%").mkString(", ")}, " +
      f"${(System.nanoTime() - t0) / 1e9}%.0f s")
    val layer = sys.env.getOrElse("BRIDGE_LAYER", scored.maxBy(_._2)._1)
    val probe = probes(layer)
    println(s"layer chosen: $layer")

    val gpu = OnMlx(Compile(program))
    val think = sys.env.contains("BRIDGE_THINK")
    val misses = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    println("n | lists | probe items | bridge right | alone right | think right | ceiling | mean |err| bridge | alone (no answer)")
    try
      for n <- Seq(5, 10, 20, 40, 80) do
        val lists = Vector.fill(6)(list(n, rnd, 2))
        var items = 0; var itemsRight = 0; var bridgeRight = 0; var aloneRight = 0; var thinkRight = 0
        var ceiling = 0; var absErr = 0; var aloneErr = 0; var aloneMissing = 0
        for items0 <- lists do
          val gold = items0.map(_._2); val truth = gold.count(identity)
          val (ids, at) = prompt(items0, think = false)
          val hits = hidden(ids, at)(layer).map(probe.hit)
          items += n; itemsRight += hits.zip(gold).count(_ == _)
          for ((t, g), h) <- items0.zip(hits) if h != g do misses(t.replaceAll(products.mkString("|"), "%s")) += 1
          // C over the probe's flags; the law: C over the gold flags is the truth
          val c = counted(gpu, hits)
          assertEquals(c, hits.count(identity), "the count is exactly the probe's hits — C adds no error")
          if counted(gpu, gold) == truth then ceiling += 1
          // D: the digits forced; the generation is the bridge's answer
          val forced = generate(ids, 8, Forced(tok.encode(c.toString) :+ end))
          val bridge = number(forced)
          if bridge.contains(truth) then bridgeRight += 1
          absErr += math.abs(c - truth)
          val alone = number(generate(ids, 16, null))
          if alone.contains(truth) then aloneRight += 1
          alone match
            case Some(a) => aloneErr += math.abs(a - truth)
            case None => aloneMissing += 1
          if think then
            val (tids, _) = prompt(items0, think = true)
            if number(generate(tids, 3000, null)).contains(truth) then thinkRight += 1
        println(f"$n%2d | ${lists.size} | ${itemsRight * 100.0 / items}%5.1f%% | $bridgeRight/${lists.size} | " +
          f"$aloneRight/${lists.size} | ${if think then s"$thinkRight/${lists.size}" else "-"} | " +
          f"$ceiling/${lists.size} | ${absErr.toDouble / lists.size}%.2f | " +
          f"${aloneErr.toDouble / math.max(1, lists.size - aloneMissing)}%.2f ($aloneMissing)")
      println("probe misses: " + misses.toVector.sortBy(-_._2).map((t, c) => s"$c× \"$t\"").mkString("; "))
    finally
      gpu.close(); model.close()
