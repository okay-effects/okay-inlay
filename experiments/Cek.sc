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
  case V(i: Int); case L(b: T); case A(f: T, a: T)
import T.*

// ---- the program as positions: 0 is NIL (a sentinel), nodes from 1
val maxSteps = 400
case class Node(kind: Int, a: Int, b: Int, i: Int) // kind 0 VAR, 1 LAM, 2 APP; a = body/fun, b = arg, i = index
def flatten(t: T): Vector[Node] =
  val nodes = scala.collection.mutable.ArrayBuffer(Node(-1, 0, 0, 0))
  def go(t: T): Int =
    val me = nodes.size; nodes += null
    nodes(me) = t match
      case V(i)    => Node(0, 0, 0, i)
      case L(b)    => Node(1, go(b), 0, 0)
      case A(f, a) => val fi = go(f); Node(2, fi, go(a), 0)
    me
  go(t); nodes.toVector

// ---- groups of the residual stream (one-hot each)
val D = 8 // de Bruijn indices 0..7
val modes = Vector("EVAL", "LOOK", "RET", "APPLY", "HALT"); val cells = Vector("NONE", "ENV", "ARG", "FUN")
type Vec = Map[String, Array[Float]]
var N = 0 // positions: set per program
def hot(n: Int, i: Int) = { val a = Array.fill(n)(0f); a(i) = 1f; a }
def size(g: String) = g match
  case "MODE"              => modes.size; case "CK" | "RM_K" => cells.size; case "TKIND" | "RT_K" => 3
  case "I" | "TI" | "RT_I" => D; case _                      => N
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
    to -> out
  }.toMap

// ---- the MLP from rules: guards (group = value) and assignments (constant, or a copy of a group, maybe shifted)
enum Set:
  case Const(g: String, v: Int); case Copy(g: String, src: String, shift: Int = 0)
case class Rule(guards: Seq[(String, Int)], sets: Seq[Set])
case class Neuron(in: Seq[(String, Int)], out: (String, Int))
def neurons(rules: Seq[Rule]): Seq[Neuron] = rules.flatMap { r =>
  r.sets.flatMap {
    case Set.Const(g, v) => Seq(Neuron(r.guards, g -> v))
    case Set.Copy(g, src, s) =>
      (0 until size(src))
        .filter(p => p + s >= 0 && p + s < size(g))
        .map(p => Neuron(r.guards :+ (src -> p), g -> (p + s)))
  }
}
def mlp(ns: Seq[Neuron], z: Vec): Vec =
  val out = scala.collection.mutable.Map.empty[String, Array[Float]]
  for n <- ns do
    val h = math.max(0f, n.in.map((g, i) => z(g)(i)).sum - (n.in.size - 1)) // AND: ReLU(x1+…+xn − (n−1))
    if h != 0f then out.getOrElseUpdate(n.out._1, Array.fill(size(n.out._1))(0f))(n.out._2) += h
  out.toMap

