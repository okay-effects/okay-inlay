package okay.inlay

import Interpret.Record

/** THE QUOTE AS WEIGHTS (specs/dsl.md v1): a program of lookups and rules whose `inside` and `allowed` equal QuoteK —
 * the runtime quote with a window of k copied tokens — at every position of random streams */
class QuoteSuite extends munit.FunSuite:
  private val r = scala.util.Random(11)
  private def c(v: String) = Val.C(Some(v))

  private def stream(n: Int, words: Vector[String]): (Vector[String], Vector[Boolean]) =
    val s = 3 + r.nextInt(n / 2)
    val toks = Vector.tabulate(n)(i =>
      if i >= s && r.nextInt(6) == 0 then (if r.nextBoolean() then "[" else "]") else words(r.nextInt(words.size)))
    (toks, Vector.tabulate(n)(_ < s))


  for k <- Seq(1, 3, 6, 8, 16, 32) do
    test(s"the cheap form, k = $k: equals the reference and QuoteK at every position"):
      val vocab = Vector("a", "b", "c", "[", "]")
      val p = Quotes.cheap(vocab, k, 64)
      val w = Compile(p)
      println(s"cheap k = $k: ${p.layers.size} layers, ${p.layers.map(_.heads.size).sum} heads " +
        s"(the first form: ${Quotes.window(vocab, k, 64).layers.map(_.heads.size).sum}), ${w.layout.dims} dims, " +
        s"${w.layers.map(_.mlp.w1.length).sum} neurons")
      var (steps, narrowed) = (0, 0)
      for _ <- 1 to 40 do
        val (toks, src) = stream(16 + r.nextInt(47), Vector("a", "b", "c"))
        val in: Vector[Record] = toks.zip(src).map((t, s) =>
          Map[String, Val]("tok" -> c(t)) ++ (if s then Map("src" -> c("y")) else Map.empty))
        val ref = Interpret.run(p, in)
        assertEquals(Forward.run(p, w, in), ref, s"weights against the reference on $toks")
        val got = ref.map { rec =>
          val in = rec("inside") == Val.F(true)
          (in, if in then rec("allowed") match
            case Val.M(s) => s
            case _ => Set.empty[String]
          else Set.empty[String])
        }
        assertEquals(got, Quotes.reference(toks, src, k), s"against QuoteK on $toks")
        steps += got.count(_._1)
        narrowed += got.count((in, s) => in && s.size < 3)
      println(s"cheap k = $k: $steps steps inside quotes, $narrowed narrowed")

  for k <- Seq(1, 3, 6) do
    test(s"k = $k: the weights equal the reference and QuoteK at every position"):
      val vocab = Vector("a", "b", "c", "[", "]")
      val p = Quotes.window(vocab, k, 48)
      val w = Compile(p)
      println(s"k = $k: ${p.layers.size} layers, ${p.layers.map(_.heads.size).sum} heads, ${w.layout.dims} dims, " +
        s"${w.layers.map(_.mlp.w1.length).sum} neurons")
      var (steps, narrowed) = (0, 0)
      for _ <- 1 to 60 do
        val (toks, src) = stream(10 + r.nextInt(37), Vector("a", "b", "c"))
        val in: Vector[Record] = toks.zip(src).map((t, s) =>
          Map[String, Val]("tok" -> c(t)) ++ (if s then Map("src" -> c("y")) else Map.empty))
        val ref = Interpret.run(p, in)
        assertEquals(Forward.run(p, w, in), ref, s"weights against the reference on $toks")
        val got = ref.map { rec =>
          val inside = rec("inside") == Val.F(true)
          (inside, if inside then rec("allowed") match
            case Val.M(s) => s
            case _ => Set.empty[String]
          else Set.empty[String])
        }
        assertEquals(got, Quotes.reference(toks, src, k), s"against QuoteK on $toks (source ${src.count(identity)})")
        steps += got.count(_._1)
        narrowed += got.count((in, s) => in && s.size < 3)
      println(s"k = $k: $steps steps inside quotes, $narrowed narrowed below the whole vocabulary")
      assert(narrowed > steps / 4, "too few narrowed steps to test anything")
