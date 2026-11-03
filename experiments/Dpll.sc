var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// DPLL IN A TRANSFORMER. Clauses are positions (POS / NEG literal masks); a pool of slots are branches, each holding
// an assignment (TRUE / FALSE bit-rows). Every turn, for every branch in parallel:
//   head C (conflict):  score λ·(#false − |C| − |C|·#true) — 0 on a falsified clause, ≤ −λ elsewhere, BOS −λ/2
//   head U (units):     score λ·(#false − (|C|−1) − |C|·#true) — 0 on a unit clause; values = the clause's masks:
//                       the mean over unit clauses, masked by "unassigned", is exactly the set of implied literals
//   both scores are q·k with q = (TRUE, FALSE, 1) and k linear in the clause's masks: ordinary W_Q, W_K
//   MLP: conflict → REJECT (the slot is freed); implications → assign them (unit propagation, all clauses at once);
//        nothing implied and all assigned → ACCEPT; else DECIDE the first unassigned variable: fork — the branch
//        takes true, a free slot (allocated by prefix counts, as in OrParallel) becomes its child with false
// At the end a COUNT head reads the number of accepted leaves = the number of models (#SAT). float32, exact.
import scala.util.Random
val lambda = 250f
type G = Array[Float]
def relu(x: Float) = math.max(0f, x)
def and(xs: Float*) = relu(xs.sum - (xs.size - 1))
def dot(a: G, b: G) = a.indices.map(i => a(i) * b(i)).sum
def softmaxRead(scores: Seq[Float], vs: Seq[G]): G = // hard attention: the weighted mean of values
  val mx = scores.max; val e = scores.map(s => math.exp((s - mx).toDouble).toFloat); val w = e.map(_ / e.sum)
  val out = Array.fill(vs.head.length)(0f)
  for (v, wi) <- vs.zip(w); i <- out.indices do out(i) += wi * v(i)
  out
def ladder(y: Float, kmax: Int): Int = // 1/(k+1) -> k, two ReLUs per threshold
  val st = 4f * (kmax + 2) * (kmax + 2)
  math.round((0 until kmax).map { k =>
    val t = ((1.0 / (k + 1) + 1.0 / (k + 2)) / 2).toFloat
    1f - (relu(st * (y - t)) - relu(st * (y - t) - 1))
  }.sum)
def present(y: Float, m: Int): Float = // step: > 0 (at least 1/(m+1)) → 1
  val s = 4f * (m + 2); val t = 1f / (2 * (m + 2)); relu(s * (y - t)) - relu(s * (y - t) - 1)

case class Clause(pos: G, neg: G) { val size = (pos.sum + neg.sum).toInt }
case class Slot(active: Float, free: Float, done: Float, tru: G, fls: G)

