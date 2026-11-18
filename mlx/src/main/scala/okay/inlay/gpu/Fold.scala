package okay.inlay.gpu

import _root_.mlx.{Arr, Dtype, Mlx, Scope}
import _root_.mlx.Mlx.*
import _root_.mlx.llm.{Qwen3Config, SafeTensors}
import java.nio.file.{Files, Path}

/** small dense linear algebra on the JVM, in doubles: what the GPU's k × k products are finished with */
object Spectrum:
  /** the lower factor L of a symmetric g = L Lᵀ (row-major, k × k); a pivot below 1e-6 of the largest diagonal is
   * raised to it (a direction the columns do not span: its column stays bounded, not a division by zero) */
  def cholesky(g: Array[Double], k: Int): Array[Double] =
    val l = new Array[Double](k * k)
    var top = 0.0
    var j = 0
    while j < k do { top = math.max(top, g(j * k + j)); j += 1 }
    val floor = math.max(top * 1e-6, Double.MinPositiveValue)
    j = 0
    while j < k do
      var d = g(j * k + j)
      var p = 0
      while p < j do { d -= l(j * k + p) * l(j * k + p); p += 1 }
      val pivot = math.sqrt(math.max(d, floor))
      l(j * k + j) = pivot
      var i = j + 1
      while i < k do
        var s = g(i * k + j)
        p = 0
        while p < j do { s -= l(i * k + p) * l(j * k + p); p += 1 }
        l(i * k + j) = s / pivot
        i += 1
      j += 1
    l

  /** the inverse of a lower triangular l (row-major, k × k) */
  def invLower(l: Array[Double], k: Int): Array[Double] =
    val x = new Array[Double](k * k)
    var c = 0
    while c < k do
      x(c * k + c) = 1.0 / l(c * k + c)
      var i = c + 1
      while i < k do
        var s = 0.0
        var p = c
        while p < i do { s -= l(i * k + p) * x(p * k + c); p += 1 }
        x(i * k + c) = s / l(i * k + i)
        i += 1
      c += 1
    x

  /** a symmetric matrix's eigenvalues, largest first, and its eigenvectors as the columns of a row-major k × k
   * matrix in the same order — cyclic Jacobi rotations until the off-diagonal part is 1e-22 of the whole */
  def eigen(a0: Array[Double], k: Int): (Array[Double], Array[Double]) =
    val a = a0.clone
    val v = new Array[Double](k * k)
    var i = 0
    while i < k do { v(i * k + i) = 1.0; i += 1 }
    def off: Double =
      var s = 0.0
      var p = 0
      while p < k do
        var q = 0
        while q < k do { if p != q then s += a(p * k + q) * a(p * k + q); q += 1 }
        p += 1
      s
    val whole = a.iterator.map(x => x * x).sum
    var sweeps = 0
    while sweeps < 100 && off > 1e-22 * whole do
      var p = 0
      while p < k - 1 do
        var q = p + 1
        while q < k do
          val apq = a(p * k + q)
          if apq != 0.0 then
            val theta = (a(q * k + q) - a(p * k + p)) / (2 * apq)
            val t = (if theta >= 0 then 1.0 else -1.0) / (math.abs(theta) + math.sqrt(theta * theta + 1))
            val c = 1 / math.sqrt(t * t + 1)
            val s = t * c
            i = 0
            while i < k do
              val (x, y) = (a(i * k + p), a(i * k + q))
              a(i * k + p) = c * x - s * y; a(i * k + q) = s * x + c * y
              i += 1
            i = 0
            while i < k do
              val (x, y) = (a(p * k + i), a(q * k + i))
              a(p * k + i) = c * x - s * y; a(q * k + i) = s * x + c * y
              i += 1
            i = 0
            while i < k do
              val (x, y) = (v(i * k + p), v(i * k + q))
              v(i * k + p) = c * x - s * y; v(i * k + q) = s * x + c * y
              i += 1
          q += 1
        p += 1
      sweeps += 1
    val order = Array.tabulate(k)(identity).sortBy(j => -a(j * k + j))
    val vs = new Array[Double](k * k)
    for (j, to) <- order.zipWithIndex; r <- 0 until k do vs(r * k + to) = v(r * k + j)
    (order.map(j => a(j * k + j)), vs)

