package okay.inlay.gpu

import _root_.mlx.{Arr, Dtype, Scope}
import _root_.mlx.Mlx.*
import okay.inlay.{Forward, Interpret, Program, Weights}

/**
 * THE WEIGHTS ON MLX: the same layers as `Forward`, as matrices on Apple's GPU — per head the scores `X qk Xᵀ` under
 * the causal mask, softmax (float32), `A (X ovᵀ)`; the MLP `ReLU(X w1ᵀ) w2ᵀ`. The matrices put on the GPU once
 * (`OnMlx(w)`), run on as many inputs as asked; `close` frees them. The law: decoded, what `Forward` and the
 * reference give.
 */
final class OnMlx(val w: Weights):
  _root_.mlx.Mlx.init()
  private val kept = new Scope
  private def matrix(m: Array[Array[Float]]): Arr =
    val rows = m.length
    val cols = if rows == 0 then 0 else m(0).length
    floats(m.flatten, rows, cols)(using kept)
  private val layers = w.layers.map { l =>
    val heads = l.heads.map(h => (matrix(h.qk), matrix(h.ov.transpose)))
    val mlp = if l.mlp.w1.isEmpty then None else Some((matrix(l.mlp.w1.transpose), matrix(l.mlp.w2.transpose)))
    (heads, mlp)
  }

  /** the layers over the embedded positions (the start first): the residual, row by row */
  def run(x0: Array[Array[Float]]): Array[Array[Float]] =
    val s = new Scope
    given Scope = s
    try
      val n = x0.length
      val d = w.layout.dims
      // −∞ above the diagonal: a position attends to itself and those before
      val mask = floats(Array.tabulate(n * n)(k => if k % n > k / n then Float.NegativeInfinity else 0f), n, n)
      val zero = scalar(0f)
      var x = floats(x0.flatten, n, d)
      for (heads, mlp) <- layers do
        var heard = zeros(Seq(n, d), Dtype.Float32)
        for (qk, ovT) <- heads do
          val a = softmax(add(matmul(matmul(x, qk), transpose(x, 1, 0)), mask), 1)
          heard = add(heard, matmul(a, matmul(x, ovT)))
        x = add(x, heard)
        for (w1T, w2T) <- mlp do
          val pre = matmul(x, w1T)
          x = add(x, matmul(where(greater(pre, zero), pre, zero), w2T))
      x.toFloats.grouped(d).toArray
    finally s.close()

  /** the program's weights run: every position's record, decoded (the start not among them) */
  def records(p: Program, inputs: Vector[Interpret.Record]): Vector[Interpret.Record] =
    val xs = run(Forward.embed(p, w, inputs))
    xs.drop(1).map(x => p.fields.map(f => f.name -> Forward.decode(w.layout, f, x)).toMap).toVector

  /** the last position's record alone */
  def last(p: Program, inputs: Vector[Interpret.Record]): Interpret.Record =
    val x = run(Forward.embed(p, w, inputs)).last
    p.fields.map(f => f.name -> Forward.decode(w.layout, f, x)).toMap

  def close(): Unit = kept.close()

object OnMlx:
  /** once: the weights put on the GPU, run, freed */
  def run(p: Program, w: Weights, inputs: Vector[Interpret.Record]): Vector[Interpret.Record] =
    val m = OnMlx(w)
    try m.records(p, inputs) finally m.close()
