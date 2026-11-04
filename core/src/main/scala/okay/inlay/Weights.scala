package okay.inlay

/** where each field lives in the residual stream: three fixed dims first (one, the start, the position as a number),
 * then the fields in the program's order, then the gathering heads' raw averages */
final case class Layout(offsets: Map[String, Int], dims: Int):
  def at(f: Field): Int = offsets(f.name)
  def dim(f: Cat, v: String): Int = at(f) + f.index(v)

object Layout:
  val One = 0
  val Start = 1
  val PosNum = 2
  /** a gathering head's average, before the ladder makes it a set */
  def raw(m: Field): Many = m match
    case Many(n, vs) => Many(n + "#raw", vs)
    case other => sys.error(s"${other.name}: not a Many")
  /** a counting head's share of attention on the start, before the ladder reads the count from it */
  def share(c: Cat): Flag = Flag(c.name + "#share")
  def of(p: Program): Layout =
    val scratch = p.layers.flatMap(_.heads.flatMap {
      case h: Lookup if h.all => h.copy.map((d, _) => raw(d))
      case c: Count => Seq(share(c.dst))
      case _ => Nil
    })
    val all = p.fields ++ scratch
    val offs = all.scanLeft(3)(_ + _.dims)
    Layout(all.map(_.name).zip(offs).toMap, offs.last)

/** a head's weights: the bilinear score `xᵢᵀ qk xⱼ` and the copy `ov xⱼ` (d × d each) */
final case class HeadW(qk: Array[Array[Float]], ov: Array[Array[Float]]):
  /** ov's columns: a source dim's contribution to each output dim */
  lazy val ovT: Array[Array[Float]] = ov.transpose

/** the MLP: hidden = ReLU(w1 x), x += w2 hidden (w1: h × d, w2: d × h) */
final case class MlpW(w1: Array[Array[Float]], w2: Array[Array[Float]]):
  lazy val w1T: Array[Array[Float]] = if w1.isEmpty then Array.empty else w1.transpose
  lazy val w2T: Array[Array[Float]] = if w1.isEmpty then Array.empty else w2.transpose

final case class LayerW(heads: Vector[HeadW], mlp: MlpW)

/** a compiled program: its layout and every layer's matrices */
final case class Weights(layout: Layout, layers: Vector[LayerW], maxLen: Int):
  def params: Long = layers.map(l => l.heads.size.toLong * 2 * layout.dims * layout.dims +
    l.mlp.w1.length.toLong * layout.dims * 2).sum

