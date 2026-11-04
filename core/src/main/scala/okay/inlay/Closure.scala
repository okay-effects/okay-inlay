package okay.inlay

/**
 * TRANSITIVE CLOSURE WITH A NEURAL RELATION (specs/bridge.md §7, bridge-4): a position is a sentence naming two
 * people, `first` and `second` in the order named; a READER (a probe, or the model asked) says whether the first is
 * the superior (`sup`) — the one judgement that is the model's. Everything after it is exact: the edge (a over b) by
 * rules; the names reachable from b (`R`) doubled each round (a head: positions j whose a is in R — a set query
 * against a one-hot key — gather their R; a union); which sentences the set rests on (`P`, positions, the same
 * way: every derived fact attributed to its readings); a question position (`qa`, `qb`: is qa over qb?) gathering
 * the R and P of the sentences starting at qa and answering `yes`, with `on` the sentences it rests on; and `cycle`
 * at a sentence whose a is reachable from its own b — a contradiction, the sentences to blame.
 * `rounds` layers of doubling reach 2^rounds hops. Not causal: a block of its own over the sentences.
 */
object Closure:
  final case class Fields(names: Vector[String], maxLen: Int, rounds: Int):
    val positions: Vector[String] = (1 until maxLen).map(_.toString).toVector
    val first = Cat("first", names); val second = Cat("second", names); val sup = Flag("sup")
    val id = Cat("id", positions)
    val qa = Cat("qa", names); val qb = Cat("qb", names)
    val a = Cat("a", names); val b = Cat("b", names)
    def r(k: Int) = Many(s"r$k", names); def p(k: Int) = Many(s"p$k", positions)
    def t(k: Int) = Many(s"t$k", names); def tp(k: Int) = Many(s"tp$k", positions)
    val s = Many("s", names); val sp = Many("sp", positions)
    val yes = Flag("yes"); val on = Many("on", positions); val cycle = Flag("cycle")

  def program(ns: Vector[String], len: Int, k0: Int): (Program, Fields) =
    val f = Fields(ns, len, k0)
    import f.*
    // the edge: a = first, b = second when sup; swapped when not (a sentence position has both names)
    val edge = Layer(rules = names.flatMap(n => Seq(
      Rule(Seq(Guard.On(sup), Guard.Is(first, n)), Seq(Write.To(a, n))),
      Rule(Seq(Guard.Off(sup), Guard.Is(first, n)), Seq(Write.To(b, n))),
      Rule(Seq(Guard.On(sup), Guard.Is(second, n)), Seq(Write.To(b, n))),
      Rule(Seq(Guard.Off(sup), Guard.Is(second, n)), Seq(Write.To(a, n))))))
    // round 0: the one hop (b) and the sentence itself, as sets
    val zero = Layer(rules = Seq(Rule(Nil, Seq(Write.Union(r(0), Seq(b)), Write.Union(p(0), Seq(id))))))
    // round k: gather from the sentences whose a is in r(k−1): their r and p; unite with one's own
    val doubling = (1 to rounds).flatMap { k =>
      val key = if k == 1 then Seq(b) else Seq(r(k - 1))
      Seq(
        Layer(heads = Seq(Lookup(Seq(t(k) -> r(k - 1), tp(k) -> p(k - 1)), key, Seq(a), all = true))),
        Layer(rules = Seq(Rule(Nil, Seq(Write.Union(r(k), Seq(r(k - 1), t(k))),
          Write.Union(p(k), Seq(p(k - 1), tp(k))))))))
    }
    // the question: what the sentences starting at qa reach, what they rest on; a cycle at a sentence
    val ask = Layer(heads = Seq(Lookup(Seq(s -> r(rounds), sp -> p(rounds)), Seq(qa), Seq(a), all = true)))
    val answer = Layer(rules =
      names.map(n => Rule(Seq(Guard.Is(qb, n), Guard.In(s, n)), Seq(Write.Raise(yes)))) ++
      names.map(n => Rule(Seq(Guard.Is(a, n), Guard.In(r(rounds), n)), Seq(Write.Raise(cycle)))) :+
      Rule(Nil, Seq(Write.Union(on, Seq(sp)))))
    (Program(Seq(first, second, sup, id, qa, qb), Seq(edge, zero) ++ doubling ++ Seq(ask, answer), maxLen,
      causal = false), f)

  /** the sentences' records (position i: its names in order, the reader's judgement) and the question's */
  def inputs(sentences: Vector[(String, String, Boolean)], question: (String, String))
      : Vector[Interpret.Record] =
    sentences.zipWithIndex.map { case ((x, y, sup), i) =>
      Map("first" -> Val.C(Some(x)), "second" -> Val.C(Some(y)), "sup" -> Val.F(sup),
        "id" -> Val.C(Some((i + 1).toString)))
    } :+ Map("qa" -> Val.C(Some(question._1)), "qb" -> Val.C(Some(question._2)))

  /** the plain truth: edges (a, b) from the judgements, reachability by search, the sentences on some path, and
   * the sentences on a cycle */
  def reference(sentences: Vector[(String, String, Boolean)], question: (String, String))
      : (Boolean, Set[Int], Set[Int]) =
    val edges = sentences.zipWithIndex.map { case ((x, y, sup), i) => (if sup then (x, y) else (y, x), i + 1) }
    def reach(from: String): (Set[String], Set[Int]) =
      var seen = Set.empty[String]; var used = Set.empty[Int]; var front = Set(from)
      while front.nonEmpty do
        val next = edges.filter((e, _) => front(e._1))
        used ++= next.map(_._2)
        val names = next.map(_._1._2).toSet -- seen
        seen ++= names; front = names
      (seen, used)
    val (names, used) = reach(question._1)
    val cycles = edges.filter((e, _) => reach(e._2)._1(e._1)).map(_._2).toSet
    (names(question._2), used, cycles)