def solve(n: Int, cnf: Seq[Clause], P: Int): (Int, Int, Int, Int) = // (models, turns, peak slots, branch nodes)
  val m = cnf.size
  // keys of the two heads (one per clause) and their values; BOS: score −λ/2, value 0
  val kU = cnf.map(c =>
    (c.neg.zip(c.pos).map((ng, ps) => ng - c.size * ps)) ++ (c.pos
      .zip(c.neg)
      .map((ps, ng) => ps - c.size * ng)) :+ -(c.size - 1f)
  )
  val kC = cnf.map(c => kU(cnf.indexOf(c)).init :+ -c.size.toFloat)
  val vU = cnf.map(c => c.pos ++ c.neg); val vC = cnf.map(_ => Array(1f))
  var slots = Vector.tabulate(P)(i =>
    Slot(if i == 0 then 1 else 0, if i == 0 then 0 else 1, 0, Array.fill(n)(0f), Array.fill(n)(0f))
  )
  val before = (0 until P).map(i => Array.tabulate(P)(j => if j < i then 1f else 0f))
  val idx = (0 until P).map(i => Array.tabulate(P)(j => if j == i then 1f else 0f))
  var turns = 0; var peak = 1; var nodes = 1
  while slots.exists(_.active == 1f) do
    turns += 1; peak = peak max slots.count(_.active == 1f)
    val q = slots.map(s => (s.tru ++ s.fls :+ 1f).map(_ * s.active))
    val conflict =
      q.map(qs => present(softmaxRead((-lambda / 2) +: kC.map(k => lambda * dot(qs, k)), Array(0f) +: vC)(0), m))
    val units = q.map(qs => softmaxRead((-lambda / 2) +: kU.map(k => lambda * dot(qs, k)), Array.fill(2 * n)(0f) +: vU))
    // MLP: what each branch does this turn
    val step = slots.indices.map { i =>
      val s = slots(i); val a = s.active
      val unassigned = (0 until n).map(v => relu(1f - s.tru(v) - s.fls(v)))
      val impT = (0 until n).map(v => and(a, present(units(i)(v), m), unassigned(v)) * (1f - conflict(i)))
      val impF = (0 until n).map(v => and(a, present(units(i)(n + v), m), unassigned(v)) * (1f - conflict(i)))
      val implied = relu(math.min(1f, (impT ++ impF).sum))
      val allSet = and(a +: (0 until n).map(v => 1f - unassigned(v))*)
      // a variable implied both ways is a conflict too (two unit clauses disagree)
      val contra = relu(math.min(1f, (0 until n).map(v => and(impT(v), impF(v))).sum))
      val rej = relu(math.min(1f, and(a, conflict(i)) + contra))
      val acc = relu(allSet - conflict(i) - implied)
      val (impT2, impF2) = (impT.map(_ * (1f - contra)), impF.map(_ * (1f - contra)))
      // the first unassigned variable: ¬a_v ∧ a_0 ∧ … ∧ a_{v−1} (an AND with one negated input)
      val decide = relu(a - conflict(i) - implied - allSet)
      val first = (0 until n).map(v => and((decide +: unassigned(v) +: (0 until v).map(u => 1f - unassigned(u)))*))
      (impT2, impF2, rej, acc, decide, first)
    }
    // allocation of children: requesters and free slots ranked by prefix counts (selector width + ladder)
    val req = step.map(_._5)
    def ranks(keys: Seq[G]) = before.map(qb =>
      ladder(softmaxRead(lambda +: keys.map(k => lambda * dot(qb, k)), Array(1f) +: keys.map(_ => Array(0f)))(0), P)
    )
    val reqRank = ranks(slots.indices.map(i => idx(i).map(_ * req(i))))
    val freeRank = ranks(slots.indices.map(i => idx(i).map(_ * slots(i).free)))
    require(req.count(_ == 1f) <= slots.count(_.free == 1f), "out of slots")
    nodes += req.count(_ == 1f)
    val rk = slots.indices.map(i => Array.tabulate(P)(r => if r == reqRank(i) then req(i) else 0f))
    val fq = slots.indices.map(i => Array.tabulate(P)(r => if r == freeRank(i) then slots(i).free else 0f))
    val parent = slots.indices.map(i => Array(1f) ++ slots(i).tru ++ slots(i).fls ++ step(i)._6)
    val got =
      fq.map(f => softmaxRead((lambda / 2) +: rk.map(k => lambda * dot(f, k)), Array.fill(1 + 3 * n)(0f) +: parent))
    slots = slots.indices.map { i =>
      val s = slots(i); val (impT, impF, rej, acc, decide, first) = step(i); val g = got(i)
      val found = and(s.free, g(0))
      val tru = Array.tabulate(n)(v => s.tru(v) + impT(v) + first(v) + and(found, g(1 + v)) - and(rej, s.tru(v)))
      val fls = Array.tabulate(n)(v =>
        s.fls(v) + impF(v) + and(found, g(1 + n + v)) + and(found, g(1 + 2 * n + v)) - and(rej, s.fls(v))
      )
      Slot(s.active - rej - acc + found, s.free - found + rej, s.done + acc, tru, fls)
    }.toVector
  require(slots.forall(s => (s.tru ++ s.fls :+ s.active :+ s.free :+ s.done).forall(v => v == 0f || v == 1f)))
  val models = ladder(
    softmaxRead(lambda +: slots.indices.map(i => lambda * slots(i).done), Array(1f) +: slots.map(_ => Array(0f)))(0),
    P
  )
  (models, turns, peak, nodes)

// references: brute force (#SAT), and plain enumeration's cost (the OrParallel way): 2^n leaves
def models(n: Int, cnf: Seq[Seq[(Int, Int)]]) =
  (0 until (1 << n)).count(a => cnf.forall(_.exists((v, p) => ((a >> v) & 1) == p)))
def clauses(n: Int, cnf: Seq[Seq[(Int, Int)]]) = cnf.map { c =>
  Clause(
    Array.tabulate(n)(v => if c.contains(v -> 1) then 1f else 0f),
    Array.tabulate(n)(v => if c.contains(v -> 0) then 1f else 0f)
  )
}
def random3(n: Int, m: Int, r: Random) = Seq.fill(m) {
  val vs = r.shuffle((0 until n).toList).take(3); vs.map(v => v -> r.nextInt(2))
}

println(f"${"instance"}%-30s  models (ref) | turns | peak slots | branch nodes | brute force: leaves")
val cases = Seq(
  (
    "pigeons: 3 in 2 holes (UNSAT)",
    6, { // p(i,h) = var 2i+h; each pigeon somewhere; no two in one hole
      val pigeons = (0 until 3).map(i => Seq((2 * i) -> 1, (2 * i + 1) -> 1))
      val holes = for h <- 0 until 2; i <- 0 until 3; j <- i + 1 until 3 yield Seq((2 * i + h) -> 0, (2 * j + h) -> 0)
      pigeons ++ holes
    }
  )
) ++
  (1 to 4).map(s => (s"random 3-SAT n=10 m=42 #$s", 10, random3(10, 42, new Random(s)))) ++
  (1 to 3).map(s => (s"random 3-SAT n=12 m=50 #$s", 12, random3(12, 50, new Random(100 + s)))) ++
  (1 to 2).map(s => (s"random 3-SAT n=16 m=68 #$s", 16, random3(16, 68, new Random(200 + s)))) ++
  Seq(("random 3-SAT n=20 m=85", 20, random3(20, 85, new Random(301))))
for (label, n, cnf) <- cases do
  val ref = models(n, cnf)
  val (sol, turns, peak, nodes) = solve(n, clauses(n, cnf), P = 160)
  check(sol == ref, label)
  println(f"$label%-30s  $sol%4d ($ref%4d) | $turns%5d | $peak%10d | $nodes%12d | ${1 << n}%6d")
if failures > 0 then sys.exit(1) else println("all checks passed")
