package okay.inlay

/** EXACT QUOTES AS PROGRAMS (specs/quotes.md, specs/dsl.md v1): between the markers "[" and "]", the tokens a copy
 * may continue with — the source positions whose k tokens before match the copy's last ones. `window`: the first form
 * (2k heads); `cheap`: doubling and a count (log2 k + 4 heads); `reference`: QuoteK, what both must equal */
object Quotes:
  /** the program for a vocabulary (with the markers "[" and "]") and a window k */
  def window(vocab: Vector[String], k: Int, maxLen: Int): Program =
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


  /**
   * THE CHEAP FORM: the tokens d back by doubling — a position that knows its h previous tokens reads h more from the
   * position h back (one head a layer, log₂ k layers) — and the copy's window by a count of the tokens copied since the
   * open marker (the token d back is in the window when the count is above d: a rule, not a head). Heads: log₂ k + 4.
   */
  def cheap(vocab: Vector[String], k: Int, maxLen: Int): Program =
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


  /**
   * THE CHEAP FORM FOR A LARGE VOCABULARY: the token as ±1 bits (`tok`, ⌈log₂ |vocab|⌉ bits) wherever it is compared
   * or copied, one-hot (`hot`) only where the allowed set is gathered and the markers recognized. `vocab` holds the
   * markers "[" and "]". Inputs per position: `tok` (the token's index in `vocab`), `hot` (the token), `src`.
   */
  def cheapBits(vocab: Vector[String], k: Int, maxLen: Int): Program =
    val bits = 32 - Integer.numberOfLeadingZeros(maxLen - 1)
    val idBits = math.max(1, 32 - Integer.numberOfLeadingZeros(vocab.size - 1))
    val tok = Bits("tok", idBits)
    val hot = Cat("hot", vocab)
    val src = Cat("src", Vector("y"))
    val pos = Builtin.pos(bits)
    val steps = Iterator.iterate(1)(_ * 2).takeWhile(_ < k).toVector
    val backs = (1 +: steps).distinct.map(h => h -> Builtin.back(h, bits)).toMap
    val yn = Vector("y", "n")
    val (isMark, yes, plain, here) = (Cat("isMark", yn), Cat("yes", yn), Cat("plain", yn), Cat("here", Vector("y")))
    val kind = Cat("kind", Vector("open", "close"))
    val prev = (1 to k).map(d => d -> Bits(s"prev$d", idBits)).toMap
    val (mark, markPos, cur) = (Cat("mark", kind.values), Bits("markPos", bits), Bits("cur", idBits))
    val copied = Cat("copied", (0 to k).map(_.toString).toVector)
    val win = (1 until k).map(d => d -> Bits(s"win$d", idBits)).toMap
    val (inside, allowed) = (Flag("inside"), Many("allowed", vocab))
    val doubling = steps.map(h => Lookup((1 to h).filter(_ + h <= k).map(e => prev(e + h) -> prev(e)), Seq(backs(h)),
      Seq(pos)))
    val first = Layer(heads = Seq(Lookup(Seq(prev(1) -> tok), Seq(backs(1)), Seq(pos))),
      rules = Seq(Rule(Nil, Seq(Write.To(yes, "y"), Write.To(plain, "n"), Write.To(here, "y"))),
        Rule(Seq(Guard.Is(hot, "[")), Seq(Write.To(isMark, "y"), Write.To(kind, "open"))),
        Rule(Seq(Guard.Is(hot, "]")), Seq(Write.To(isMark, "y"), Write.To(kind, "close"))),
        Rule(Seq(Guard.IsNot(hot, "["), Guard.IsNot(hot, "]")), Seq(Write.To(isMark, "n")))))
    val second = Layer(heads = doubling.take(1) :+ Lookup(Seq(mark -> kind, markPos -> pos), Seq(yes), Seq(isMark),
        latest = true),
      rules = Seq(Rule(Seq(Guard.Is(isMark, "n")), Seq(Write.Copy(cur, tok)))))
    val third = Layer(heads = doubling.slice(1, 2) :+ Count(copied, Seq(markPos, plain), Seq(markPos, isMark)),
      rules = Seq(Rule(Seq(Guard.Is(mark, "open")), Seq(Write.Raise(inside)))))
    val more = doubling.drop(2).map(h => Layer(heads = Seq(h)))
    val windows = Layer(rules = (1 until k).map(d => Rule((0 to d).map(n => Guard.IsNot(copied, n.toString)),
      Seq(Write.Copy(win(d), prev(d))))))
    val induction = Layer(heads = Seq(Lookup(Seq(allowed -> hot), cur +: (1 until k).map(win) :+ here,
      (1 to k).map(prev) :+ src, all = true, wild = true)))
    Program(Seq(tok, hot, src, pos) ++ backs.toSeq.sortBy(_._1).map(_._2),
      Seq(first, second, third) ++ more ++ Seq(windows, induction), maxLen)

  /** a token stream as `cheapBits`'s inputs: each token's index and one-hot, the source positions marked */
  def bitsInputs(vocab: Vector[String], toks: Vector[String], src: Vector[Boolean]): Vector[Interpret.Record] =
    toks.zip(src).map { (t, s) =>
      Map[String, Val]("tok" -> Val.B(Some(vocab.indexOf(t))), "hot" -> Val.C(Some(t))) ++
        (if s then Map("src" -> Val.C(Some("y"))) else Map.empty)
    }
