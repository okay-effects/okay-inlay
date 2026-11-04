package okay.inlay.gpu

import _root_.mlx.Dtype
import okay.inlay.*

/** WHAT A MODEL FILE'S PRECISION DOES TO THE BLOCK: the cheap quote's weights and activations in float16 and
 * bfloat16 (as a standard weights file stores them), against the reference — how many positions stay exact */
class HalfSuite extends munit.FunSuite:
  private val r = scala.util.Random(5)
  private def c(v: String) = Val.C(Some(v))

  for dt <- Seq(Dtype.Float32, Dtype.Float16, Dtype.BFloat16) do
    test(s"the cheap quote, k = 8, in $dt"):
      val vocab = Vector("a", "b", "c", "[", "]")
      val p = Quotes.cheap(vocab, 8, 64)
      val gpu = OnMlx(Compile(p), dt)
      var (positions, exact, inexact) = (0, 0, 0)
      for _ <- 1 to 20 do
        val n = 16 + r.nextInt(47)
        val s = 3 + r.nextInt(n / 2)
        val toks = Vector.tabulate(n)(i =>
          if i >= s && r.nextInt(6) == 0 then (if r.nextBoolean() then "[" else "]") else Seq("a", "b", "c")(r.nextInt(3)))
        val in = toks.zipWithIndex.map((t, i) =>
          Map[String, Val]("tok" -> c(t)) ++ (if i < s then Map("src" -> c("y")) else Map.empty))
        val ref = Interpret.run(p, in)
        val xs = gpu.run(Forward.embed(p, gpu.w, in)).drop(1)
        for (x, i) <- xs.zipWithIndex do
          positions += 1
          try
            val got = p.fields.map(f => f.name -> Forward.decode(gpu.w.layout, f, x)).toMap
            if got == ref(i) then exact += 1
          catch case _: Inexact => inexact += 1
      gpu.close()
      println(s"$dt: $exact of $positions positions exact, $inexact not even decodable")
      if dt == Dtype.Float32 then assertEquals(exact, positions)