/**
 * FOLDING ADJACENT LAYERS, UNTRAINED (specs/fold.md; arXiv 2611.18113's fold before any training): layer b's seven
 * linear maps replaced by layer a's plus a rank-r correction — the truncated SVD of W_b − W_a — its own norms kept.
 * The measures: how much of W_b − W_a a rank holds, how alike two layers' residual updates are, how far a folded
 * pair's output is from the original's, the folded model's perplexity.
 *
 * A Qwen3 from an MLX 4-bit file: every weight dequantised to float32 when used (the file's quantisation is part of
 * what is measured: `noise` estimates its share), the pass in float32 — the same blocks as mlx-scala's `Qwen3`.
 */
final class Fold private (val config: Qwen3Config, t: Map[String, Arr], own: Scope):
  import config.*
  import Fold.*
  private val scale = (1.0 / math.sqrt(headDim)).toFloat
  private def prefix(l: Int, m: String) =
    if attentionMaps(m) then s"model.layers.$l.self_attn.${m}_proj" else s"model.layers.$l.mlp.${m}_proj"
  private def dense(p: String)(using Scope): Arr =
    f32(dequantize(t(s"$p.weight"), t(s"$p.scales"), t(s"$p.biases"), groupSize, bits))

  /** layer l's map m, [out, in] in float32 */
  def weight(l: Int, m: String)(using Scope): Arr = dense(prefix(l, m))

  /** the energy ‖W − Ŵ‖² the file's quantisation is expected to have put into this map: affine rounding to steps of
   * `scale`, an error uniform within a step — scale² / 12 an element (an estimate: the unquantised W is not here) */
  def noise(l: Int, m: String): Double =
    val s = new Scope
    try
      val sc = f32(t(s"${prefix(l, m)}.scales"))(using s)
      total(sc)(using s) * groupSize / 12.0
    finally s.close()

  /** one block on the residual stream x [1, n, H]: the maps from `w`, the four norms layer `norms`' own */
  def block(x: Arr, w: String => Arr, norms: Int)(using Scope): Arr =
    val n = x.dim(1)
    def nw(name: String) = f32(t(s"model.layers.$norms.$name.weight"))
    def lin(a: Arr, m: String) = matmul(a, transpose(w(m), 1, 0))
    val xn = rmsNorm(x, nw("input_layernorm"), eps)
    val q = rope(transpose(rmsNorm(reshape(lin(xn, "q"), 1, n, heads, headDim), nw("self_attn.q_norm"), eps),
      0, 2, 1, 3), headDim, false, ropeTheta, 1f, 0)
    val k = rope(transpose(rmsNorm(reshape(lin(xn, "k"), 1, n, kvHeads, headDim), nw("self_attn.k_norm"), eps),
      0, 2, 1, 3), headDim, false, ropeTheta, 1f, 0)
    val v = transpose(reshape(lin(xn, "v"), 1, n, kvHeads, headDim), 0, 2, 1, 3)
    val att = attention(q, k, v, scale, causal = n > 1)
    val a = add(x, lin(reshape(transpose(att, 0, 2, 1, 3), 1, n, heads * headDim), "o"))
    val mm = rmsNorm(a, nw("post_attention_layernorm"), eps)
    add(a, lin(multiply(silu(lin(mm, "gate")), lin(mm, "up")), "down"))

  /** the embeddings of `ids`: [1, n, H] */
  def embed(ids: Array[Int])(using Scope): Arr =
    val i = ints(ids, ids.length)
    val p = "model.embed_tokens"
    reshape(f32(dequantize(take(t(s"$p.weight"), i, 0), take(t(s"$p.scales"), i, 0), take(t(s"$p.biases"), i, 0),
      groupSize, bits)), 1, ids.length, hidden)

  /** the residual stream before each layer and after the last — layers + 1 arrays [1, n, H], computed, in `keep`;
   * layer l's maps from `source` (the model's own by default) */
  def states(ids: Array[Int], keep: Scope, source: Source = plain): Vector[Arr] =
    var h = Scope(keep)(embed(ids).eval())
    val out = Vector.newBuilder[Arr]
    out += h
    for l <- 0 until layers do
      h = Scope(keep) { val s = summon[Scope]; block(h, m => source(l, m, s), l).eval() }
      out += h
    out.result()

  /** the next token's negative log-likelihood at each position but the last (n − 1 numbers), from the residual
   * stream after the last layer */
  def nll(last: Arr, ids: Array[Int])(using Scope): Array[Double] =
    val p = "model.embed_tokens"
    nllOf(quantizedMatmul(rmsNorm(last, f32(t("model.norm.weight")), eps), t(s"$p.weight"), t(s"$p.scales"),
      t(s"$p.biases"), transpose = true, groupSize, bits), ids)

  /** the model's own maps */
  val plain: Source = (l, m, s) => weight(l, m)(using s)

  /** layer b's map m when folded onto layer a: W_a + the rank-r part of W_b − W_a in `basis` */
  def folded(a: Int, b: Int, m: String, basis: Basis, r: Int)(using Scope): Arr =
    val wa = weight(a, m)
    add(wa, lowRank(subtract(weight(b, m), wa), basis, r))

  /** the weights' measures of the pair (a, b), map by map, and each map's basis (in the scope given) */
  def pair(a: Int, b: Int, ranks: Seq[Int], over: Int, iters: Int)(using s: Scope): Vector[(MapRow, Basis)] =
    maps.map { m =>
      val tmp = new Scope
      try
        given Scope = tmp
        val (wa, wb) = (weight(a, m), weight(b, m))
        val basis = Fold.basis(subtract(wb, wa), ranks.max, over, iters, seed = 7L * a + b)
        val (a2, b2) = (total(wa), total(wb))
        val cos = if a2 * b2 == 0 then 0.0 else dot(wa, wb) / math.sqrt(a2 * b2)
        basis.u.moveTo(s)
        (MapRow(a, b, m, basis.u.dim(0), basis.total, b2, cos, noise(a, m) + noise(b, m),
          ranks.map(basis.rel).toVector, basis.defect), basis)
      finally tmp.close()
    }

  def close(): Unit =
    own.close()
    Mlx.clearCache()

