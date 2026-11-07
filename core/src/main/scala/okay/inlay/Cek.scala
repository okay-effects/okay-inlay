package okay.inlay

/**
 * THE CEK MACHINE WITH EFFECT HANDLERS, IN THE DSL (docs/effects.md): call-by-value λ-calculus with de Bruijn
 * indices, deep handlers and multi-shot continuations — experiments/Handlers.sc's machine written as a `Machine`: a
 * step program of two heads and sixteen rules, compiled by `Compile` like any program.
 *
 * The term's nodes are the first positions; each step appends one record — the registers (MODE, C, E, K, MK, the
 * value VT/VE/VK, the index I, the address ADDR) and at most one heap cell (CK, CA…CD, CV). A cell's address is the
 * position of the record that wrote it (`Builtin.next` at the step that writes it); NIL is none. Head RT reads the
 * term node at C, head RM the cell at ADDR; the rules are the transition.
 *
 * Effects: `handle body with h` installs a META cell (the handler, its environment, the outer segment and handlers)
 * and runs the body in a fresh segment; `perform` reads the nearest handler and runs it with k = (this segment, this
 * handler) at index 0 — capturing the continuation is copying two pointers; applying k resumes: the handler
 * reinstalled (deep), the segment continued with the value. The memory only grows, so k can be resumed twice.
 */