/** THE COMPILER: a program into weights (specs/dsl.md) */
object Compile:
  /** the position term of a `latest` lookup: consecutive positions this far apart in score (exp(-128) = 0 in
   * float32) */
  val Eps = 128f
  /** the gap between a match and a mismatch of a plain lookup */
  val Lambda = 256f

  def apply(p: Program): Weights =
    p.checked
    val lay = Layout.of(p)
    val d = lay.dims
    def square() = Array.fill(d, d)(0f)
    def head(h: Head): HeadW =
      val qk = square()
      val ov = square()
      val latest = h match
        case l: Lookup => l.latest
        case _ => false
      val lambda = if latest then 4 * Eps * p.maxLen else Lambda
      var top = 0f
      for (q, k) <- h.query.zip(h.key) do (q, k) match
        case (qc: Cat, kc: Cat) =>
          for v <- qc.values do qk(lay.dim(qc, v))(lay.dim(kc, v)) = lambda
          top += lambda
          if h.wild then
            // a none query scores the pair's top against every position but the start: λ·(1 − Σ one-hot)·(one − start)
            qk(Layout.One)(Layout.One) += lambda
            qk(Layout.One)(Layout.Start) -= lambda
            for v <- qc.values do
              qk(lay.dim(qc, v))(Layout.One) -= lambda
              qk(lay.dim(qc, v))(Layout.Start) += lambda
        case (qb: Bits, kb: Bits) =>
          for b <- 0 until qb.bits do qk(lay.at(qb) + b)(lay.at(kb) + b) = lambda / 2
          val pairTop = lambda / 2 * qb.bits
          top += pairTop
          if h.wild then
            // as for a Cat: a none query (presence 0) scores the pair's top against every position but the start
            val present = lay.at(qb) + qb.present
            qk(Layout.One)(Layout.One) += pairTop
            qk(Layout.One)(Layout.Start) -= pairTop
            qk(present)(Layout.One) -= pairTop
            qk(present)(Layout.Start) += pairTop
        case _ => sys.error("checked: comparable fields")
      // the start: a fixed score half a gap below a match — it wins only when nothing matches
      h match
        case h: Lookup =>
          qk(Layout.One)(Layout.Start) += top - lambda / 2
          if h.latest then qk(Layout.One)(Layout.PosNum) = Eps
          for (dst, src) <- h.copy do
            val into = if h.all then Layout.raw(dst) else dst
            for i <- 0 until dst.dims do ov(lay.at(into) + i)(lay.at(src) + i) = 1f
        case c: Count =>
          // the start scores as a match: 1/(c+1) of the attention on it, read into the share
          qk(Layout.One)(Layout.Start) += top
          ov(lay.at(Layout.share(c.dst)))(Layout.Start) = 1f
      HeadW(qk, ov)
    def mlp(l: Layer): MlpW =
      // a neuron: its input weights, and its output dims with their weights
      val neurons = Vector.newBuilder[(Array[Float], Seq[(Int, Float)])]
      // the guards' sum: +1 per positive guard, −1 per negative, and how many positive
      def guarded(guards: Seq[Guard]): (Array[Float], Int) =
        val row = Array.fill(d)(0f)
        var positive = 0
        guards.foreach {
          case Guard.Is(f, v) => row(lay.dim(f, v)) += 1; positive += 1
          case Guard.IsNot(f, v) => row(lay.dim(f, v)) -= 1
          case Guard.On(f) => row(lay.at(f)) += 1; positive += 1
          case Guard.Off(f) => row(lay.at(f)) -= 1
        }
        (row, positive)
      // AND of the guards (and of `also`, a 0/1 dim), never at the start: 1 when all hold, else ≤ 0
      def and(guards: Seq[Guard], also: Option[Int] = None): Array[Float] =
        val (row, n0) = guarded(guards)
        also.foreach(a => row(a) += 1)
        val n = n0 + also.size
        row(Layout.One) += 1 - n
        row(Layout.Start) -= n + 1
        row
      for r <- l.rules; w <- r.sets do w match
        case Write.To(f, v) => neurons += ((and(r.guards), Seq(lay.dim(f, v) -> 1f)))
        case Write.Raise(f) => neurons += ((and(r.guards), Seq(lay.at(f) -> 1f)))
        case Write.Copy(dst: Cat, src: Cat) =>
          for v <- src.values do neurons += ((and(r.guards :+ Guard.Is(src, v)), Seq(lay.dim(dst, v) -> 1f)))
        case Write.Copy(dst: Bits, src: Bits) =>
          // each ±1 bit passed through while the guards hold: ReLU(x + 2(G − n)) − ReLU(−x + 2(G − n)), G the guards'
          // sum (n when all hold: x; else |x| ≤ 1 against −2: 0); the presence an AND with the guards
          val (g, n) = guarded(r.guards)
          for b <- 0 until src.bits do
            val up = g.map(_ * 2)
            up(Layout.One) -= 2f * n
            up(Layout.Start) -= 2f
            val down = up.clone()
            up(lay.at(src) + b) += 1
            down(lay.at(src) + b) -= 1
            neurons += ((up, Seq(lay.at(dst) + b -> 1f)))
            neurons += ((down, Seq(lay.at(dst) + b -> -1f)))
          neurons += ((and(r.guards, Some(lay.at(src) + src.present)), Seq(lay.at(dst) + dst.present -> 1f)))
        case Write.Copy(_, _) => sys.error("checked: copies of Cats or Bits")
      // the ladder of a gathering head: min(1, L·x) = ReLU(L·x) − ReLU(L·x − 1), any share ≥ 1/maxLen a 1
      val steep = 2f * p.maxLen
      for h <- l.heads.collect { case h: Lookup if h.all => h }; (dst, _) <- h.copy; i <- 0 until dst.dims do
        val from = lay.at(Layout.raw(dst)) + i
        val a = Array.fill(d)(0f)
        a(from) = steep
        val b = Array.fill(d)(0f)
        b(from) = steep
        b(Layout.One) = -1f
        neurons += ((a, Seq(lay.at(dst) + i -> 1f)))
        neurons += ((b, Seq(lay.at(dst) + i -> -1f)))
      // the ladder of a counting head: the share x = 1/(c+1); G_n = [c ≥ n] = clamp(S·(tₙ − x), 0, 1), tₙ = 1/(n + ½)
      // (S·(tₙ − x) ≥ 1 when c ≥ n, ≤ 0 when c < n); the count's one-hot "n" = G_n − G_{n+1}, "0" = 1 − G₁
      for c <- l.heads.collect { case c: Count => c } do
        val k = c.cap
        val s = 4f * (k + 1) * (k + 1)
        val x = lay.at(Layout.share(c.dst))
        def bucket(n: Int) = lay.dim(c.dst, n.toString)
        val zero = Array.fill(d)(0f)
        zero(Layout.One) = 1f
        zero(Layout.Start) = -1f
        neurons += ((zero, Seq(bucket(0) -> 1f)))
        for n <- 1 to k do
          val t = 1f / (n + 0.5f)
          val a = Array.fill(d)(0f)
          a(Layout.One) = s * t
          a(x) = -s
          val b = a.clone()
          b(Layout.One) -= 1f
          neurons += ((a, Seq(bucket(n) -> 1f, bucket(n - 1) -> -1f)))
          neurons += ((b, Seq(bucket(n) -> -1f, bucket(n - 1) -> 1f)))
      val ns = neurons.result()
      val w2 = Array.fill(d, ns.size)(0f)
      for ((_, outs), n) <- ns.zipWithIndex; (o, wt) <- outs do w2(o)(n) = wt
      MlpW(ns.map(_._1).toArray, w2)
    Weights(lay, p.layers.map(l => LayerW(l.heads.map(head).toVector, mlp(l))).toVector, p.maxLen)