import Set.*
def keep(gs: String*) = gs.map(g => Copy(g, g))
def m(s: String) = modes.indexOf(s); def c(s: String) = cells.indexOf(s)
val rules: Seq[Rule] = Seq(
  // EVAL a variable: look it up, I steps down the environment
  Rule(
    Seq("MODE" -> m("EVAL"), "RT_K" -> 0),
    Seq(Const("MODE", m("LOOK")), Copy("I", "RT_I"), Copy("ADDR", "E"), Const("CK", 0)) ++ keep(
      "C",
      "E",
      "K",
      "VT",
      "VE"
    )
  ),
  Rule(
    Seq("MODE" -> m("LOOK"), "I" -> 0),
    Seq(
      Const("MODE", m("RET")),
      Copy("VT", "RM_A"),
      Copy("VE", "RM_B"),
      Copy("ADDR", "K"),
      Const("I", 0),
      Const("CK", 0)
    ) ++ keep("C", "E", "K")
  )
) ++
  (1 until D).map(i =>
    Rule(
      Seq("MODE" -> m("LOOK"), "I" -> i),
      Seq(Const("MODE", m("LOOK")), Const("I", i - 1), Copy("ADDR", "RM_C"), Const("CK", 0)) ++ keep(
        "C",
        "E",
        "K",
        "VT",
        "VE"
      )
    )
  ) ++ Seq(
    // EVAL a λ: it is a value, a closure (C, E)
    Rule(
      Seq("MODE" -> m("EVAL"), "RT_K" -> 1),
      Seq(
        Const("MODE", m("RET")),
        Copy("VT", "C"),
        Copy("VE", "E"),
        Copy("ADDR", "K"),
        Const("I", 0),
        Const("CK", 0)
      ) ++ keep("C", "E", "K")
    ),
    // EVAL an application: push the frame ARG(argument, E), evaluate the function
    Rule(
      Seq("MODE" -> m("EVAL"), "RT_K" -> 2),
      Seq(
        Const("CK", c("ARG")),
        Copy("CA", "RT_B"),
        Copy("CB", "E"),
        Copy("CC", "K"),
        Copy("K", "SELF", 1),
        Copy("C", "RT_A"),
        Const("MODE", m("EVAL")),
        Const("ADDR", 0),
        Const("I", 0)
      ) ++ keep("E", "VT", "VE")
    ),
    // RETURN a value to the frame ARG: evaluate the argument, push FUN(the value)
    Rule(
      Seq("MODE" -> m("RET"), "RM_K" -> c("ARG")),
      Seq(
        Copy("C", "RM_A"),
        Copy("E", "RM_B"),
        Const("CK", c("FUN")),
        Copy("CA", "VT"),
        Copy("CB", "VE"),
        Copy("CC", "RM_C"),
        Copy("K", "SELF", 1),
        Const("MODE", m("EVAL")),
        Const("ADDR", 0),
        Const("I", 0)
      ) ++ keep("VT", "VE")
    ),
    // RETURN to FUN(closure): pop it, go to the λ (its body is read next step)
    Rule(
      Seq("MODE" -> m("RET"), "RM_K" -> c("FUN")),
      Seq(
        Const("MODE", m("APPLY")),
        Copy("C", "RM_A"),
        Copy("E", "RM_B"),
        Copy("K", "RM_C"),
        Const("ADDR", 0),
        Const("I", 0),
        Const("CK", 0)
      ) ++ keep("VT", "VE")
    ),
    // APPLY: extend the closure's environment with the value (a new cell), evaluate the body
    Rule(
      Seq("MODE" -> m("APPLY")),
      Seq(
        Const("CK", c("ENV")),
        Copy("CA", "VT"),
        Copy("CB", "VE"),
        Copy("CC", "E"),
        Copy("E", "SELF", 1),
        Copy("C", "RT_A"),
        Const("MODE", m("EVAL")),
        Const("ADDR", 0),
        Const("I", 0)
      ) ++
        keep("K", "VT", "VE")
    ),
    // RETURN with no frame left: halt — the value is the answer
    Rule(
      Seq("MODE" -> m("RET"), "K" -> 0),
      Seq(Const("MODE", m("HALT")), Const("CK", 0), Const("ADDR", 0), Const("I", 0)) ++ keep("C", "E", "K", "VT", "VE")
    )
  )

