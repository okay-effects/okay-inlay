var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// THE CEK MACHINE IN A TRANSFORMER. Call-by-value λ-calculus with de Bruijn indices. The program (a term's nodes) is
// at the context's first positions; every step APPENDS one record: the machine's registers and, at most, one heap
// cell (an environment link or a continuation frame). The heap is the trace itself: a cell's address is the
// position of the record that wrote it, read back by attention. One block, the same weights every step:
//   head A: the term node at C          head B: the record (cell) at ADDR (prefetched by the step before)
//   MLP:    the transition, AND-neurons generated from a table of rules (a compiler in miniature)
// float32, exact: one-hot everything, λ large.
enum T:
  case V(i: Int); case L(b: T); case A(f: T, a: T); case H(body: T, handler: T); case P
import T.*

// ---- the program as positions: 0 is NIL (a sentinel), nodes from 1
val maxSteps = 1000
case class Node(kind: Int, a: Int, b: Int, i: Int)   // kind 0 VAR, 1 LAM, 2 APP; a = body/fun, b = arg, i = index
def flatten(t: T): Vector[Node] =
  val nodes = scala.collection.mutable.ArrayBuffer(Node(-1, 0, 0, 0))
  def go(t: T): Int =
    val me = nodes.size; nodes += null
    nodes(me) = t match
      case V(i)    => Node(0, 0, 0, i)
      case L(b)    => Node(1, go(b), 0, 0)
      case A(f, a) => val fi = go(f); Node(2, fi, go(a), 0)
      case H(b, h) => val bi = go(b); Node(3, bi, go(h), 0)
      case P       => Node(4, 0, 0, 0)
    me
  go(t); nodes.toVector

// ---- groups of the residual stream (one-hot each)
val D = 8                                                   // de Bruijn indices 0..7
val modes = Vector("EVAL", "LOOK", "RET", "APPLY", "HALT", "PERF", "RESUME", "POP")
val cells = Vector("NONE", "ENV", "ARG", "FUN", "META")    // META: a handler installed (handler, its env, outer K, outer MK)
type Vec = Map[String, Array[Float]]
var N = 0                                                    // positions: set per program
def hot(n: Int, i: Int) = { val a = Array.fill(n)(0f); a(i) = 1f; a }
def size(g: String) = g match
  case "MODE" => modes.size; case "CK" | "RM_K" => cells.size; case "TKIND" | "RT_K" => 5
  case "VK" | "CV" | "RM_V" => 2                              // a value: 0 a closure, 1 a continuation
  case "I" | "TI" | "RT_I" => D; case _ => N
def idx(v: Vec, g: String) = v(g).indexWhere(_ == 1f)
def vec(kv: (String, Int)*): Vec = kv.map((g, i) => g -> hot(size(g), i)).toMap

// ---- attention: score = λ · q·k over every position so far; the winner's groups copied (W_OV)
val lambda = 250f
def head(seq: IndexedSeq[Vec], q: Array[Float], copy: Seq[(String, String)]): Vec =
  val scores = seq.map(p => lambda * p("SELF").indices.map(j => q(j) * p("SELF")(j)).sum)
  val mx = scores.max; val e = scores.map(s => math.exp((s - mx).toDouble).toFloat); val w = e.map(_ / e.sum)
  copy.map { (from, to) =>
    val out = Array.fill(size(to))(0f)
    for (p, wi) <- seq.zip(w) if p.contains(from); j <- out.indices do out(j) += wi * p(from)(j)
    to -> out }.toMap

// ---- the MLP from rules: guards (group = value) and assignments (constant, or a copy of a group, maybe shifted)
enum Set:
  case Const(g: String, v: Int); case Copy(g: String, src: String, shift: Int = 0)
case class Rule(guards: Seq[(String, Int)], sets: Seq[Set])
case class Neuron(in: Seq[(String, Int)], out: (String, Int))
def neurons(rules: Seq[Rule]): Seq[Neuron] = rules.flatMap { r =>
  r.sets.flatMap {
    case Set.Const(g, v) => Seq(Neuron(r.guards, g -> v))
    case Set.Copy(g, src, s) => (0 until size(src)).filter(p => p + s >= 0 && p + s < size(g))
        .map(p => Neuron(r.guards :+ (src -> p), g -> (p + s)))
  } }
