package okay.inlay

/** the quote over a large vocabulary (tokens as ±1 bits, with presence): the weights equal the reference and QuoteK */
class BitsQuoteSuite extends munit.FunSuite:
  private val r = scala.util.Random(17)

  for (size, k, n) <- Seq((6, 4, 40), (40, 8, 60), (300, 16, 60)) do
    test(s"a vocabulary of $size, k = $k: every position"):
      val words = (0 until size).map(i => s"t$i").toVector
      val vocab = words ++ Vector("[", "]")
      val p = Quotes.cheapBits(vocab, k, 64)
      val w = Compile(p)
      println(s"vocab $size, k = $k: ${p.layers.map(_.heads.size).sum} heads, ${w.layout.dims} dims")
      // few distinct words, so windows repeat: a source made of a small alphabet's runs
      val alphabet = r.shuffle(words).take(4)
      for _ <- 1 to 15 do
        val len = 20 + r.nextInt(n - 20)
        val s = 4 + r.nextInt(len / 2)
        val toks = Vector.tabulate(len)(i =>
          if i >= s && r.nextInt(6) == 0 then (if r.nextBoolean() then "[" else "]") else alphabet(r.nextInt(4)))
        val src = Vector.tabulate(len)(_ < s)
        val in = Quotes.bitsInputs(vocab, toks, src)
        val ref = Interpret.run(p, in)
        val fw = Forward.run(p, w, in)
        for i <- fw.indices; f <- p.fields.map(_.name) if fw(i)(f) != ref(i)(f) do
          fail(s"position ${i + 1} field $f: weights ${fw(i)(f)} reference ${ref(i)(f)} on ${toks.take(i + 1)}")
        val got = ref.map(rec => (rec("inside") == Val.F(true), rec("allowed")))
          .map((in, a) => (in, if in then a match { case Val.M(s) => s; case _ => Set.empty[String] } else Set.empty[String]))
        assertEquals(got, Quotes.reference(toks, src, k), s"against QuoteK on $toks")
