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


  /**
   * THE CHEAP FORM: the tokens d back by doubling — a position that knows its h previous tokens reads h more from the
   * position h back (one head a layer, log₂ k layers) — and the copy's window by a count of the tokens copied since the
   * open marker (the token d back is in the window when the count is above d: a rule, not a head). Heads: log₂ k + 4.
   */
  def quoteFast(vocab: Vector[String], k: Int, maxLen: Int): Program =
    val bits = 32 - Integer.numberOfLeadingZeros(maxLen - 1)
    val tok = Cat("tok", vocab)
    val src = Cat("src", Vector("y"))
    val pos = Builtin.pos(bits)
    val steps = Iterator.iterate(1)(_ * 2).takeWhile(_ < k).toVector // the doubling layers read h back: 1, 2, 4, …
    val backs = (1 +: steps).distinct.map(h => h -> Builtin.back(h, bits)).toMap
    val yn = Vector("y", "n")
    val (isMark, yes, plain, here) = (Cat("isMark", yn), Cat("yes", yn), Cat("plain", yn), Cat("here", Vector("y")))
    val prev = (1 to k).map(d => d -> Cat(s"prev$d", vocab)).toMap
    val (mark, markPos, cur) = (Cat("mark", vocab), Bits("markPos", bits), Cat("cur", vocab))
    val copied = Cat("copied", (0 to k).map(_.toString).toVector)
    val win = (1 until k).map(d => d -> Cat(s"win$d", vocab)).toMap
    val (inside, allowed) = (Flag("inside"), Many("allowed", vocab))
    // layer 1 + t (t = 0, 1, …): read from h = 2^t back its prev1..prevh as prev(h+1)..prev(2h)
    val doubling = steps.map(h => Lookup((1 to h).filter(_ + h <= k).map(e => prev(e + h) -> prev(e)), Seq(backs(h)),
      Seq(pos)))
    val first = Layer(heads = Seq(Lookup(Seq(prev(1) -> tok), Seq(backs(1)), Seq(pos))),
      rules = Seq(Rule(Nil, Seq(Write.To(yes, "y"), Write.To(plain, "n"), Write.To(here, "y"))),
        Rule(Seq(Guard.Is(tok, "[")), Seq(Write.To(isMark, "y"))),
        Rule(Seq(Guard.Is(tok, "]")), Seq(Write.To(isMark, "y"))),
        Rule(Seq(Guard.IsNot(tok, "["), Guard.IsNot(tok, "]")), Seq(Write.To(isMark, "n")))))
    val second = Layer(heads = doubling.take(1) :+ Lookup(Seq(mark -> tok, markPos -> pos), Seq(yes), Seq(isMark),
        latest = true),
      rules = Seq(Rule(Seq(Guard.Is(isMark, "n")), Seq(Write.Copy(cur, tok)))))
    val third = Layer(heads = doubling.slice(1, 2) :+ Count(copied, Seq(markPos, plain), Seq(markPos, isMark)),
      rules = Seq(Rule(Seq(Guard.Is(mark, "[")), Seq(Write.Raise(inside)))))
    val more = doubling.drop(2).map(h => Layer(heads = Seq(h)))
    // the window: the token d back while the count is above d
    val windows = Layer(rules = (1 until k).map(d => Rule((0 to d).map(n => Guard.IsNot(copied, n.toString)),
      Seq(Write.Copy(win(d), prev(d))))))
    val induction = Layer(heads = Seq(Lookup(Seq(allowed -> tok), cur +: (1 until k).map(win) :+ here,
      (1 to k).map(prev) :+ src, all = true, wild = true)))
    Program(Seq(tok, src, pos) ++ backs.toSeq.sortBy(_._1).map(_._2),
      Seq(first, second, third) ++ more ++ Seq(windows, induction), maxLen)

  for k <- Seq(1, 3, 6, 8, 16, 32) do
    test(s"the cheap form, k = $k: equals the reference and QuoteK at every position"):
      val vocab = Vector("a", "b", "c", "[", "]")
      val p = quoteFast(vocab, k, 64)
      val w = Compile(p)
      println(s"cheap k = $k: ${p.layers.size} layers, ${p.layers.map(_.heads.size).sum} heads " +
        s"(the first form: ${quote(vocab, k, 64).layers.map(_.heads.size).sum}), ${w.layout.dims} dims, " +
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
        assertEquals(got, reference(toks, src, k), s"against QuoteK on $toks")
        steps += got.count(_._1)
        narrowed += got.count((in, s) => in && s.size < 3)
      println(s"cheap k = $k: $steps steps inside quotes, $narrowed narrowed")

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
