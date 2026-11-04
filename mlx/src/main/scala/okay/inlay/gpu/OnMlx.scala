package okay.inlay.gpu

import _root_.mlx.{Arr, Dtype, Scope}
import _root_.mlx.Mlx.*
import okay.inlay.{Forward, Interpret, Program, Weights}

/**
 * THE WEIGHTS ON MLX: the same layers as `Forward`, as matrices on Apple's GPU — per head the scores `X qk Xᵀ` under
 * the causal mask, softmax (float32), `A (X ovᵀ)`; the MLP `ReLU(X w1ᵀ) w2ᵀ`. The law: decoded, what `Forward` and
 * the reference give.
 */
object OnMlx:
  _root_.mlx.Mlx.init()

  private def matrix(m: Array[Array[Float]])(using Scope): Arr =
    val rows = m.length
    val cols = if rows == 0 then 0 else m(0).length
    floats(m.flatten, rows, cols)

  /** the layers over the embedded positions (the start first): the residual, row by row */
  def layers(w: Weights, x0: Array[Array[Float]]): Array[Array[Float]] =
    val s = new Scope
    given Scope = s
    try
      val n = x0.length
      val d = w.layout.dims
      // −∞ above the diagonal: a position attends to itself and those before
      val mask = floats(Array.tabulate(n * n)(k => if k % n > k / n then Float.NegativeInfinity else 0f), n, n)
      val zero = scalar(0f)
      var x = matrix(x0)
      for l <- w.layers do
        var heard = zeros(Seq(n, d), Dtype.Float32)
        for h <- l.heads do
          val scores = add(matmul(matmul(x, matrix(h.qk)), transpose(x, 1, 0)), mask)
          val a = softmax(scores, 1)
          heard = add(heard, matmul(a, matmul(x, transpose(matrix(h.ov), 1, 0))))
        x = add(x, heard)
        if l.mlp.w1.nonEmpty then
          val pre = matmul(x, transpose(matrix(l.mlp.w1), 1, 0))
          val hidden = where(greater(pre, zero), pre, zero)
          x = add(x, matmul(hidden, transpose(matrix(l.mlp.w2), 1, 0)))
      x.toFloats.grouped(d).toArray
    finally s.close()

  /** the program's weights run on MLX: every position's record, decoded (the start not among them) */
  def run(p: Program, w: Weights, inputs: Vector[Interpret.Record]): Vector[Interpret.Record] =
    val xs = layers(w, Forward.embed(p, w, inputs))
    xs.drop(1).map(x => p.fields.map(f => f.name -> Forward.decode(w.layout, f, x)).toMap).toVector