object Fold:
  val maps: Vector[String] = Vector("q", "k", "v", "o", "gate", "up", "down")
  private val attentionMaps = Set("q", "k", "v", "o")
  /** where layer l's map m comes from, in a scope */
  type Source = (Int, String, Scope) => Arr

  /** a pair's map: the small side of W, ‖W_b − W_a‖², ‖W_b‖², the cosine of W_a and W_b, the quantisation's expected
   * energy in the difference, the share of ‖W_b − W_a‖² left out at each rank, the basis' distance from orthonormal */
  final case class MapRow(a: Int, b: Int, map: String, small: Int, d2: Double, wb2: Double, cos: Double, noise: Double,
      rel: Vector[Double], defect: Double)

  /** the leading subspace of a matrix d on its smaller side: `u` [m, c], orthonormal columns ordered by the energy
   * of d they hold (`energy`: ‖uⱼᵀ d‖², or ‖d uⱼ‖² when the smaller side is d's columns); `total` = ‖d‖² */
  final class Basis(val u: Arr, val energy: Array[Double], val rows: Boolean, val total: Double, val defect: Double):
    /** the share of ‖d‖² the rank-r part leaves out */
    def rel(r: Int): Double = if total == 0 then 0 else math.max(0.0, 1 - energy.take(r).sum / total)

  def f32(a: Arr)(using Scope): Arr = if a.dtype == Dtype.Float32 then a else astype(a, Dtype.Float32)

  /** Σ a·b over every element (`total`: Σ a²), the last axis summed on the GPU, the rest in doubles */
  def dot(a: Arr, b: Arr)(using Scope): Double =
    val p = multiply(a, b)
    sum(p, p.ndim - 1).toFloats.iterator.map(_.toDouble).sum
  def total(a: Arr)(using Scope): Double = dot(a, a)

  /** Σ a·b over the last axis of [1, n, H] at each position from `from` on */
  def rows(a: Arr, b: Arr, from: Int = 1)(using Scope): Array[Double] =
    sum(multiply(a, b), 2).toFloats.drop(from).map(_.toDouble)

  /** from logits [1, n, V]: −log p(ids(i + 1)) at each position i but the last */
  def nllOf(logits: Arr, ids: Array[Int])(using Scope): Array[Double] =
    val n = ids.length
    val lg = f32(logits)
    val loss = subtract(logsumexp(lg, 2), squeeze(takeAlong(lg, ints(ids.drop(1) :+ 0, 1, n, 1), 2), 2))
    loss.toFloats.take(n - 1).map(_.toDouble)

  /** y [p, k] with its columns made orthonormal: Cholesky QR twice (the k × k Gram matrix on the GPU, its factor on
   * the JVM in doubles) */
  private def ortho(y: Arr)(using Scope): Arr =
    val k = y.dim(1)
    def once(y: Arr): Arr =
      val g = matmul(transpose(y, 1, 0), y).toFloats.map(_.toDouble)
      val inv = Spectrum.invLower(Spectrum.cholesky(g, k), k)
      // Q = Y L⁻ᵀ: [k, k] with (L⁻ᵀ)(i, j) = L⁻¹(j, i)
      matmul(y, floats(Array.tabulate(k * k)(x => inv((x % k) * k + x / k).toFloat), k, k))
    once(once(y))

  /** THE TRUNCATED SVD, by randomized subspace iteration (Halko, Martinsson, Tropp 2011): a random start of
   * k = min(m, rmax + over) columns, `iters` rounds of A Aᵀ with the columns made orthonormal, then Rayleigh–Ritz —
   * the k × k eigenproblem on the JVM. With k = m (a small matrix) it is the exact SVD. The basis in the scope given */
  def basis(d: Arr, rmax: Int, over: Int, iters: Int, seed: Long)(using s: Scope): Basis =
    val t = new Scope
    try
      given Scope = t
      val rowsSide = d.dim(0) <= d.dim(1)
      val a = if rowsSide then d else transpose(d, 1, 0) // [m, n], m ≤ n
      val at = transpose(a, 1, 0)
      val (m, n) = (a.dim(0), a.dim(1))
      val k = math.min(m, rmax + over)
      val c = math.min(rmax, k)
      val all = total(d)
      if all == 0 then Basis(zeros(Seq(m, c), Dtype.Float32)(using s).eval(), new Array(c), rowsSide, 0, 0)
      else
        val rnd = java.util.Random(seed)
        var q = ortho(matmul(a, floats(Array.fill(n * k)(rnd.nextGaussian().toFloat), n, k)))
        for _ <- 0 until iters do q = ortho(matmul(a, ortho(matmul(at, q)))).eval()
        val gram = matmul(transpose(q, 1, 0), q).toFloats
        val defect = (0 until k * k).iterator.map(x => math.abs(gram(x) - (if x / k == x % k then 1f else 0f))).max
        val b = matmul(transpose(q, 1, 0), a)
        val sm = matmul(b, transpose(b, 1, 0)).toFloats.map(_.toDouble)
        for i <- 0 until k; j <- 0 until i do
          val mean = (sm(i * k + j) + sm(j * k + i)) / 2
          sm(i * k + j) = mean; sm(j * k + i) = mean
        val (lam, w) = Spectrum.eigen(sm, k)
        val wr = floats(Array.tabulate(k * c)(x => w((x / c) * k + x % c).toFloat), k, c)
        Basis(matmul(q, wr).eval().moveTo(s), lam.take(c), rowsSide, all, defect)
    finally t.close()

  /** the rank-r part of d in a basis of its own (`basis(d, …)`) */
  def lowRank(d: Arr, b: Basis, r: Int)(using Scope): Arr =
    val rr = math.min(r, b.u.dim(1))
    if rr == 0 || b.total == 0 then multiply(d, scalar(0f))
    else
      val ur = slice(b.u, Seq(0, 0), Seq(b.u.dim(0), rr))
      val ut = transpose(ur, 1, 0)
      if b.rows then matmul(ur, matmul(ut, d)) else matmul(matmul(d, ur), ut)

  /** the model of a directory: config.json and its 4-bit .safetensors shards */
  def load(dir: Path): Fold =
    Mlx.init()
    val config = Qwen3Config(okay.effects.json.Json.fromText(Files.readString(dir.resolve("config.json"))))
    val own = new Scope
    given Scope = own
    val weights = SafeTensors.shards(dir).flatMap(SafeTensors.load(_, mapped = false)).toMap
    evalAll(weights.values.toSeq)
    new Fold(config, weights, own)