def mlp(ns: Seq[Neuron], z: Vec): Vec =
  val out = scala.collection.mutable.Map.empty[String, Array[Float]]
  for n <- ns do
    val h = math.max(0f, n.in.map((g, i) => z(g)(i)).sum - (n.in.size - 1))    // AND: ReLU(x1+…+xn − (n−1))
    if h != 0f then out.getOrElseUpdate(n.out._1, Array.fill(size(n.out._1))(0f))(n.out._2) += h
  out.toMap

import Set.*
def keep(gs: String*) = gs.map(g => Copy(g, g))
def m(s: String) = modes.indexOf(s); def c(s: String) = cells.indexOf(s)
val CLO = 0; val CONT = 1
val rules: Seq[Rule] = Seq(
  Rule(Seq("MODE" -> m("EVAL"), "RT_K" -> 0), Seq(Const("MODE", m("LOOK")), Copy("I", "RT_I"), Copy("ADDR", "E"),
    Const("CK", 0)) ++ keep("C", "E", "K", "MK", "VT", "VE", "VK")),
  Rule(Seq("MODE" -> m("LOOK"), "I" -> 0), Seq(Const("MODE", m("RET")), Copy("VT", "RM_A"), Copy("VE", "RM_B"),
    Copy("VK", "RM_V"), Copy("ADDR", "K"), Const("I", 0), Const("CK", 0)) ++ keep("C", "E", "K", "MK"))) ++
  (1 until D).map(i => Rule(Seq("MODE" -> m("LOOK"), "I" -> i), Seq(Const("MODE", m("LOOK")), Const("I", i - 1),
    Copy("ADDR", "RM_C"), Const("CK", 0)) ++ keep("C", "E", "K", "MK", "VT", "VE", "VK"))) ++ Seq(
  Rule(Seq("MODE" -> m("EVAL"), "RT_K" -> 1), Seq(Const("MODE", m("RET")), Copy("VT", "C"), Copy("VE", "E"),
    Const("VK", CLO), Copy("ADDR", "K"), Const("I", 0), Const("CK", 0)) ++ keep("C", "E", "K", "MK")),
  Rule(Seq("MODE" -> m("EVAL"), "RT_K" -> 2), Seq(Const("CK", c("ARG")), Copy("CA", "RT_B"), Copy("CB", "E"),
    Copy("CC", "K"), Copy("K", "SELF", 1), Copy("C", "RT_A"), Const("MODE", m("EVAL")), Const("ADDR", 0),
    Const("I", 0)) ++ keep("E", "MK", "VT", "VE", "VK")),
  Rule(Seq("MODE" -> m("RET"), "RM_K" -> c("ARG")), Seq(Copy("C", "RM_A"), Copy("E", "RM_B"), Const("CK", c("FUN")),
    Copy("CA", "VT"), Copy("CB", "VE"), Copy("CV", "VK"), Copy("CC", "RM_C"), Copy("K", "SELF", 1),
    Const("MODE", m("EVAL")), Const("ADDR", 0), Const("I", 0)) ++ keep("MK", "VT", "VE", "VK")),
  // to FUN(a closure): apply as before
  Rule(Seq("MODE" -> m("RET"), "RM_K" -> c("FUN"), "RM_V" -> CLO), Seq(Const("MODE", m("APPLY")), Copy("C", "RM_A"),
    Copy("E", "RM_B"), Copy("K", "RM_C"), Const("ADDR", 0), Const("I", 0), Const("CK", 0)) ++ keep("MK", "VT", "VE", "VK")),
  Rule(Seq("MODE" -> m("APPLY")), Seq(Const("CK", c("ENV")), Copy("CA", "VT"), Copy("CB", "VE"), Copy("CV", "VK"),
    Copy("CC", "E"), Copy("E", "SELF", 1), Copy("C", "RT_A"), Const("MODE", m("EVAL")), Const("ADDR", 0),
    Const("I", 0)) ++ keep("K", "MK", "VT", "VE", "VK")),
  // HANDLE body with h: install the handler (a META cell), the body runs in a fresh segment (K = NIL)
  Rule(Seq("MODE" -> m("EVAL"), "RT_K" -> 3), Seq(Const("CK", c("META")), Copy("CA", "RT_B"), Copy("CB", "E"),
    Copy("CC", "K"), Copy("CD", "MK"), Copy("MK", "SELF", 1), Const("K", 0), Copy("C", "RT_A"),
    Const("MODE", m("EVAL")), Const("ADDR", 0), Const("I", 0)) ++ keep("E", "VT", "VE", "VK")),
  // PERFORM: read the nearest handler...
  Rule(Seq("MODE" -> m("EVAL"), "RT_K" -> 4), Seq(Const("MODE", m("PERF")), Copy("ADDR", "MK"), Const("CK", 0),
    Const("I", 0)) ++ keep("C", "E", "K", "MK", "VT", "VE", "VK")),
  // ...and run it outside itself, with k = (this segment, this handler) bound at index 0: CAPTURE is two pointers
  Rule(Seq("MODE" -> m("PERF")), Seq(Const("CK", c("ENV")), Const("CV", CONT), Copy("CA", "K"), Copy("CB", "MK"),
    Copy("CC", "RM_B"), Copy("E", "SELF", 1), Copy("C", "RM_A"), Copy("K", "RM_C"), Copy("MK", "RM_D"),
    Const("MODE", m("EVAL")), Const("ADDR", 0), Const("I", 0)) ++ keep("VT", "VE", "VK")),
  // to FUN(a continuation): RESUME — read its handler, reinstall it (deep), continue the segment with the value
  Rule(Seq("MODE" -> m("RET"), "RM_K" -> c("FUN"), "RM_V" -> CONT), Seq(Const("MODE", m("RESUME")), Copy("C", "RM_A"),
    Copy("K", "RM_C"), Copy("ADDR", "RM_B"), Const("I", 0), Const("CK", 0)) ++ keep("E", "MK", "VT", "VE", "VK")),
  Rule(Seq("MODE" -> m("RESUME")), Seq(Const("CK", c("META")), Copy("CA", "RM_A"), Copy("CB", "RM_B"), Copy("CC", "K"),
    Copy("CD", "MK"), Copy("MK", "SELF", 1), Copy("K", "C"), Copy("ADDR", "C"), Const("MODE", m("RET")),
    Const("I", 0)) ++ keep("C", "E", "VT", "VE", "VK")),
  // a segment returned: pop its handler (the return clause is the identity) — or halt when there is none
  Rule(Seq("MODE" -> m("RET"), "K" -> 0), Seq(Const("MODE", m("POP")), Copy("ADDR", "MK"), Const("CK", 0),
    Const("I", 0)) ++ keep("C", "E", "K", "MK", "VT", "VE", "VK")),
  Rule(Seq("MODE" -> m("POP"), "RM_K" -> c("META")), Seq(Copy("K", "RM_C"), Copy("MK", "RM_D"), Copy("ADDR", "RM_C"),
    Const("MODE", m("RET")), Const("CK", 0), Const("I", 0)) ++ keep("C", "E", "VT", "VE", "VK")),
  Rule(Seq("MODE" -> m("POP"), "MK" -> 0), Seq(Const("MODE", m("HALT")), Const("CK", 0), Const("ADDR", 0),
    Const("I", 0)) ++ keep("C", "E", "K", "MK", "VT", "VE", "VK")))

