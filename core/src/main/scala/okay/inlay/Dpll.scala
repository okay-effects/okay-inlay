package okay.inlay

/**
 * DPLL IN THE DSL (docs/effects.md): experiments/Dpll.sc's machine as a wide `Machine`. Clauses are the first
 * positions (their positive and negative literals as sets); branches are slots, each holding an assignment (a variable
 * true, false, or neither). Every turn, for every branch at once:
 *
 *  - CONFLICT — a `Cover` head, slack 0: a clause whose positive literals are all false and negative all true;
 *  - UNITS — a `Cover` head, slack 1, no literal true: the unit clauses, their literals gathered; the unassigned among
 *    them are the implied literals — unit propagation over every clause in one head;
 *  - the rules: a conflict (or a variable implied both ways) rejects the branch; implications are assigned; nothing
 *    implied and all assigned accepts; else the first unassigned variable is DECIDED — the branch takes true and a free
 *    slot, allocated by prefix counts as in `OrParallel`, becomes its child with false.
 *
 * A turn is two rounds (A: the heads, the decisions, the ranks; B: a child reads its parent by rank, every branch's
 * transition). The accepted leaves are the models (#SAT).
 */
object Dpll:
  /** a clause: (variable, polarity) literals */
  type Clause = Seq[(Int, Boolean)]

  /** the reference: the number of models, by brute force */
  def models(n: Int, cnf: Seq[Clause]): Int =
    (0 until (1 << n)).count(a => cnf.forall(_.exists((v, s) => ((a >> v & 1) == 1) == s)))

  /** random 3-SAT */
  def random3(n: Int, m: Int, rnd: scala.util.Random): Vector[Clause] =
    Vector.fill(m)(rnd.shuffle((0 until n).toList).take(3).map(v => v -> rnd.nextBoolean()))

  /** a search's outcome: the accepted leaves and their assignments, the turns, the peak, the branch nodes */
  final case class Search(models: Int, found: Vector[Map[Int, Boolean]], turns: Int, peak: Int, nodes: Int,
      run: Machine.WideRun)

  final class Program(val n: Int, val slots: Int, val maxLen: Int = 1536, val maxTurns: Int = 64):
    private def cat(name: String, vs: Seq[String]) = Cat(name, vs.toVector)
    val vars: Vector[String] = (0 until n).map(_.toString).toVector
    val yes = Vector("yes")
    val ranks: Vector[String] = (0 to slots).map(_.toString).toVector
    // the clauses' positions
    val pos = Many("POS", vars); val neg = Many("NEG", vars); val kind = cat("KIND", Seq("clause"))
    // the advice's positions (a decision's handler): a variable, the value to try first, whether the adviser is sure
    val avar = cat("AVAR", vars); val aguess = cat("AGUESS", Seq("t", "f")); val asure = cat("ASURE", yes)
    // a branch's state
    val slot = cat("SLOT", (0 until slots).map(_.toString)); val phase = cat("PHASE", Seq("A", "B"))
    val act = cat("ACT", yes); val free = cat("FREE", yes); val done = cat("DONE", yes)
    val tv: Vector[Cat] = vars.map(v => cat(s"TV$v", vars)); val fv: Vector[Cat] = vars.map(v => cat(s"FV$v", vars))
    val myRank = cat("MYFR", ranks)
    val state: Vector[Cat] = Vector(slot, phase, act, free, done) ++ tv ++ fv :+ myRank
    val out: Map[String, Cat] = state.map(f => f.name -> cat("N_" + f.name, f.values)).toMap
    private def nx(f: Cat) = out(f.name)
    // on the way
    val ka = cat("KA", Seq("A", "B")); val y = cat("Y", yes); val ask = cat("ASK", Seq("clause"))
    val tset = Many("TSET", vars); val fset = Many("FSET", vars)
    val asg: Vector[Cat] = vars.map(v => cat(s"ASG$v", yes))
    val conf = Many("CONF", Vector("clause")); val upos = Many("UPOS", vars); val uneg = Many("UNEG", vars)
    val turn = cat("TURN", (0 to maxTurns).map(_.toString))
    val it: Vector[Cat] = vars.map(v => cat(s"IT$v", yes)); val ifl: Vector[Cat] = vars.map(v => cat(s"IF$v", yes))
    val ct: Vector[Cat] = vars.map(v => cat(s"CT$v", yes))
    val all = cat("ALL", yes); val imp = Many("IMP", yes); val contra = Many("CONTRA", yes)
    val req = cat("REQ", yes); val dec = cat("DEC", vars); val freeA = cat("FREEA", yes)
    val reqRank = cat("REQRANK", ranks); val freeRank = cat("FREERANK", ranks)
    val rqo = cat("RQO", ranks); val fro = cat("FRO", ranks)
    val gVal = cat("G_VAL", Seq("t", "f")); val gSure = cat("G_SURE", yes)
    val pHas = cat("P_HAS", yes); val pDec = cat("P_DEC", vars); val pGval = cat("P_GVAL", Seq("t", "f"))
    val pTv: Vector[Cat] = vars.map(v => cat(s"P_TV$v", vars))
    val pFv: Vector[Cat] = vars.map(v => cat(s"P_FV$v", vars))

    import Guard.*
    import Write.*
    private def rule(gs: Guard*)(ws: Write*) = Rule(gs, ws)
    private def keep(fs: Cat*) = fs.map(f => Copy(nx(f), f))
    private def toNext(f: Cat, v: String) = To(nx(f), v)
    private val lives = Seq(Is(phase, "B"), Is(act, "yes"), NotIn(conf, "clause"), NotIn(contra, "yes"))
    private val decides = Seq(Is(act, "yes"), NotIn(conf, "clause"), NotIn(contra, "yes"), NotIn(imp, "yes"),
      IsNot(all, "yes"))

    val layers: Seq[Layer] = Seq(
      // constants; the assignment as sets; which variables are assigned
      Layer(rules = Seq(rule()(To(ka, "A"), To(y, "yes"), To(ask, "clause")), Rule(Nil, Seq(Union(tset, tv))),
        Rule(Nil, Seq(Union(fset, fv)))) ++
        vars.indices.flatMap(v => Seq(rule(Is(tv(v), vars(v)))(To(asg(v), "yes")),
          rule(Is(fv(v), vars(v)))(To(asg(v), "yes"))))),
      // CONFLICT and UNITS over every clause; the turn
      Layer(heads = Seq(
        Cover(Seq(conf -> kind), Seq(ask), Seq(kind), Seq(fset -> pos, tset -> neg)),
        Cover(Seq(upos -> pos, uneg -> neg), Seq(ask), Seq(kind), Seq(fset -> pos, tset -> neg),
          apart = Seq(tset -> pos, fset -> neg), slack = 1),
        Count(turn, Seq(slot, ka), Seq(slot, phase))),
        rules = Seq(rule(Is(act, "yes") +: vars.indices.map(v => Is(asg(v), "yes"))*)(To(all, "yes")))),
      // implied literals (unassigned ones of the unit clauses), and those implied both ways
      Layer(rules = vars.indices.flatMap { v =>
        val base = Seq(Is(act, "yes"), NotIn(conf, "clause"), IsNot(asg(v), "yes"))
        Seq(Rule(base :+ In(upos, vars(v)), Seq(To(it(v), "yes"))),
          Rule(base :+ In(uneg, vars(v)), Seq(To(ifl(v), "yes"))),
          Rule(base ++ Seq(In(upos, vars(v)), In(uneg, vars(v))), Seq(To(ct(v), "yes"))))
      }),
      Layer(rules = Seq(Rule(Nil, Seq(Union(imp, it ++ ifl))), Rule(Nil, Seq(Union(contra, ct))))),
      // DECIDE the first unassigned variable (no conflict, nothing implied, not all assigned); requests, free slots
      Layer(rules = vars.indices.map(v =>
          Rule(decides ++ Seq(IsNot(asg(v), "yes")) ++ (0 until v).map(u => Is(asg(u), "yes")),
          Seq(To(dec, vars(v)))))),
      // THE DECISION'S HANDLER: the advice for the decided variable — none is plain DPLL (true first, a fork always);
      // sure: dive with the guess, no fork; unsure: fork, the guess first
      Layer(heads = Seq(Lookup(Seq(gVal -> aguess, gSure -> asure), Seq(dec), Seq(avar))),
        rules = Seq(Rule(Seq(Is(phase, "A")) ++ decides :+ IsNot(gSure, "yes"), Seq(To(req, "yes"))),
          rule(Is(phase, "A"), Is(free, "yes"))(To(freeA, "yes")))),
      Layer(heads = Seq(Count(reqRank, Seq(turn, y), Seq(turn, req)), Count(freeRank, Seq(turn, y), Seq(turn, freeA)))),
      Layer(rules = Seq(rule(Is(req, "yes"))(Copy(rqo, reqRank)), rule(Is(freeA, "yes"))(Copy(fro, freeRank)))),
      // phase B: a free slot of rank k reads the requester of rank k; every branch's transition
      Layer(heads = Seq(Lookup(Seq(pHas -> req, pDec -> dec, pGval -> gVal) ++ pTv.zip(tv) ++ pFv.zip(fv),
          Seq(turn, myRank),
          Seq(turn, rqo))),
        rules = Seq(rule(Is(phase, "A"))(Seq(toNext(phase, "B"), Copy(nx(myRank), fro)) ++
            keep(Seq(slot, act, free, done) ++ tv ++ fv*)*),
          rule(Is(phase, "B"), Is(act, "yes"), In(conf, "clause"))(toNext(phase, "A"), toNext(free, "yes"),
            Copy(nx(slot), slot)),
          rule(Is(phase, "B"), Is(act, "yes"), NotIn(conf, "clause"), In(contra, "yes"))(toNext(phase, "A"),
            toNext(free, "yes"), Copy(nx(slot), slot)),
          // an accepted branch keeps its model
          Rule(lives :+ Is(all, "yes"), Seq(toNext(phase, "A"), toNext(done, "yes"), Copy(nx(slot), slot)) ++
            keep(tv ++ fv*)),
          Rule(lives :+ IsNot(all, "yes"), Seq(toNext(phase, "A"), toNext(act, "yes"), Copy(nx(slot), slot))),
          rule(Is(phase, "B"), Is(free, "yes"), IsNot(pHas, "yes"))(toNext(phase, "A"), toNext(free, "yes"),
            Copy(nx(slot), slot)),
          Rule(Seq(Is(phase, "B"), Is(done, "yes")),
            Seq(toNext(phase, "A"), toNext(done, "yes"), Copy(nx(slot), slot)) ++
            keep(tv ++ fv*)),
          rule(Is(phase, "B"), Is(free, "yes"), Is(pHas, "yes"))(toNext(phase, "A"), toNext(act, "yes"),
            Copy(nx(slot), slot))) ++
          vars.indices.flatMap { v =>
            val going = lives :+ IsNot(all, "yes")
            Seq(
              // a living branch: true if it was, or implied, or decided now; false if it was, or implied
              Rule(going :+ Is(tv(v), vars(v)), Seq(toNext(tv(v), vars(v)))),
              Rule(going ++ Seq(IsNot(tv(v), vars(v)), Is(it(v), "yes")), Seq(toNext(tv(v), vars(v)))),
              Rule(going ++ Seq(IsNot(tv(v), vars(v)), IsNot(it(v), "yes"), Is(dec, vars(v)), IsNot(gVal, "f")),
                Seq(toNext(tv(v), vars(v)))),
              Rule(going ++ Seq(IsNot(fv(v), vars(v)), IsNot(ifl(v), "yes"), Is(dec, vars(v)), Is(gVal, "f")),
                Seq(toNext(fv(v), vars(v)))),
              Rule(going :+ Is(fv(v), vars(v)), Seq(toNext(fv(v), vars(v)))),
              Rule(going ++ Seq(IsNot(fv(v), vars(v)), Is(ifl(v), "yes")), Seq(toNext(fv(v), vars(v)))),
              // a child: its parent's assignment, and the decided variable the other way than the parent's try
              rule(Is(phase, "B"), Is(free, "yes"), Is(pHas, "yes"), IsNot(pDec, vars(v)))(Copy(nx(tv(v)), pTv(v))),
              rule(Is(phase, "B"), Is(free, "yes"), Is(pHas, "yes"), Is(pDec, vars(v)), Is(pGval, "f"))(
                toNext(tv(v), vars(v))),
              rule(Is(phase, "B"), Is(free, "yes"), Is(pHas, "yes"), Is(pFv(v), vars(v)))(toNext(fv(v), vars(v))),
              rule(Is(phase, "B"), Is(free, "yes"), Is(pHas, "yes"), IsNot(pFv(v), vars(v)), Is(pDec, vars(v)),
                IsNot(pGval, "f"))(toNext(fv(v), vars(v))))
          }))

    val step: okay.inlay.Program = okay.inlay.Program(Seq(pos, neg, kind, avar, aguess, asure) ++ state, layers, maxLen)
    val machine: Machine = Machine(step, state.map(f => out(f.name) -> f), _ => false)

    /** the clauses, then the advice: per variable advised, the value to try first and whether sure */
    def prefix(cnf: Seq[Clause], advice: Map[Int, (Boolean, Boolean)] = Map.empty): Vector[Interpret.Record] =
      cnf.toVector.map { c =>
        Map(pos.name -> Val.M(c.collect { case (v, true) => vars(v) }.toSet),
          neg.name -> Val.M(c.collect { case (v, false) => vars(v) }.toSet), kind.name -> Val.C(Some("clause")))
      } ++ advice.toVector.sortBy(_._1).map { case (v, (guess, sure)) =>
        Map(avar.name -> Val.C(Some(vars(v))), aguess.name -> Val.C(Some(if guess then "t" else "f"))) ++
          (if sure then Map(asure.name -> Val.C(Some("yes"))) else Map.empty)
      }
    val starts: Vector[Interpret.Record] = Vector.tabulate(slots) { s =>
      Map(slot.name -> Val.C(Some(s.toString)), phase.name -> Val.C(Some("A"))) ++
        (if s == 0 then Map(act.name -> Val.C(Some("yes"))) else Map(free.name -> Val.C(Some("yes"))))
    }
    private def is(r: Interpret.Record, f: Cat, v: String) = r.get(f.name).contains(Val.C(Some(v)))

    /** the formula solved: the models (the accepted leaves), the turns, the most branches at once, the branch nodes */
    def run(cnf: Seq[Clause], runner: Machine.Runner): (Int, Int, Int, Int, Machine.WideRun) =
      val g = guided(cnf, runner, Map.empty, firstOnly = false)
      (g.models, g.turns, g.peak, g.nodes, g.run)

    /** the formula searched with the advice as the decision's handler: the accepted leaves (their assignments), the
     * turns, the most branches at once, the branch nodes — to the first model when `firstOnly` */
    def guided(cnf: Seq[Clause], runner: Machine.Runner, advice: Map[Int, (Boolean, Boolean)], firstOnly: Boolean)
        : Dpll.Search =
      var peak = 0
      val stop: Vector[Interpret.Record] => Boolean = ss =>
        require(!ss.exists(s => is(s, phase, "A") && is(s, free, "yes") && is(s, act, "yes")),
          "a slot both free and active")
        if ss.forall(is(_, phase, "A")) then peak = peak max ss.count(is(_, act, "yes"))
        ss.forall(is(_, phase, "A")) && (!ss.exists(is(_, act, "yes")) || firstOnly && ss.exists(is(_, done, "yes")))
      val r = machine.runWide(prefix(cnf, advice), starts, runner, 2 * maxTurns, stop)
      // every request made a child, or slots ran out
      val requests = r.trace.count(t => is(t, req, "yes"))
      val children = r.trace.count(t => is(t, phase, "B") && is(t, pHas, "yes") && is(t, free, "yes"))
      require(requests == children, s"out of slots: $requests requests, $children children")
      val found = r.states.filter(is(_, done, "yes")).map(s => vars.indices.collect {
        case v if is(s, tv(v), vars(v)) => v -> true
        case v if is(s, fv(v), vars(v)) => v -> false
      }.toMap)
      Dpll.Search(found.size, found, r.rounds / 2, peak, 1 + requests, r)
