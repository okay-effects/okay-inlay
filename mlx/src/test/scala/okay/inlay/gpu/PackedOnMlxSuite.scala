package okay.inlay.gpu

import okay.inlay.*

/**
 * THE LAW OF A PACKED LAYOUT ON THE GPU (specs/dsl.md "Liveness on MLX"), float32: for every program the MLX module
 * runs plain, and the machines — the packed weights on MLX decode to the packed reference's records at every
 * position; the packed reference keeps the plain one's values on every field left; and each coordinate a `Clear`
 * frees is exactly 0 after the layer's heads, at every position.
 */
class PackedOnMlxSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")

  for (label, make) <- Packed.cases do
    test(s"$label: packed on MLX == the packed reference == the plain one on the fields left; clears leave 0"):
      val k = make()
      val gpu = OnMlx(k.packing.weights)
      try
        for (in, n) <- k.inputs.zipWithIndex do
          assertEquals(k.packedRef(n).map(k.packing.left), k.plainRef(n).map(k.packing.left), s"references, $n")
          assertEquals(gpu.records(k.packed, in), k.packedRef(n), s"MLX against the packed reference, sequence $n")
        val z = k.zeros(gpu)
        println(s"packed-mlx law | $label | ${k.inputs.size} sequences, ${k.inputs.map(_.size).sum} positions, " +
          s"longest ${k.inputs.map(_.size).max} | width ${Layout.of(k.plain).dims} → ${gpu.w.layout.dims} | $z")
        assertEquals(z.notZero, 0L, "a cleared coordinate is exactly 0")
      finally gpu.close()