def runModel(t: T): (Int, Int, Int) =
  val prog = flatten(t); N = prog.size + maxSteps
  val ns = neurons(rules :+ Rule(Nil, Seq(Copy("SELF", "SELF", 1))))
  val seq = scala.collection.mutable.ArrayBuffer.empty[Vec]
  for (n, p) <- prog.zipWithIndex do
    seq += (if n.kind < 0 then vec("SELF" -> 0) else vec("SELF" -> p, "TKIND" -> n.kind, "TA" -> n.a, "TB" -> n.b, "TI" -> n.i))
  seq += vec("SELF" -> prog.size, "MODE" -> m("EVAL"), "C" -> 1, "E" -> 0, "K" -> 0, "MK" -> 0, "VT" -> 0, "VE" -> 0,
    "VK" -> 0, "I" -> 0, "ADDR" -> 0, "CK" -> 0, "CA" -> 0, "CB" -> 0, "CC" -> 0, "CD" -> 0, "CV" -> 0)
  var steps = 0
  while idx(seq.last, "MODE") != m("HALT") do
    val x = seq.last
    val rt = head(seq.toIndexedSeq, x("C"), Seq("TKIND" -> "RT_K", "TA" -> "RT_A", "TB" -> "RT_B", "TI" -> "RT_I"))
    val rm = head(seq.toIndexedSeq, x("ADDR"),
      Seq("CK" -> "RM_K", "CA" -> "RM_A", "CB" -> "RM_B", "CC" -> "RM_C", "CD" -> "RM_D", "CV" -> "RM_V"))
    seq += mlp(ns, x ++ rt ++ rm); steps += 1
    require(steps < maxSteps, "out of steps")
  val last = seq.last
  require(last.values.forall(_.forall(v => v == 0f || v == 1f)), "a value other than 0/1")
  (idx(last, "VT"), steps, ns.size)

