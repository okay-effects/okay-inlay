package okay.inlay.gpu

import okay.inlay.*

/**
 * THE TIME OF A PASS, PLAIN AND PACKED (specs/dsl.md "Liveness on MLX"), float32: the closure block and DPLL at
 * n = 8, one sequence each (the longest), the embedding made before the clock starts — a pass is the matrices'
 * work and the residual read back. Five rounds, each 20 passes plain then 20 packed after 5 unmeasured of each;
 * a round's number is its median pass. Nothing is asserted: the rows are measurements.
 */
class PackedTimeSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")

  private def median(xs: Seq[Double]): Double = xs.sorted.apply(xs.size / 2)

  for (label, make) <- Seq[(String, () => Packed)]("Closure, 12 names, 4 rounds" -> (() => Packed.closure()),
      "Dpll, n = 8, 20 slots" -> (() => Packed.dpll8())) do
    test(s"$label: a pass plain and packed"):
      val k = make()
      val in = k.inputs.maxBy(_.size)
      val plain = OnMlx(k.plainWeights)
      val packed = OnMlx(k.packing.weights)
      try
        val x0 = Forward.embed(k.plain, plain.w, in)
        val x1 = Forward.embed(k.packed, packed.w, in)
        def passes(gpu: OnMlx, x: Array[Array[Float]], count: Int): Seq[Double] =
          for _ <- 1 to count yield
            val t0 = System.nanoTime()
            gpu.run(x)
            (System.nanoTime() - t0) / 1e6
        passes(plain, x0, 5); passes(packed, x1, 5)
        val rounds = for _ <- 1 to 5 yield (median(passes(plain, x0, 20)), median(passes(packed, x1, 20)))
        def heads(w: Weights) = w.layers.map(_.heads.size).sum
        println(f"packed-mlx time | $label | ${in.size + 1} positions | plain: width ${plain.w.layout.dims}, " +
          f"${heads(plain.w)} heads, ms a pass by round ${rounds.map(r => f"${r._1}%.2f").mkString(" ")} | " +
          f"packed: width ${packed.w.layout.dims}, ${heads(packed.w)} heads, " +
          f"${rounds.map(r => f"${r._2}%.2f").mkString(" ")} | packed/plain by round " +
          f"${rounds.map(r => f"${r._2 / r._1}%.2f").mkString(" ")}")
      finally { plain.close(); packed.close() }