def runModel(t: T): (Int, Int, Int) = // (VT of the answer, steps, neurons)
  val prog = flatten(t); N = prog.size + maxSteps
  val ns = neurons(rules :+ Rule(Nil, Seq(Copy("SELF", "SELF", 1)))) // every record's address: the last one's + 1
  val seq = scala.collection.mutable.ArrayBuffer.empty[Vec]
  for (n, p) <- prog.zipWithIndex do
    seq += (if n.kind < 0 then vec("SELF" -> 0)
            else vec("SELF" -> p, "TKIND" -> n.kind, "TA" -> n.a, "TB" -> n.b, "TI" -> n.i))
  seq += vec(
    "SELF" -> prog.size,
    "MODE" -> m("EVAL"),
    "C" -> 1,
    "E" -> 0,
    "K" -> 0,
    "VT" -> 0,
    "VE" -> 0,
    "I" -> 0,
    "ADDR" -> 0,
    "CK" -> 0,
    "CA" -> 0,
    "CB" -> 0,
    "CC" -> 0
  )
  var steps = 0
  while idx(seq.last, "MODE") != m("HALT") do
    val x = seq.last
    val rt = head(seq.toIndexedSeq, x("C"), Seq("TKIND" -> "RT_K", "TA" -> "RT_A", "TB" -> "RT_B", "TI" -> "RT_I"))
    val rm = head(seq.toIndexedSeq, x("ADDR"), Seq("CK" -> "RM_K", "CA" -> "RM_A", "CB" -> "RM_B", "CC" -> "RM_C"))
    seq += mlp(ns, x ++ rt ++ rm); steps += 1
    require(steps < maxSteps, "out of steps")
  val last = seq.last
  require(last.values.forall(_.forall(v => v == 0f || v == 1f)), "a value other than 0/1")
  (idx(last, "VT"), steps, ns.size)

// ---- the reference: an ordinary CEK interpreter over the same nodes
def runRef(t: T): (Int, Int) =
  val p = flatten(t)
  case class Clo(t: Int, env: List[Clo])
  enum K:
    case Arg(t: Int, env: List[Clo], k: K); case Fun(c: Clo, k: K); case Done
  def eval(c: Int, env: List[Clo], k: K, n: Int): (Clo, Int) = p(c).kind match
    case 0 => ret(env(p(c).i), k, n + 2 + p(c).i) // EVAL + LOOK steps, as the model counts
    case 1 => ret(Clo(c, env), k, n + 1)
    case _ => eval(p(c).a, env, K.Arg(p(c).b, env, k), n + 1)
  def ret(v: Clo, k: K, n: Int): (Clo, Int) = k match
    case K.Arg(t, e, k2) => eval(t, e, K.Fun(v, k2), n + 1)
    case K.Fun(f, k2)    => eval(p(f.t).a, v :: f.env, k2, n + 2) // RET + APPLY
    case K.Done          => (v, n + 1)
  eval(1, Nil, K.Done, 0) match { case (v, n) => (v.t, n) }

// Church booleans and friends
val tru = L(L(V(1))); val fls = L(L(V(0)))
val not = L(A(A(V(0), fls), tru))
val and = L(L(A(A(V(1), V(0)), fls)))
val or = L(L(A(A(V(1), tru), V(0))))
def app(f: T, as: T*) = as.foldLeft(f)(A(_, _))
def name(t: T, vt: Int) = flatten(t)(vt) match
  case Node(1, b, _, _) =>
    flatten(t)(b) match
      case Node(1, bb, _, _) =>
        flatten(t)(bb) match { case Node(0, _, _, 1) => "true"; case Node(0, _, _, 0) => "false"; case _ => "λλ…" }
      case _ => "λ…"
  case _ => "?"
val cases = Seq(
  "not true" -> app(not, tru),
  "and true false" -> app(and, tru, fls),
  "or false true" -> app(or, fls, tru),
  "and (not false) (or false false)" -> app(and, app(not, fls), app(or, fls, fls)),
  "(λa.λb.λc. a) true false false" -> app(L(L(L(V(2)))), tru, fls, fls),
  "not (not (not (not true)))" -> app(not, app(not, app(not, app(not, tru))))
)
for (label, t) <- cases do
  val (vt, steps, ns) = runModel(t); val (rvt, rsteps) = runRef(t)
  val ok = if vt == rvt && steps == rsteps then "= interpreter" else s"DIFFERS (ref ${name(t, rvt)} in $rsteps)"
  check(vt == rvt && steps == rsteps, label)
  println(f"$label%-36s → ${name(t, vt)}%-5s  $steps%3d steps, $ns neurons, ${flatten(t).size - 1}%2d nodes  $ok")
if failures > 0 then sys.exit(1) else println("all checks passed")