object Cek:
  /** a term: a variable (de Bruijn), a λ, an application, `handle body with h`, `perform` */
  enum Term:
    case V(i: Int)
    case L(body: Term)
    case A(f: Term, a: Term)
    case H(body: Term, handler: Term)
    case P

  import Term.*

  /** a node: kind 0 VAR, 1 LAM, 2 APP, 3 HANDLE, 4 PERFORM; a = body / function, b = argument / handler, i = index */
  final case class Node(kind: Int, a: Int, b: Int, i: Int)

  /** the term's nodes; node 0 a sentinel (NIL), the root node 1 */
  def flatten(t: Term): Vector[Node] =
    val nodes = scala.collection.mutable.ArrayBuffer(Node(-1, 0, 0, 0))
    def go(t: Term): Int =
      val me = nodes.size
      nodes += null
      nodes(me) = t match
        case V(i) => Node(0, 0, 0, i)
        case L(b) => Node(1, go(b), 0, 0)
        case A(f, a) => val fi = go(f); Node(2, fi, go(a), 0)
        case H(b, h) => val bi = go(b); Node(3, bi, go(h), 0)
        case P => Node(4, 0, 0, 0)
      me
    go(t)
    nodes.toVector

  val modes = Vector("EVAL", "LOOK", "RET", "APPLY", "HALT", "PERF", "RESUME", "POP")
  val kinds = Vector("VAR", "LAM", "APP", "HANDLE", "PERFORM")
  val indices: Vector[String] = (0 until 8).map(_.toString).toVector
  val cells = Vector("ENV", "ARG", "FUN", "META")
  val values = Vector("clo", "cont")

  /** the machine for terms of up to `maxLen` positions with their steps */
  final class Program(val maxLen: Int):
    /** pointers: enough bits for `next` of the last position */
    val bits: Int = Iterator.from(1).find(b => (1 << b) > maxLen).get
    private def ptr(n: String) = Bits(n, bits)
    val pos: Bits = Builtin.pos(bits)
    val next: Bits = Builtin.next(bits)
    // the term's fields (at the nodes' positions)
    val tkind = Cat("TKIND", kinds); val ta = ptr("TA"); val tb = ptr("TB"); val ti = Cat("TI", indices)
    // the registers and the cell (at the steps' positions)
    val mode = Cat("MODE", modes); val c = ptr("C"); val e = ptr("E"); val k = ptr("K"); val mk = ptr("MK")
    val vt = ptr("VT"); val ve = ptr("VE"); val vk = Cat("VK", values); val i = Cat("I", indices)
    val addr = ptr("ADDR")
    val ck = Cat("CK", cells); val ca = ptr("CA"); val cb = ptr("CB"); val cc = ptr("CC"); val cd = ptr("CD")
    val cv = Cat("CV", values)
    // what the heads read: the term node at C, the cell at ADDR
    val rtK = Cat("RT_K", kinds); val rtA = ptr("RT_A"); val rtB = ptr("RT_B"); val rtI = Cat("RT_I", indices)
    val rmK = Cat("RM_K", cells); val rmA = ptr("RM_A"); val rmB = ptr("RM_B"); val rmC = ptr("RM_C")
    val rmD = ptr("RM_D"); val rmV = Cat("RM_V", values)
    val state: Vector[Field] = Vector(mode, c, e, k, mk, vt, ve, vk, i, addr, ck, ca, cb, cc, cd, cv)
    /** the next state: one output per state field */
    val out: Map[String, Field] = state.map {
      case f: Cat => f.name -> Cat("N_" + f.name, f.values)
      case f: Bits => f.name -> Bits("N_" + f.name, f.bits)
      case f => f.name -> f
    }.toMap
    private def n(f: Field) = out(f.name)

    import Write.*
    import Guard.*
    private def to(f: Cat, v: String) = To(n(f).asInstanceOf[Cat], v)
    private def copy(dst: Field, src: Field) = Copy(n(dst), src)
    private def keep(fs: Field*) = fs.map(f => Copy(n(f), f))
    private def rule(guards: Guard*)(writes: Write*) = Rule(guards, writes)

    val rules: Seq[Rule] = Seq(
      // a variable: look its index up the environment
      rule(Is(mode, "EVAL"), Is(rtK, "VAR"))(Seq(to(mode, "LOOK"), copy(i, rtI), copy(addr, e)) ++
        keep(c, e, k, mk, vt, ve, vk)*),
      rule(Is(mode, "LOOK"), Is(i, "0"))(Seq(to(mode, "RET"), copy(vt, rmA), copy(ve, rmB), copy(vk, rmV),
        copy(addr, k)) ++ keep(c, e, k, mk)*)) ++
      (1 until indices.size).map(d => rule(Is(mode, "LOOK"), Is(i, d.toString))(
        Seq(to(mode, "LOOK"), to(i, (d - 1).toString), copy(addr, rmC)) ++ keep(c, e, k, mk, vt, ve, vk)*)) ++ Seq(
      // a λ: a closure, returned
      rule(Is(mode, "EVAL"), Is(rtK, "LAM"))(Seq(to(mode, "RET"), copy(vt, c), copy(ve, e), to(vk, "clo"),
        copy(addr, k)) ++ keep(c, e, k, mk)*),
      // an application: an ARG frame (the argument, its environment, the rest), the function evaluated
      rule(Is(mode, "EVAL"), Is(rtK, "APP"))(Seq(to(ck, "ARG"), copy(ca, rtB), copy(cb, e), copy(cc, k),
        copy(k, next), copy(c, rtA), to(mode, "EVAL")) ++ keep(e, mk, vt, ve, vk)*),
      // a value to an ARG frame: a FUN frame (the value, the rest), the argument evaluated
      rule(Is(mode, "RET"), Is(rmK, "ARG"))(Seq(copy(c, rmA), copy(e, rmB), to(ck, "FUN"), copy(ca, vt), copy(cb, ve),
        copy(cv, vk), copy(cc, rmC), copy(k, next), to(mode, "EVAL")) ++ keep(mk, vt, ve, vk)*),
      // a value to a FUN frame holding a closure: apply
      rule(Is(mode, "RET"), Is(rmK, "FUN"), Is(rmV, "clo"))(Seq(to(mode, "APPLY"), copy(c, rmA), copy(e, rmB),
        copy(k, rmC)) ++ keep(mk, vt, ve, vk)*),
      rule(Is(mode, "APPLY"))(Seq(to(ck, "ENV"), copy(ca, vt), copy(cb, ve), copy(cv, vk), copy(cc, e), copy(e, next),
        copy(c, rtA), to(mode, "EVAL")) ++ keep(k, mk, vt, ve, vk)*),
      // HANDLE body with h: install the handler (a META cell), the body in a fresh segment (K none)
      rule(Is(mode, "EVAL"), Is(rtK, "HANDLE"))(Seq(to(ck, "META"), copy(ca, rtB), copy(cb, e), copy(cc, k),
        copy(cd, mk), copy(mk, next), copy(c, rtA), to(mode, "EVAL")) ++ keep(e, vt, ve, vk)*),
      // PERFORM: read the nearest handler…
      rule(Is(mode, "EVAL"), Is(rtK, "PERFORM"))(Seq(to(mode, "PERF"), copy(addr, mk)) ++
        keep(c, e, k, mk, vt, ve, vk)*),
      // …and run it outside itself, k = (this segment, this handler) at index 0: CAPTURE is two pointers
      rule(Is(mode, "PERF"))(Seq(to(ck, "ENV"), to(cv, "cont"), copy(ca, k), copy(cb, mk), copy(cc, rmB),
        copy(e, next), copy(c, rmA), copy(k, rmC), copy(mk, rmD), to(mode, "EVAL")) ++ keep(vt, ve, vk)*),
      // a value to a FUN frame holding a continuation: RESUME — its handler read, reinstalled, the segment continued
      rule(Is(mode, "RET"), Is(rmK, "FUN"), Is(rmV, "cont"))(Seq(to(mode, "RESUME"), copy(c, rmA), copy(k, rmC),
        copy(addr, rmB)) ++ keep(e, mk, vt, ve, vk)*),
      rule(Is(mode, "RESUME"))(Seq(to(ck, "META"), copy(ca, rmA), copy(cb, rmB), copy(cc, k), copy(cd, mk),
        copy(mk, next), copy(k, c), copy(addr, c), to(mode, "RET")) ++ keep(c, e, vt, ve, vk)*),
      // a segment returned: pop its handler (the return clause the identity) — or halt when there is none
      rule(Is(mode, "RET"), Lacks(k))(Seq(to(mode, "POP"), copy(addr, mk)) ++ keep(c, e, k, mk, vt, ve, vk)*),
      rule(Is(mode, "POP"), Is(rmK, "META"))(Seq(copy(k, rmC), copy(mk, rmD), copy(addr, rmC), to(mode, "RET")) ++
        keep(c, e, vt, ve, vk)*),
      rule(Is(mode, "POP"), Lacks(mk))(Seq(to(mode, "HALT")) ++ keep(c, e, k, mk, vt, ve, vk)*))

    val heads: Seq[Head] = Seq(
      Lookup(Seq(rtK -> tkind, rtA -> ta, rtB -> tb, rtI -> ti), Seq(c), Seq(pos)),
      Lookup(Seq(rmK -> ck, rmA -> ca, rmB -> cb, rmC -> cc, rmD -> cd, rmV -> cv), Seq(addr), Seq(pos)))

    val step: okay.inlay.Program =
      okay.inlay.Program(Seq(pos, next, tkind, ta, tb, ti) ++ state, Seq(Layer(heads, rules)), maxLen)
    val machine: Machine =
      Machine(step, state.map(f => out(f.name) -> f), r => r(mode.name) == Val.C(Some("HALT")))

    /** the term's nodes as the first positions (the sentinel not among them: NIL is none) */
    def prefix(t: Term): Vector[Interpret.Record] =
      flatten(t).drop(1).map { nd =>
        Map(tkind.name -> Val.C(Some(kinds(nd.kind))), ti.name -> Val.C(Some(nd.i.toString)),
          ta.name -> Val.B(Option.when(nd.a > 0)(nd.a)), tb.name -> Val.B(Option.when(nd.b > 0)(nd.b)))
      }
    /** the first state: evaluate the root (node 1) in the empty environment */
    val start: Interpret.Record = Map(mode.name -> Val.C(Some("EVAL")), c.name -> Val.B(Some(1)))

    /** the machine run on a term by a runner: the answer's node (a closure's λ), the steps */
    def run(t: Term, runner: Machine.Runner): (Option[Int], Int, Machine.Run) =
      val r = machine.run(prefix(t), start, runner, maxLen - flatten(t).size)
      val answer = r.last(vt.name) match { case Val.B(v) => v; case _ => None }
      (answer, r.steps, r)

  /** THE REFERENCE MACHINE written the ordinary way (experiments/Handlers.sc's): the answer's node (a closure's λ, or
   * none for a continuation) and its iterations */
  def reference(t: Term): (Option[Int], Int) =
    val p = flatten(t)
    enum Value:
      case Clo(t: Int, env: List[Value])
      case Cont(seg: List[Frame], h: Handler)
    final case class Handler(body: Int, env: List[Value])
    enum Frame:
      case Arg(t: Int, env: List[Value])
      case Fun(v: Value)
    import Value.*, Frame.*
    var evaluating = true; var c = 1; var env: List[Value] = Nil; var v: Value = null
    var k: List[Frame] = Nil; var mk: List[(Handler, List[Frame])] = Nil; var done = false; var it = 0
    while !done do
      it += 1
      if evaluating then
        p(c).kind match
          case 0 => v = env(p(c).i); evaluating = false
          case 1 => v = Clo(c, env); evaluating = false
          case 2 => k = Arg(p(c).b, env) :: k; c = p(c).a
          case 3 => mk = (Handler(p(c).b, env), k) :: mk; k = Nil; c = p(c).a
          case _ =>
            val (h, outer) = mk.head
            env = Cont(k, h) :: h.env; c = h.body; k = outer; mk = mk.tail
      else
        k match
          case Arg(t, e) :: rest => k = Fun(v) :: rest; c = t; env = e; evaluating = true
          case Fun(Clo(f, fe)) :: rest => k = rest; c = p(f).a; env = v :: fe; evaluating = true
          case Fun(Cont(seg, h)) :: rest => mk = (h, rest) :: mk; k = seg
          case Nil =>
            mk match
              case (_, outer) :: rest => k = outer; mk = rest
              case Nil => done = true
    (v match { case Clo(t, _) => Some(t); case _ => None }, it)

  // Church booleans and the handlers of the experiment
  val tru: Term = L(L(V(1)))
  val fls: Term = L(L(V(0)))
  val not: Term = L(A(A(V(0), fls), tru))
  val and: Term = L(L(A(A(V(1), V(0)), fls)))
  val or: Term = L(L(A(A(V(1), tru), V(0))))
  def app(f: Term, as: Term*): Term = as.foldLeft(f)(A(_, _))
  /** resume k twice, with true and with false: "is there a choice that …" */
  val exists: Term = app(or, app(V(0), tru), app(V(0), fls))
  /** resume k twice: "for every choice …" */
  val forall: Term = app(and, app(V(0), tru), app(V(0), fls))
  val once: Term = app(V(0), tru)

  /** a closure's node read as a Church boolean */
  def name(t: Term, node: Option[Int]): String =
    val p = flatten(t)
    node.fold("none") { n =>
      p(n) match
        case Node(1, b, _, _) => p(b) match
          case Node(1, bb, _, _) => p(bb) match
            case Node(0, _, _, 1) => "true"
            case Node(0, _, _, 0) => "false"
            case _ => "λλ…"
          case _ => "λ…"
        case _ => "?"
    }

  /** the experiment's programs */
  val cases: Seq[(String, Term)] = Seq(
    "handle choose with k true" -> H(P, once),
    "∃x. x ∧ ¬x" -> H(app(L(app(and, V(0), app(not, V(0)))), P), exists),
    "∃x. ∃y. x ∧ ¬y" -> H(app(and, P, app(not, P)), exists),
    "∀x. ∀y. x ∨ ¬y" -> H(app(or, P, app(not, P)), forall),
    "∀x. ∀y. x ∨ y" -> H(app(or, P, P), forall),
    "∃x. ∃y. ∃z. x ∧ y ∧ ¬z" -> H(app(and, P, app(and, P, app(not, P))), exists))