/**
 * THE MEASURES ON A TEXT SAMPLE (specs/fold.md §3): the original model's residual streams over `texts` kept, then
 * read. Position 0 of each text is left out of every activation measure (its residual stream is an outlier in norm,
 * and would set every variance alone).
 */
final class FoldMeasures(val f: Fold, texts: Vector[Array[Int]]):
  import Fold.*
  private val keep = new Scope
  /** hs(t)(l): text t's residual stream before layer l; hs(t)(layers) after the last */
  val hs: Vector[Vector[Arr]] = texts.map(f.states(_, keep))
  private def update(t: Int, l: Int)(using Scope): Arr = subtract(hs(t)(l + 1), hs(t)(l))

  /** the mean over tokens of the cosine between layer a's and layer b's residual updates at the same token */
  def cosine(a: Int, b: Int): Double =
    val cs = texts.indices.flatMap { t =>
      val s = new Scope
      try
        given Scope = s
        val (ua, ub) = (update(t, a), update(t, b))
        val (ab, aa, bb) = (rows(ua, ub), rows(ua, ua), rows(ub, ub))
        ab.indices.map(i => ab(i) / math.sqrt(aa(i) * bb(i)))
      finally s.close()
    }
    cs.sum / cs.size

  /** one way of replacing layer b in the pair (a, b): its name (`skip`: no layer b; a rank: the fold), the squared
   * error of the pair's output over the variance of the original output (`nmse`), and over the energy of layer b's
   * own update (`relU`: 1 = as bad as dropping the layer) */
  final case class PairRow(a: Int, b: Int, how: String, nmse: Double, relU: Double)

  /** THE UNTRAINED FOLD OF ONE PAIR: layer b run on the original input of layer b with layer a's maps + the rank-r
   * part of the difference and its own norms, against the original output of the pair */
  def pairFold(a: Int, b: Int, bases: Map[String, Basis], ranks: Seq[Int]): Vector[PairRow] =
    val s = new Scope
    try
      given Scope = s
      var (n, sum1, sum2, upd) = (0L, 0.0, 0.0, 0.0)
      for t <- texts.indices do
        val y = hs(t)(b + 1)
        sum1 += sum(y, 2).toFloats.drop(1).iterator.map(_.toDouble).sum
        sum2 += rows(y, y).sum
        val u = update(t, b)
        upd += rows(u, u).sum
        n += (y.dim(1) - 1).toLong * y.dim(2)
      val variance = sum2 - sum1 * sum1 / n
      val folds = ranks.map { r =>
        val tmp = new Scope
        try
          given Scope = tmp
          val w = maps.map(m => m -> f.folded(a, b, m, bases(m), r).eval()).toMap
          val err = texts.indices.map { t =>
            val e = subtract(f.block(hs(t)(b), w, b), hs(t)(b + 1))
            rows(e, e).sum
          }.sum
          PairRow(a, b, r.toString, err / variance, err / upd)
        finally tmp.close()
      }
      (PairRow(a, b, "skip", upd / variance, 1.0) +: folds).toVector
    finally s.close()

  /** the perplexity of the next token over the sample (every position but each text's last), with the layers' maps
   * from `source`: pooled, and text by text */
  def perplexity(source: Fold.Source = f.plain): (Double, Vector[Double]) =
    val per = texts.map { ids =>
      val s = new Scope
      try f.nll(f.states(ids, s, source).last, ids)(using s) finally s.close()
    }
    (math.exp(per.flatten.sum / per.map(_.length).sum), per.map(x => math.exp(x.sum / x.length)))

  /** THE WHOLE MODEL FOLDED AT N = 2: each odd layer b whose pair (b − 1, b) is in `bases` takes layer b − 1's maps
   * + the rank-r part of the difference; its norms stay its own (the pass takes them from layer b) */
  def foldedSource(bases: Map[Int, Map[String, Basis]], r: Int): Fold.Source = (l, m, s) =>
    bases.get(l - 1) match
      case Some(bs) if l % 2 == 1 => f.folded(l - 1, l, m, bs(m), r)(using s)
      case _ => f.weight(l, m)(using s)

  def close(): Unit = keep.close()
