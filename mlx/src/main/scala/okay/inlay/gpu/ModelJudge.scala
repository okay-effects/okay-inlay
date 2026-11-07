package okay.inlay.gpu

import _root_.mlx.{Dtype, Scope}
import _root_.mlx.Mlx.*
import _root_.mlx.llm.{Chat, Qwen3, Qwen35}
import okay.inlay.bridge.Edge

/**
 * A MODEL AS THE BRIDGE'S READER (specs/bridge.md §8b, docs/effects.md): the judgement "the first person named is the
 * superior" read from a language model, one sentence at a time — handlers of the bridge's `Judge` through
 * `Bridge.reading`. Three readers, bridge-4's:
 *
 *  - ASK — no training: the model asked, in both orders, whether x is the superior of y; yes against no;
 *  - PROBE — a linear probe on the residual after `layer`, at the two names and the sentence's last token, trained on
 *    phrasings other than those it reads;
 *  - BOTH — the two summed, each divided by its spread: two readers that fail on different phrasings.
 *
 * The names are found in the sentence (the first two of `names`, by position); a sentence naming fewer is not read.
 */
final class ModelJudge(chat: Chat, model: Qwen35, layer: Int, names: Vector[String]):
  private val tok = chat.tokenizer
  private val yesIds = Seq("yes", "Yes", " yes", " Yes").map(tok.encode).filter(_.length == 1).map(_(0)).distinct
  private val noIds = Seq("no", "No", " no", " No").map(tok.encode).filter(_.length == 1).map(_(0)).distinct

  /** the two names a sentence states a relation between, in their order */
  def pair(sentence: String): Option[(String, String)] =
    val found = names.flatMap(n => s"\\b$n\\b".r.findFirstMatchIn(sentence).map(m => (m.start, n))).sortBy(_._1)
    Option.when(found.size >= 2)((found(0)._2, found(1)._2))

  private def logits(ids: Array[Int]): Array[Float] =
    val st = model.newState(); val s = new Scope
    try astype(model.logits(ints(ids, 1, ids.length)(using s), st)(using s), Dtype.Float32)(using s).toFloats
    finally { s.close(); st.close() }

  /** ASK, one direction: yes against no on "is x the superior of y" */
  def askDir(text: String, x: String, y: String): Float =
    val l = logits(chat.ask(s"Statement: \"$text\"\nAccording to this statement alone, is $x the superior of $y? " +
      "Reply yes or no only.", Map("enable_thinking" -> false)))
    yesIds.map(l).max - noIds.map(l).max
  /** ASK: both directions, the difference — > 0: x is the superior */
  def ask(text: String, x: String, y: String): Float = askDir(text, x, y) - askDir(text, y, x)

  /** the residual after `layer` at the sentence's two names and its last token, the sentence alone in the prompt */
  def features(text: String, x: String, y: String): Array[Float] =
    val head = "Statement: \""
    val ids = chat.ask(s"$head$text\"", Map("enable_thinking" -> false))
    // the token holding a character of the decoded prompt: by the decoded lengths, token by token
    val ends = ids.scanLeft(0)((n, t) => n + tok.decode(Seq(t)).length).drop(1)
    val decoded = tok.decode(ids.toSeq)
    val from = decoded.indexOf(head + text) + head.length
    def tokenAt(c: Int) = ends.indexWhere(_ > c)
    val fx = text.indexOf(x); val sy = text.indexOf(y, fx + x.length)
    val at = Vector(tokenAt(from + fx + x.length - 1), tokenAt(from + sy + y.length - 1),
      tokenAt(from + text.length - 1))
    var got = Vector.empty[Array[Float]]
    model.hook = new Qwen3.Hook:
      def after(i: Int, h: _root_.mlx.Arr)(using Scope): _root_.mlx.Arr =
        if i + 1 == layer then
          val d = h.dim(2); val all = astype(h, Dtype.Float32).toFloats
          got = at.map(k => all.slice(k * d, (k + 1) * d))
        h
    val st = model.newState(); val s = new Scope
    try { model.hiddenStates(ints(ids, 1, ids.length)(using s), st, all = true)(using s).eval() }
    finally { s.close(); st.close(); model.hook = null }
    got.flatten.toArray

  /** a probe trained on sentences (text, x, y, x is the superior) */
  def train(sentences: Vector[(String, String, String, Boolean)]): ModelJudge.Probe =
    ModelJudge.Probe.train(sentences.map((t, x, y, _) => features(t, x, y)), sentences.map(_._4))

  /** the readers, as `Bridge.reading` takes them */
  def asking: String => Option[Edge] = s => pair(s).map((x, y) => Edge(x, y, ask(s, x, y) > 0))
  def probing(p: ModelJudge.Probe): String => Option[Edge] =
    s => pair(s).map((x, y) => Edge(x, y, p.score(features(s, x, y)) > 0))
  /** BOTH: each score over its spread (on sentences it was not trained on), summed */
  def both(p: ModelJudge.Probe, probeSpread: Float, askSpread: Float): String => Option[Edge] =
    s => pair(s).map((x, y) => Edge(x, y, p.score(features(s, x, y)) / probeSpread + ask(s, x, y) / askSpread > 0))

object ModelJudge:
  /** a logistic probe over standardised features */
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

  /** the spread of scores: their root mean square */
  def spread(xs: Vector[Float]): Float = math.sqrt(xs.map(x => x * x).sum / xs.size).toFloat