// ---- the reference: a CEK machine with deep, multi-shot handlers, written the ordinary way
var refSteps = 0
def runRef(t: T): Int =
  val p = flatten(t)
  enum Val:
    case Clo(t: Int, env: List[Val]); case Cont(seg: List[Fr], h: Hnd)
  case class Hnd(body: Int, env: List[Val])
  enum Fr:
    case Arg(t: Int, env: List[Val]); case Fun(v: Val)
  import Val.*, Fr.*
  // state: eval(c, env) or ret(v); K the segment; MK the handlers with their outer segments
  var mode = "eval"; var c = 1; var env: List[Val] = Nil; var v: Val = null
  var k: List[Fr] = Nil; var mk: List[(Hnd, List[Fr])] = Nil; var done = false; var it = 0
  while !done do
    it += 1
    if mode == "eval" then p(c).kind match
      case 0 => v = env(p(c).i); mode = "ret"
      case 1 => v = Clo(c, env); mode = "ret"
      case 2 => k = Arg(p(c).b, env) :: k; c = p(c).a
      case 3 => mk = (Hnd(p(c).b, env), k) :: mk; k = Nil; c = p(c).a
      case _ => val (h, outer) = mk.head
        env = Cont(k, h) :: h.env; c = h.body; k = outer; mk = mk.tail
    else k match
      case Arg(t, e) :: rest => k = Fun(v) :: rest; c = t; env = e; mode = "eval"
      case Fun(Clo(f, fe)) :: rest => k = rest; c = p(f).a; env = v :: fe; mode = "eval"
      case Fun(Cont(seg, h)) :: rest => mk = (h, rest) :: mk; k = seg
      case Nil => mk match
        case (_, outer) :: rest => k = outer; mk = rest
        case Nil => done = true
  refSteps = it
  v match { case Clo(t, _) => t; case _ => -1 }

val tru = L(L(V(1))); val fls = L(L(V(0)))
val not = L(A(A(V(0), fls), tru))
val and = L(L(A(A(V(1), V(0)), fls)))
val or  = L(L(A(A(V(1), tru), V(0))))
def app(f: T, as: T*) = as.foldLeft(f)(A(_, _))
def name(t: T, vt: Int) = flatten(t)(vt) match
  case Node(1, b, _, _) => flatten(t)(b) match
    case Node(1, bb, _, _) => flatten(t)(bb) match { case Node(0, _, _, 1) => "true"; case Node(0, _, _, 0) => "false"; case _ => "λλ…" }
    case _ => "λ…"
  case _ => "?"
// handlers: k is the continuation, index 0 in the handler's body
val exists = app(or, app(V(0), tru), app(V(0), fls))     // resume TWICE, true and false: "is there a choice that …"
val forall = app(and, app(V(0), tru), app(V(0), fls))
val once   = app(V(0), tru)                               // resume once
val cases = Seq(
  "handle choose with k true"                   -> H(P, once),
  "∃x. x ∧ ¬x"                                  -> H(L(app(and, V(0), app(not, V(0)))) match { case f => app(f, P) }, exists),
  "∃x. ∃y. x ∧ ¬y   (nested choices)"           -> H(app(and, P, app(not, P)), exists),
  "∀x. ∀y. x ∨ ¬y"                              -> H(app(or, P, app(not, P)), forall),
  "∀x. ∀y. x ∨ y"                               -> H(app(or, P, P), forall),
  "∃x. ∃y. ∃z. x ∧ y ∧ ¬z"                      -> H(app(and, P, app(and, P, app(not, P))), exists))
for (label, t) <- cases do
  val rvt = runRef(t)
  val (vt, steps, ns) = runModel(t)
  val ok = if vt == rvt then "= reference" else s"DIFFERS (reference: ${name(t, rvt)})"
  check(vt == rvt, label)
  println(f"$label%-36s → ${name(t, vt)}%-5s  $steps%3d steps, $ns neurons  $ok")
if failures > 0 then sys.exit(1) else println("all checks passed")
