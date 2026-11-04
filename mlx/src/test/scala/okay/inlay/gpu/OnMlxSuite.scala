package okay.inlay.gpu

import okay.inlay.*

/** THE LAW ON THE GPU: the quote programs' weights on MLX decode to the reference's records, at every position */
class OnMlxSuite extends munit.FunSuite:
  private val r = scala.util.Random(3)
  private def c(v: String) = Val.C(Some(v))

  for (name, k) <- Seq("window" -> 3, "cheap" -> 8, "cheap" -> 32) do
    test(s"the quote, $name k = $k: on MLX as the reference"):
      val vocab = Vector("a", "b", "c", "[", "]")
      val p = if name == "window" then Quotes.window(vocab, k, 64) else Quotes.cheap(vocab, k, 64)
      val w = Compile(p)
      for _ <- 1 to 20 do
        val n = 16 + r.nextInt(47)
        val s = 3 + r.nextInt(n / 2)
        val toks = Vector.tabulate(n)(i =>
          if i >= s && r.nextInt(6) == 0 then (if r.nextBoolean() then "[" else "]") else Seq("a", "b", "c")(r.nextInt(3)))
        val in = toks.zipWithIndex.map((t, i) =>
          Map[String, Val]("tok" -> c(t)) ++ (if i < s then Map("src" -> c("y")) else Map.empty))
        assertEquals(OnMlx.run(p, w, in), Interpret.run(p, in), s"on $toks")
