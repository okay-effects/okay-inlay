package okay.inlay.gpu

import _root_.mlx.{Dtype, Scope}
import _root_.mlx.Mlx.*
import _root_.mlx.llm.{Chat, Model, Models, Qwen3}
import okay.inlay.domain.{Kit, Slot, Units}

/**
 * THE SECOND FAMILY ON THE BUS (specs/domains.md §4): units and the calendar read off bus v1's states — Qwen3-0.6B-4bit
 * cut19, the residual after layer 19 at the prompt's last token — by linear probes per slot (one against the rest
 * per choice), trained on the domain's pairs, measured by the kit on held-out pairs against the keyword baseline.
 * DOMAINS=1 to run; DOMAIN_MODEL, DOMAIN_LAYER, DOMAIN_TRAIN, DOMAIN_TEST.
 */
class DomainBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")

  final class Probe(mean: Array[Float], std: Array[Float], w: Array[Float], b: Float):
    def score(h: Array[Float]): Float =
      var s = b; var i = 0
      while i < w.length do { s += w(i) * (h(i) - mean(i)) / std(i); i += 1 }
      s

  /** logistic regression on standardized states, by gradient descent */
  def train(xs: Vector[Array[Float]], ys: Vector[Boolean], steps: Int = 300, lr: Float = 0.1f, l2: Float = 1e-2f): Probe =
    val d = xs.head.length; val n = xs.size
    val mean = Array.tabulate(d)(i => xs.map(_(i)).sum / n)
    val std = Array.tabulate(d)(i => math.max(math.sqrt(xs.map(x => (x(i) - mean(i)) * (x(i) - mean(i))).sum / n),
      1e-3).toFloat)
    val zs = xs.map(x => Array.tabulate(d)(i => (x(i) - mean(i)) / std(i)))
    val w = Array.fill(d)(0f); var b = 0f
    // the positives weighted up to the negatives' mass: a choice seen in few pairs still learns
    val pos = ys.count(identity).max(1); val wPos = (n - pos).toFloat / pos
    for _ <- 1 to steps do
      val gw = Array.fill(d)(0f); var gb = 0f
      for (z, y) <- zs.zip(ys) do
        var s = b; var i = 0
        while i < d do { s += w(i) * z(i); i += 1 }
        val p = 1f / (1f + math.exp(-s).toFloat)
        val g = (p - (if y then 1f else 0f)) * (if y then wPos else 1f) / n
        i = 0
        while i < d do { gw(i) += g * z(i); i += 1 }
        gb += g
      var i = 0
      while i < d do { w(i) -= lr * (gw(i) + l2 * w(i)); i += 1 }
      b -= lr * gb
    Probe(mean, std, w, b)

  test("units and the calendar on bus v1: probes per slot against the keyword baseline"):
    assume(sys.env.contains("DOMAINS"), "DOMAINS=1 to run")
    val repo = sys.env.getOrElse("DOMAIN_MODEL", "local/Qwen3-0.6B-4bit-cut19")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true)
    val layer = sys.env.getOrElse("DOMAIN_LAYER", "19").toInt
    println(s"model: $repo, layer $layer")

    /** the bus: the residual after `layer` at the prompt's last token */
    def bus(question: String): Array[Float] =
      val ids = chat.ask(question, Map("enable_thinking" -> false))
      var got: Array[Float] = null
      val cap = new Qwen3.Hook:
        def after(i: Int, h: _root_.mlx.Arr)(using Scope): _root_.mlx.Arr =
          if i + 1 == layer then
            val d = h.dim(2); val all = astype(h, Dtype.Float32).toFloats
            got = all.slice((ids.length - 1) * d, ids.length * d)
          h
      val st = model.newState(); val s = new Scope
      model.asInstanceOf[Qwen3].hook = cap
      try { model.hiddenStates(ints(ids, 1, ids.length)(using s), st, all = false)(using s).eval(); got }
      finally { s.close(); st.close(); model.asInstanceOf[Qwen3].hook = null }

    val nTrain = sys.env.getOrElse("DOMAIN_TRAIN", "600").toInt; val nTest = sys.env.getOrElse("DOMAIN_TEST", "300").toInt
    val trainPairs = Kit.pairs(Units)(Units, nTrain, 101, Vector("en", "ru"))
    val testPairs = Kit.pairs(Units)(Units, nTest, 202, Vector("en", "ru"))
    val t0 = System.nanoTime()
    val trainX = trainPairs.map(p => bus(p.question)); val testX = testPairs.map(p => bus(p.question))
    println(f"states: ${trainX.size + testX.size} prompts in ${(System.nanoTime() - t0) / 1e9}%.0f s")

    // a probe per slot and choice, trained on the pairs whose point uses the slot
    val probes: Map[String, Map[String, Probe]] = Units.slots(Units).filterNot(_.copied).map { slot =>
      val uses = trainPairs.zip(trainX).filter((p, _) => Units.parts(p.point).contains(slot.name))
      slot.name -> slot.choices.filter(c => uses.exists((p, _) => Units.parts(p.point)(slot.name) == c)).map { c =>
        c -> train(uses.map(_._2), uses.map((p, _) => Units.parts(p.point)(slot.name) == c))
      }.toMap
    }.toMap
    val states = (testPairs.map(_.question) zip testX).toMap
    object onBus extends Kit.Reader[Units.type]:
      def read(question: String, s: Units.type, slot: Slot): Map[String, Double] =
        val h = states(question)
        val scores = probes.getOrElse(slot.name, Map.empty).map((c, p) => c -> p.score(h).toDouble)
        // probabilities by a softmax over the choices' scores
        if scores.isEmpty then Map.empty else
          val top = scores.values.max; val e = scores.map((c, x) => c -> math.exp(x - top)); val z = e.values.sum
          e.map((c, x) => c -> x / z)

    val keywords = new okay.inlay.domain.UnitsSuite().keywords
    val base = Kit.measure(Units)(Units, testPairs, keywords, Units.copier)
    println(s"keywords: ${base.text}")
    val bus0 = Kit.measure(Units)(Units, testPairs, onBus, Units.copier)
    println(s"on the bus: ${bus0.text}")
    for m <- Vector(0.2, 0.5) do
      println(s"on the bus, margin $m: ${Kit.measure(Units)(Units, testPairs, onBus, Units.copier, m).text}")
    // THE HYBRID (okay-desk's lesson): a unit the question names is an exact fact — the candidates are the units
    // found in the text (by the keyword reader's words); the probe decides their role, from or to
    object hybrid extends Kit.Reader[Units.type]:
      def read(question: String, s: Units.type, slot: Slot): Map[String, Double] =
        val p = onBus.read(question, s, slot)
        if slot.name != "from" && slot.name != "to" then p
        else
          val named = Vector("from", "to").flatMap(n => keywords.read(question, s, Slot(n, slot.choices)).keys).toSet
          val kept = p.filter((c, _) => named.contains(c))
          if kept.isEmpty then p else { val z = kept.values.sum; kept.map((c, x) => c -> x / z) }
    val hy = Kit.measure(Units)(Units, testPairs, hybrid, Units.copier)
    println(s"hybrid: ${hy.text}")
    for m <- Vector(0.2, 0.5) do
      println(s"hybrid, margin $m: ${Kit.measure(Units)(Units, testPairs, hybrid, Units.copier, m).text}")
    model.close()
