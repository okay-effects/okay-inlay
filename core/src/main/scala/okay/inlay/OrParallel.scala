package okay.inlay

/**
 * OR-PARALLEL SEARCH IN THE DSL (docs/effects.md): experiments/OrParallel.sc's machine as a wide `Machine` — a pool of
 * slots, every active slot executing one instruction of the same program each turn; `CHOOSE r` forks (the slot takes
 * r = true, a FREE slot becomes its child with r = false), children allocated without conflicts by prefix counts (a
 * requester of rank k is taken by the free slot of rank k); `REJECT` frees the slot; `ACCEPT` keeps a solution. The
 * choice effect, handled in parallel: all branches advance in the same turn.
 *
 * A turn is two rounds of positions, one a slot each. PHASE A: the instruction fetched at PC; who requests a child,
 * who is free; their ranks among this turn's requesters and free slots (`Count`). PHASE B: a free slot of rank k
 * reads the requester of rank k from this turn's phase A (all earlier positions) — its next address, its registers,
 * the register it chose — and every slot's transition is computed. The program: SAT over the variables, CHOOSE each,
 * then each clause checked (jump past it if a literal holds, else REJECT), then ACCEPT.
 */
object OrParallel:
  enum Ins:
    case Choose(r: Int)
    case Jump(r: Int, pol: Boolean, to: Int)
    case Reject
    case Accept

  /** the program for a CNF: clauses of (variable, polarity) */
  def compile(nv: Int, cnf: Seq[Seq[(Int, Boolean)]]): Vector[Ins] =
    val b = Vector.newBuilder[Ins]
    var at = 0
    for r <- 0 until nv do { b += Ins.Choose(r); at += 1 }
    for clause <- cnf do
      val past = at + clause.size + 1
      for (r, pol) <- clause do { b += Ins.Jump(r, pol, past); at += 1 }
      b += Ins.Reject; at += 1
    b += Ins.Accept
    b.result()

  /** the reference: the number of models, by brute force */
  def models(nv: Int, cnf: Seq[Seq[(Int, Boolean)]]): Int =
    (0 until (1 << nv)).count(m => cnf.forall(_.exists((r, pol) => ((m >> r & 1) == 1) == pol)))

  /** the machine for `nv` variables, programs of up to `npc` instructions, `slots` processors */
  final class Program(val nv: Int, val npc: Int, val slots: Int, val maxLen: Int = 1024, val maxTurns: Int = 64):
    private def cat(n: String, vs: Seq[String]) = Cat(n, vs.toVector)
    val addrs: Vector[String] = (0 to npc).map(_.toString).toVector
    val ops = Vector("CHOOSE", "JT", "REJECT", "ACCEPT")
    val regNames: Vector[String] = (0 until nv).map(_.toString).toVector
    val bools = Vector("f", "t")
    val yes = Vector("yes")
    val phases = Vector("A", "B")
    val ranks: Vector[String] = (0 to slots).map(_.toString).toVector
    // the program's positions
    val addr = cat("ADDR", addrs); val op = cat("OP", ops); val rr = cat("RR", regNames); val pol = cat("POL", bools)
    val tk = cat("TK", addrs); val nx = cat("NX", addrs)
    // a slot's state
    val slot = cat("SLOT", (0 until slots).map(_.toString)); val phase = cat("PHASE", phases)
    val act = cat("ACT", yes); val free = cat("FREE", yes); val done = cat("DONE", yes); val pc = cat("PC", addrs)
    val regs: Vector[Cat] = regNames.map(r => cat(s"REG$r", bools))
    val myRank = cat("MYFR", ranks)
    val state: Vector[Cat] = Vector(slot, phase, act, free, done, pc) ++ regs :+ myRank
    val out: Map[String, Cat] = state.map(f => f.name -> cat("N_" + f.name, f.values)).toMap
    private def n(f: Cat) = out(f.name)
    // computed on the way
    val ka = cat("KA", phases); val y = cat("Y", yes)
    val fOp = cat("F_OP", ops); val fR = cat("F_R", regNames); val fPol = cat("F_POL", bools)
    val fK = cat("F_K", addrs); val fNx = cat("F_NX", addrs)
    val turn = cat("TURN", (0 to maxTurns).map(_.toString))
    val req = cat("REQ", yes); val freeA = cat("FREEA", yes)
    val reqRank = cat("REQRANK", ranks); val freeRank = cat("FREERANK", ranks)
    val rqo = cat("RQO", ranks); val fro = cat("FRO", ranks)
    val pHas = cat("P_HAS", yes); val pR = cat("P_R", regNames); val pNx = cat("P_NX", addrs)
    val pRegs: Vector[Cat] = regNames.map(r => cat(s"P_REG$r", bools))

    import Guard.*
    import Write.*
    private def to(f: Cat, v: String) = To(n(f), v)
    private def copy(dst: Cat, src: Cat) = Copy(n(dst), src)
    private def keep(fs: Cat*) = fs.map(f => Copy(n(f), f))
    private def rule(gs: Guard*)(ws: Write*) = Rule(gs, ws)
    private def others(r: Int) = regs.indices.filter(_ != r).map(regs)

    val layers: Seq[Layer] = Seq(
      // constants every position holds: "A" (to count a slot's phase-A positions: its turn), "yes"
      Layer(rules = Seq(rule()(To(ka, "A"), To(y, "yes")))),
      // the instruction at PC; the turn
      Layer(heads = Seq(Lookup(Seq(fOp -> op, fR -> rr, fPol -> pol, fK -> tk, fNx -> nx), Seq(pc), Seq(addr)),
          Count(turn, Seq(slot, ka), Seq(slot, phase))),
        rules = Seq(rule(Is(phase, "A"), Is(act, "yes"), Is(fOp, "CHOOSE"))(To(req, "yes")),
          rule(Is(phase, "A"), Is(free, "yes"))(To(freeA, "yes")))),
      // ranks in this turn: requesters, free slots (prefix counts), kept where they mean something
      Layer(heads = Seq(Count(reqRank, Seq(turn, y), Seq(turn, req)), Count(freeRank, Seq(turn, y), Seq(turn, freeA)))),
      // (a count is decoded by its layer's MLP: read in the next)
      Layer(rules = Seq(rule(Is(req, "yes"))(Copy(rqo, reqRank)), rule(Is(freeA, "yes"))(Copy(fro, freeRank)))),
      // phase B: a free slot of rank k reads the requester of rank k; then every slot's transition
      Layer(heads = Seq(Lookup(Seq(pHas -> req, pR -> fR, pNx -> fNx) ++ pRegs.zip(regs), Seq(turn, myRank),
          Seq(turn, rqo))),
        rules = Seq(rule(Is(phase, "A"))(Seq(to(phase, "B"), copy(myRank, fro)) ++ keep(slot, act, free, done, pc) ++
          keep(regs*)*)) ++
          (0 until nv).map(r => rule(Is(phase, "B"), Is(act, "yes"), Is(fOp, "CHOOSE"), Is(fR, r.toString))(
            Seq(to(phase, "A"), to(act, "yes"), copy(pc, fNx), to(regs(r), "t")) ++ keep(slot) ++ keep(others(r)*)*)) ++
          (for r <- 0 until nv; v <- bools; holds <- Seq(true, false) yield
            rule(Is(phase, "B"), Is(act, "yes"), Is(fOp, "JT"), Is(fR, r.toString), Is(fPol, v),
                if holds then Is(regs(r), v) else IsNot(regs(r), v))(
              Seq(to(phase, "A"), to(act, "yes"), copy(pc, if holds then fK else fNx)) ++ keep(slot) ++ keep(regs*)*)) ++
          Seq(
            rule(Is(phase, "B"), Is(act, "yes"), Is(fOp, "REJECT"))(to(phase, "A"), to(free, "yes"), copy(slot, slot)),
            rule(Is(phase, "B"), Is(act, "yes"), Is(fOp, "ACCEPT"))(to(phase, "A"), to(done, "yes"), copy(slot, slot)),
            rule(Is(phase, "B"), Is(free, "yes"), IsNot(pHas, "yes"))(to(phase, "A"), to(free, "yes"), copy(slot, slot)),
            rule(Is(phase, "B"), Is(done, "yes"))(to(phase, "A"), to(done, "yes"), copy(slot, slot))) ++
          (0 until nv).map(r => rule(Is(phase, "B"), Is(free, "yes"), Is(pHas, "yes"), Is(pR, r.toString))(
            Seq(to(phase, "A"), to(act, "yes"), copy(pc, pNx), to(regs(r), "f"), copy(slot, slot)) ++
              others(r).map(o => Copy(n(o), pRegs(regs.indexOf(o))))*))))

    val step: okay.inlay.Program =
      okay.inlay.Program(Seq(addr, op, rr, pol, tk, nx) ++ state, layers, maxLen)
    val machine: Machine = Machine(step, state.map(f => out(f.name) -> f), _ => false)

    def prefix(prog: Vector[Ins]): Vector[Interpret.Record] =
      require(prog.size <= npc, s"${prog.size} instructions: the machine holds $npc")
      prog.zipWithIndex.map { (ins, a) =>
        val base = Map(addr.name -> Val.C(Some(a.toString)), nx.name -> Val.C(Some((a + 1).toString)))
        base ++ (ins match
          case Ins.Choose(r) => Map(op.name -> Val.C(Some("CHOOSE")), rr.name -> Val.C(Some(r.toString)))
          case Ins.Jump(r, p, k) => Map(op.name -> Val.C(Some("JT")), rr.name -> Val.C(Some(r.toString)),
            pol.name -> Val.C(Some(if p then "t" else "f")), tk.name -> Val.C(Some(k.toString)))
          case Ins.Reject => Map(op.name -> Val.C(Some("REJECT")))
          case Ins.Accept => Map(op.name -> Val.C(Some("ACCEPT"))))
      }
    /** slot 0 active at instruction 0, the others free */
    val starts: Vector[Interpret.Record] = Vector.tabulate(slots) { s =>
      Map(slot.name -> Val.C(Some(s.toString)), phase.name -> Val.C(Some("A"))) ++
        (if s == 0 then Map(act.name -> Val.C(Some("yes")), pc.name -> Val.C(Some("0")))
         else Map(free.name -> Val.C(Some("yes"))))
    }
    private def is(r: Interpret.Record, f: Cat, v: String) = r.get(f.name).contains(Val.C(Some(v)))

    /** the program run: the solutions (slots that accepted), the turns, the most slots active at once */
    def run(prog: Vector[Ins], runner: Machine.Runner): (Int, Int, Int, Machine.WideRun) =
      var peak = 0
      val stop: Vector[Interpret.Record] => Boolean = ss =>
        if ss.forall(is(_, phase, "A")) then peak = peak max ss.count(is(_, act, "yes"))
        ss.forall(s => is(s, phase, "A") && !is(s, act, "yes"))
      val r = machine.runWide(prefix(prog), starts, runner, 2 * maxTurns, stop)
      (r.states.count(is(_, done, "yes")), r.rounds / 2, peak, r)

  /** the experiment's formulas over three variables */
  val cases: Seq[(String, Seq[Seq[(Int, Boolean)]])] = Seq(
    "x ∧ y ∧ ¬z" -> Seq(Seq(0 -> true), Seq(1 -> true), Seq(2 -> false)),
    "(x ∨ y) ∧ (¬x ∨ z) ∧ (¬y ∨ ¬z)" -> Seq(Seq(0 -> true, 1 -> true), Seq(0 -> false, 2 -> true),
      Seq(1 -> false, 2 -> false)),
    "x ∧ ¬x" -> Seq(Seq(0 -> true), Seq(0 -> false)),
    "true (no clauses)" -> Seq())