/** THE WEIGHTS RUN: embedding, layers (float32: softmax, ReLU), the read-out decoded */
object Forward:
  import Interpret.Record

  def embed(p: Program, w: Weights, inputs: Vector[Record]): Array[Array[Float]] =
    val lay = w.layout
    val rs = Interpret.records(p, inputs)
    val start = Array.fill(lay.dims)(0f)
    start(Layout.One) = 1f
    start(Layout.Start) = 1f
    (start +: rs.zipWithIndex.map { (r, i) =>
      val x = Array.fill(lay.dims)(0f)
      x(Layout.One) = 1f
      x(Layout.PosNum) = (i + 1).toFloat
      for f <- p.inputs do (f, r(f.name)) match
        case (c: Cat, Val.C(Some(v))) => x(lay.dim(c, v)) = 1f
        case (b: Bits, Val.B(Some(n))) =>
          for k <- 0 until b.bits do x(lay.at(b) + k) = if (n >> k & 1) == 1 then 1f else -1f
          x(lay.at(b) + b.present) = 1f
        case (fl: Flag, Val.F(true)) => x(lay.at(fl)) = 1f
        case (m: Many, Val.M(vs)) => vs.foreach(v => x(lay.at(m) + m.values.indexOf(v)) = 1f)
        case _ => ()
      x
    }).toArray

  /** the dims where x is not zero */
  private def nonZero(x: Array[Float]): Array[Int] = x.indices.filter(x(_) != 0f).toArray

  /** Σ over x's non-zero dims of x(a) · rows(a) */
  private def spread(rows: Array[Array[Float]], x: Array[Float], nz: Array[Int], width: Int): Array[Float] =
    val out = new Array[Float](width)
    for a <- nz do
      val r = rows(a)
      val xa = x(a)
      var k = 0
      while k < width do
        out(k) += xa * r(k)
        k += 1
    out

  private def dot(a: Array[Float], b: Array[Float]): Float =
    var s = 0f
    var k = 0
    while k < a.length do
      s += a(k) * b(k)
      k += 1
    s

  /** the layers over the embedded positions (the start first) */
  def layers(w: Weights, x0: Array[Array[Float]]): Array[Array[Float]] =
    val dims = w.layout.dims
    var xs = x0
    for l <- w.layers do
      val nz = xs.map(nonZero)
      val heard = xs.indices.map { i =>
        val total = new Array[Float](dims)
        for h <- l.heads do
          // the query xᵢᵀ qk, a score against each position up to i, softmax, the copies averaged
          val q = spread(h.qk, xs(i), nz(i), dims)
          val scores = (0 to i).map(j => dot(q, xs(j)))
          val top = scores.max
          val e = scores.map(s => math.exp((s - top).toDouble).toFloat)
          val sum = e.sum
          for j <- 0 to i if e(j) != 0f do
            val v = spread(h.ovT, xs(j), nz(j), dims)
            val wj = e(j) / sum
            for k <- 0 until dims do total(k) += wj * v(k)
        total
      }
      xs = xs.indices.map(i => Array.tabulate(dims)(k => xs(i)(k) + heard(i)(k))).toArray
      if l.mlp.w1.nonEmpty then
        val n = l.mlp.w1.length
        xs = xs.map { x =>
          val hidden = spread(l.mlp.w1T, x, nonZero(x), n).map(math.max(_, 0f))
          val out = spread(l.mlp.w2T, hidden, nonZero(hidden), dims)
          Array.tabulate(dims)(k => x(k) + out(k))
        }
    xs

  private val tolerance = 1e-4f
  private def near(a: Float, b: Float) = math.abs(a - b) < tolerance

  /** a field read from a residual vector — exact or `Inexact` */
  def decode(lay: Layout, f: Field, x: Array[Float]): Val =
    val xs = x.slice(lay.at(f), lay.at(f) + f.dims)
    f match
      case c: Cat =>
        if !xs.forall(v => near(v, 0) || near(v, 1)) || xs.count(near(_, 1)) > 1 then
          throw Inexact(s"${f.name} is not one-hot: ${xs.mkString(",")}")
        Val.C(xs.indexWhere(near(_, 1)) match
          case -1 => None
          case i => Some(c.values(i)))
      case m: Many =>
        if !xs.forall(v => near(v, 0) || near(v, 1)) then throw Inexact(s"${f.name} is not 0/1: ${xs.mkString(",")}")
        Val.M(xs.indices.filter(i => near(xs(i), 1)).map(m.values).toSet)
      case b: Bits =>
        val bits = xs.take(b.bits)
        if xs.forall(near(_, 0)) then Val.B(None)
        else if near(xs(b.present), 1) && bits.forall(v => near(v, 1) || near(v, -1)) then
          Val.B(Some(bits.zipWithIndex.map((v, k) => if v > 0 then 1 << k else 0).sum))
        else throw Inexact(s"${f.name} is not ±1 and present: ${xs.mkString(",")}")
      case _: Flag =>
        if near(xs(0), 0) then Val.F(false) else if near(xs(0), 1) then Val.F(true)
        else throw Inexact(s"${f.name} is not 0 or 1: ${xs(0)}")

  /** the program's weights run on inputs: every position's record, decoded (the start not among them) */
  def run(p: Program, w: Weights, inputs: Vector[Record]): Vector[Record] =
    val xs = layers(w, embed(p, w, inputs))
    xs.drop(1).map(x => p.fields.map(f => f.name -> decode(w.layout, f, x)).toMap).toVector
