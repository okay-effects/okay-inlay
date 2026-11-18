package okay.inlay.gpu

import _root_.mlx.Dtype

/**
 * WHAT A MODEL FILE'S PRECISION DOES TO A PACKED LAYOUT (specs/dsl.md "Liveness on MLX"): `HalfSuite`'s question for
 * every program, plain and packed side by side — weights and activations in float32, float16 and bfloat16, the
 * positions that stay exact, the earliest one that does not, and what the clears leave on the coordinates they free.
 * Asserted in float32 only; the halves are measured.
 */
class PackedHalfSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")

  for (label, make) <- Packed.cases do
    test(s"$label: plain and packed in float32, float16, bfloat16"):
      val k = make()
      for dt <- Packed.dtypes do
        val plain = OnMlx(k.plainWeights, dt)
        val onPlain = try k.tally(plain, k.plain, k.plainRef) finally plain.close()
        val packed = OnMlx(k.packing.weights, dt)
        val (onPacked, zeros) = try (k.tally(packed, k.packed, k.packedRef), k.zeros(packed)) finally packed.close()
        println(s"packed-mlx half | $label | $dt | plain: $onPlain | packed: $onPacked | $zeros")
        if dt == Dtype.Float32 then
          assert(onPlain.holds, s"plain in float32: $onPlain")
          assert(onPacked.holds, s"packed in float32: $onPacked")
          assertEquals(zeros.notZero, 0L, "a cleared coordinate is exactly 0 in float32")
