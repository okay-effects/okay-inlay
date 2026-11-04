package okay.inlay

/** where each field lives in the residual stream: three fixed dims first (one, the start, the position as a number),
 * then the fields in the program's order */
final case class Layout(offsets: Map[String, Int], dims: Int):
  def at(f: Field): Int = offsets(f.name)
  def dim(f: Cat, v: String): Int = at(f) + f.index(v)

object Layout:
  val One = 0
  val Start = 1
  val PosNum = 2
  def of(p: Program): Layout =
    val offs = p.fields.scanLeft(3)(_ + _.dims)
    Layout(p.fields.map(_.name).zip(offs).toMap, offs.last)

/** a head's weights: the bilinear score `xᵢᵀ qk xⱼ` and the copy `ov xⱼ` (d × d each) */
final case class HeadW(qk: Array[Array[Float]], ov: Array[Array[Float]])

/** the MLP: hidden = ReLU(w1 x), x += w2 hidden (w1: h × d, w2: d × h) */
final case class MlpW(w1: Array[Array[Float]], w2: Array[Array[Float]])

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
    def head(h: Lookup): HeadW =
      val qk = square()
      val ov = square()
      val lambda = if h.latest then 4 * Eps * p.maxLen else Lambda
      var top = 0f
      for (q, k) <- h.query.zip(h.key) do (q, k) match
        case (qc: Cat, kc: Cat) =>
          for v <- qc.values do qk(lay.dim(qc, v))(lay.dim(kc, v)) = lambda
          top += lambda
        case (qb: Bits, kb: Bits) =>
          for b <- 0 until qb.bits do qk(lay.at(qb) + b)(lay.at(kb) + b) = lambda / 2
          top += lambda / 2 * qb.bits
        case _ => sys.error("checked: comparable fields")
      // the start: a fixed score half a gap below a match — it wins only when nothing matches
      qk(Layout.One)(Layout.Start) = top - lambda / 2
      if h.latest then qk(Layout.One)(Layout.PosNum) = Eps
      for (dst, src) <- h.copy; i <- 0 until dst.dims do ov(lay.at(dst) + i)(lay.at(src) + i) = 1f
      HeadW(qk, ov)
    def mlp(rules: Seq[Rule]): MlpW =
      // a neuron: AND of its guards (positive and negative), never at the start; one output dim
      val neurons = rules.flatMap { r =>
        r.sets.flatMap {
          case Write.To(f, v) => Seq((r.guards, lay.dim(f, v)))
          case Write.Raise(f) => Seq((r.guards, lay.at(f)))
          case Write.Copy(dst, src) => src.values.map(v => (r.guards :+ Guard.Is(src, v), lay.dim(dst, v)))
        }
      }
      val w1 = neurons.map { (guards, _) =>
        val row = Array.fill(d)(0f)
        var positive = 0
        guards.foreach {
          case Guard.Is(f, v) => row(lay.dim(f, v)) += 1; positive += 1
          case Guard.IsNot(f, v) => row(lay.dim(f, v)) -= 1
          case Guard.On(f) => row(lay.at(f)) += 1; positive += 1
          case Guard.Off(f) => row(lay.at(f)) -= 1
        }
        row(Layout.One) += 1 - positive
        row(Layout.Start) -= positive + 1
        row
      }.toArray
      val w2 = Array.fill(d, neurons.size)(0f)
      for ((_, out), n) <- neurons.zipWithIndex do w2(out)(n) = 1f
      MlpW(w1, w2)
    Weights(lay, p.layers.map(l => LayerW(l.heads.map(head).toVector, mlp(l.rules))).toVector, p.maxLen)

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
        case (fl: Flag, Val.F(true)) => x(lay.at(fl)) = 1f
        case _ => ()
      x
    }).toArray

  private def times(m: Array[Array[Float]], x: Array[Float]): Array[Float] =
    m.map { row =>
      var s = 0f
      var k = 0
      while k < x.length do
        s += row(k) * x(k)
        k += 1
      s
    }

  private def dot(a: Array[Float], b: Array[Float]): Float =
    var s = 0f
    var k = 0
    while k < a.length do
      s += a(k) * b(k)
      k += 1
    s

  /** the layers over the embedded positions (the start first) */
  def layers(w: Weights, x0: Array[Array[Float]]): Array[Array[Float]] =
    var xs = x0
    for l <- w.layers do
      val heard = xs.indices.map { i =>
        val total = Array.fill(w.layout.dims)(0f)
        for h <- l.heads do
          // the query: xᵢᵀ qk, then a score against each position up to i
          val q = Array.tabulate(w.layout.dims)(b => xs(i).indices.map(a => xs(i)(a) * h.qk(a)(b)).sum)
          val scores = (0 to i).map(j => dot(q, xs(j)))
          val top = scores.max
          val e = scores.map(s => math.exp((s - top).toDouble).toFloat)
          val sum = e.sum
          for j <- 0 to i if e(j) != 0f do
            val v = times(h.ov, xs(j))
            val wj = e(j) / sum
            for k <- total.indices do total(k) += wj * v(k)
        total
      }
      xs = xs.indices.map(i => xs(i).indices.map(k => xs(i)(k) + heard(i)(k)).toArray).toArray
      xs = xs.map { x =>
        val hidden = times(l.mlp.w1, x).map(math.max(_, 0f))
        val out = times(l.mlp.w2, hidden)
        x.indices.map(k => x(k) + out(k)).toArray
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
      case _: Bits =>
        if xs.forall(near(_, 0)) then Val.B(None)
        else if xs.forall(v => near(v, 1) || near(v, -1)) then
          Val.B(Some(xs.zipWithIndex.map((v, k) => if v > 0 then 1 << k else 0).sum))
        else throw Inexact(s"${f.name} is not ±1: ${xs.mkString(",")}")
      case _: Flag =>
        if near(xs(0), 0) then Val.F(false) else if near(xs(0), 1) then Val.F(true)
        else throw Inexact(s"${f.name} is not 0 or 1: ${xs(0)}")

  /** the program's weights run on inputs: every position's record, decoded (the start not among them) */
  def run(p: Program, w: Weights, inputs: Vector[Record]): Vector[Record] =
    val xs = layers(w, embed(p, w, inputs))
    xs.drop(1).map(x => p.fields.map(f => f.name -> decode(w.layout, f, x)).toMap).toVector
