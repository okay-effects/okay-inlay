package okay.inlay

import Interpret.Record

/** THE QUOTE AS WEIGHTS (specs/dsl.md v1): a program of lookups and rules whose `inside` and `allowed` equal QuoteK —
 * the runtime quote with a window of k copied tokens — at every position of random streams */
class QuoteSuite extends munit.FunSuite:
  private val r = scala.util.Random(11)
  private def c(v: String) = Val.C(Some(v))

  /** the program for a vocabulary (with the markers "[" and "]") and a window k */
  def quote(vocab: Vector[String], k: Int, maxLen: Int): Program =
    val bits = 32 - Integer.numberOfLeadingZeros(maxLen - 1)
    val tok = Cat("tok", vocab)
    val src = Cat("src", Vector("y"))
    val pos = Builtin.pos(bits)
    val back = (1 to k).map(d => Builtin.back(d, bits))
    val yn = Vector("y", "n")
    val (isMark, yes, plain, here) = (Cat("isMark", yn), Cat("yes", yn), Cat("plain", yn), Cat("here", Vector("y")))
    val prevs = (1 to k).map(d => Cat(s"prev$d", vocab))
    val (mark, markPos, cur) = (Cat("mark", vocab), Bits("markPos", bits), Cat("cur", vocab))
    val wins = (1 until k).map(d => Cat(s"win$d", vocab))
    val (inside, allowed) = (Flag("inside"), Many("allowed", vocab))
    Program(Seq(tok, src, pos) ++ back, Seq(
      // every position's tokens d back; which are markers; constants to ask by
      Layer(heads = prevs.zip(back).map((pv, b) => Lookup(Seq(pv -> tok), Seq(b), Seq(pos))),
        rules = Seq(Rule(Nil, Seq(Write.To(yes, "y"), Write.To(plain, "n"), Write.To(here, "y"))),
          Rule(Seq(Guard.Is(tok, "[")), Seq(Write.To(isMark, "y"))),
          Rule(Seq(Guard.Is(tok, "]")), Seq(Write.To(isMark, "y"))),
          Rule(Seq(Guard.IsNot(tok, "["), Guard.IsNot(tok, "]")), Seq(Write.To(isMark, "n"))))),
      // the latest marker and where it is; the current token unless it is a marker
      Layer(heads = Seq(Lookup(Seq(mark -> tok, markPos -> pos), Seq(yes), Seq(isMark), latest = true)),
        rules = Seq(Rule(Seq(Guard.Is(isMark, "n")), Seq(Write.Copy(cur, tok))))),
      // the copied tokens d back: after the same marker and not a marker, else none (a wildcard below)
      Layer(heads = wins.zip(back).map((w, b) => Lookup(Seq(w -> tok), Seq(b, markPos, plain), Seq(pos, markPos,
          isMark))),
        rules = Seq(Rule(Seq(Guard.Is(mark, "[")), Seq(Write.Raise(inside))))),
      // the source positions whose k tokens before match the copy's last ones: their tokens
      Layer(heads = Seq(Lookup(Seq(allowed -> tok), cur +: wins :+ here, prevs :+ src, all = true, wild = true)))),
      maxLen)

  /** QuoteK: at each position, inside a quote or not, and the tokens a copy may continue with */
  def reference(toks: Vector[String], src: Vector[Boolean], k: Int): Vector[(Boolean, Set[String])] =
    toks.indices.map { i =>
      val markAt = (0 to i).reverse.find(j => toks(j) == "[" || toks(j) == "]")
      val inside = markAt.exists(toks(_) == "[")
      val m = markAt.fold(0)(i - _)
      val w = math.min(m, k)
      val copied = toks.slice(i - w + 1, i + 1)
      val allowed = (0 to i).filter(p => src(p) && p - w >= 0 && toks.slice(p - w, p) == copied).map(toks).toSet
      (inside, if inside then allowed else Set.empty[String])
    }.toVector

  private def stream(n: Int, words: Vector[String]): (Vector[String], Vector[Boolean]) =
    val s = 3 + r.nextInt(n / 2)
    val toks = Vector.tabulate(n)(i =>
      if i >= s && r.nextInt(6) == 0 then (if r.nextBoolean() then "[" else "]") else words(r.nextInt(words.size)))
    (toks, Vector.tabulate(n)(_ < s))

  for k <- Seq(1, 3, 6) do
    test(s"k = $k: the weights equal the reference and QuoteK at every position"):
      val vocab = Vector("a", "b", "c", "[", "]")
      val p = quote(vocab, k, 48)
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
        assertEquals(got, reference(toks, src, k), s"against QuoteK on $toks (source ${src.count(identity)})")
        steps += got.count(_._1)
        narrowed += got.count((in, s) => in && s.size < 3)
      println(s"k = $k: $steps steps inside quotes, $narrowed narrowed below the whole vocabulary")
      assert(narrowed > steps / 4, "too few narrowed steps to test anything")
