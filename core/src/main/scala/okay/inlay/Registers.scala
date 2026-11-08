package okay.inlay

/**
 * A REGISTER MACHINE IN THE DSL (experiments/Looped.sc): registers A and B, a program counter, the ops INC_A, DEC_B,
 * JZ_B k, JMP k, HALT. The program is data — the first positions — and the same compiled weights run any program: a
 * head fetches the instruction at PC, rules make the step, `Machine` appends the next state a position a step until
 * HALT. An interpreter in weights, the program in the context.
 */
object Registers:
  enum Op:
    case IncA, DecB, Halt
    case JzB(to: Int)
    case Jmp(to: Int)

  /** A := A + B, and A := A + 2B */
  val add: Vector[Op] = Vector(Op.JzB(4), Op.IncA, Op.DecB, Op.Jmp(0), Op.Halt)
  val add2: Vector[Op] = Vector(Op.JzB(5), Op.IncA, Op.IncA, Op.DecB, Op.Jmp(0), Op.Halt)

  /** the reference: the program run the ordinary way — the registers at HALT, and the steps */
  def interpret(p: Vector[Op], a0: Int, b0: Int): (Int, Int, Int) =
    var (pc, a, b, steps) = (0, a0, b0, 0)
    while p(pc) != Op.Halt do
      steps += 1
      p(pc) match
        case Op.IncA => a += 1; pc += 1
        case Op.DecB => b -= 1; pc += 1
        case Op.JzB(k) => pc = if b == 0 then k else pc + 1
        case Op.Jmp(k) => pc = k
        case Op.Halt => ()
    (a, b, steps)

  /** the machine for programs of up to `npc` instructions, registers in 0 … `top` */
  final class Program(val npc: Int = 8, val top: Int = 15, val maxLen: Int = 128):
    private def cat(n: String, vs: Seq[String]) = Cat(n, vs.toVector)
    val addrs: Vector[String] = (0 to npc).map(_.toString).toVector
    val nums: Vector[String] = (0 to top).map(_.toString).toVector
    val ops = Vector("INC_A", "DEC_B", "JZ_B", "JMP", "HALT")
    // the program's positions; the state's
    val addr = cat("ADDR", addrs); val op = cat("OP", ops); val arg = cat("ARG", addrs); val nx = cat("NX", addrs)
    val pc = cat("PC", addrs); val a = cat("A", nums); val b = cat("B", nums); val halted = cat("HALTED", Seq("yes"))
    val state: Vector[Cat] = Vector(pc, a, b, halted)
    val out: Map[String, Cat] = state.map(f => f.name -> cat("N_" + f.name, f.values)).toMap
    private def n(f: Cat) = out(f.name)
    // the fetched instruction
    val fOp = cat("F_OP", ops); val fArg = cat("F_ARG", addrs); val fNx = cat("F_NX", addrs)

    import Guard.*
    import Write.*
    private def rule(gs: Guard*)(ws: Write*) = Rule(gs, ws)
    private def keep(fs: Cat*) = fs.map(f => Copy(n(f), f))
    private val running = Seq(IsNot(halted, "yes"))

    val layers: Seq[Layer] = Seq(
      Layer(heads = Seq(Lookup(Seq(fOp -> op, fArg -> arg, fNx -> nx), Seq(pc), Seq(addr)))),
      Layer(rules =
        nums.indices.init.map(v => Rule(running ++ Seq(Is(fOp, "INC_A"), Is(a, nums(v))),
          Seq(To(n(a), nums(v + 1)), Copy(n(pc), fNx)) ++ keep(b))) ++
        nums.indices.tail.map(v => Rule(running ++ Seq(Is(fOp, "DEC_B"), Is(b, nums(v))),
          Seq(To(n(b), nums(v - 1)), Copy(n(pc), fNx)) ++ keep(a))) ++
        Seq(
          Rule(running ++ Seq(Is(fOp, "JZ_B"), Is(b, "0")), Copy(n(pc), fArg) +: keep(a, b)),
          Rule(running ++ Seq(Is(fOp, "JZ_B"), IsNot(b, "0")), Copy(n(pc), fNx) +: keep(a, b)),
          Rule(running :+ Is(fOp, "JMP"), Copy(n(pc), fArg) +: keep(a, b)),
          Rule(running :+ Is(fOp, "HALT"), To(n(halted), "yes") +: keep(pc, a, b)),
          rule(Is(halted, "yes"))(keep(pc, a, b, halted)*))))

    val step: okay.inlay.Program = okay.inlay.Program(Seq(addr, op, arg, nx) ++ state, layers, maxLen)
    val machine: Machine =
      Machine(step, state.map(f => out(f.name) -> f), r => r.get(halted.name).contains(Val.C(Some("yes"))))

    def prefix(p: Vector[Op]): Vector[Interpret.Record] = p.zipWithIndex.map { (o, i) =>
      val (name, to) = o match
        case Op.IncA => ("INC_A", None)
        case Op.DecB => ("DEC_B", None)
        case Op.Halt => ("HALT", None)
        case Op.JzB(k) => ("JZ_B", Some(k))
        case Op.Jmp(k) => ("JMP", Some(k))
      Map(addr.name -> Val.C(Some(i.toString)), op.name -> Val.C(Some(name)), nx.name -> Val.C(Some((i + 1).toString)),
        arg.name -> Val.C(to.map(_.toString)))
    }

    /** the program run on (A, B): the registers at HALT, the steps, the run */
    def run(p: Vector[Op], a0: Int, b0: Int, runner: Machine.Runner): (Int, Int, Int, Machine.Run) =
      val r = machine.run(prefix(p), Map(pc.name -> Val.C(Some("0")), a.name -> Val.C(Some(a0.toString)),
        b.name -> Val.C(Some(b0.toString))), runner, maxLen - p.size - 1)
      def num(f: Cat) = r.last(f.name) match { case Val.C(Some(v)) => v.toInt; case _ => -1 }
      // the last step only raised HALTED: the steps before it are the program's
      (num(a), num(b), r.steps - 1, r)
