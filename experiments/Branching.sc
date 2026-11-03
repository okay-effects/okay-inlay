var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// BRANCHING BY INTUITION. The DPLL transformer of Dpll.sc with one change: a decision takes the first unassigned
// variable in an ORDER — the model's ranking ("which variable matters"), sorted (in a transformer: sort by selector
// width, as in Tracr). Propagation and checks stay exact: a bad ranking costs branches, never correctness.
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

var accepted: Option[Array[Int]] = None // an accepted branch's assignment, to check it independently
def solve(
    n: Int,
    cnf: Seq[Clause],
    P: Int,
    order: Seq[Int],
    hint: Seq[Int] = Nil,
    dive: Boolean = false
): (Int, Int, Int, Int) = // (models, turns, peak slots, branch nodes)
  val m = cnf.size
  val h = if hint.isEmpty then Array.fill(n)(1f) else hint.map(_.toFloat).toArray // the value to try first
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
      val first = Array.fill(n)(0f) // the first unassigned in the ORDER (the model's ranking): an AND chain over it
      for r <- 0 until n do
        first(order(r)) = and((decide +: unassigned(order(r)) +: (0 until r).map(u => 1f - unassigned(order(u))))*)
      (impT2, impF2, rej, acc, decide, first)
    }
    // allocation of children: requesters and free slots ranked by prefix counts (selector width + ladder)
    val req = if dive then step.map(_ => 0f) else step.map(_._5) // a dive never forks: it follows the hints
    // the same heads as in Dpll.sc, computed sparsely: a one-hot key makes q·k one lookup (O(P²) instead of O(P³))
    def ranks(flag: Seq[Float]) = slots.indices.map { i =>
      val scores = lambda +: slots.indices.map(j => if j < i then lambda * flag(j) else 0f)
      ladder(softmaxRead(scores, Array(1f) +: slots.map(_ => Array(0f)))(0), P)
    }
    val reqRank = ranks(req)
    val freeRank = ranks(slots.map(_.free))
    require(req.count(_ == 1f) <= slots.count(_.free == 1f), "out of slots")
    nodes += req.count(_ == 1f)
    val parent = slots.indices.map(i => Array(1f) ++ slots(i).tru ++ slots(i).fls ++ step(i)._6)
    val got = slots.indices.map { i => // q = one-hot free rank, k = one-hot request rank
      val scores = (lambda / 2) +: slots.indices.map(j =>
        if reqRank(j) == freeRank(i) then lambda * req(j) * slots(i).free else 0f
      )
      softmaxRead(scores, Array.fill(1 + 3 * n)(0f) +: parent)
    }
    slots = slots.indices.map { i =>
      val s = slots(i); val (impT, impF, rej, acc, decide, first) = step(i); val g = got(i)
      val found = and(s.free, g(0))
      // the branch takes the hinted value, its child the other one
      val tru = Array.tabulate(n)(v =>
        s.tru(v) + impT(v) + first(v) * h(v) + and(found, g(1 + v)) +
          and(found, g(1 + 2 * n + v)) * (1f - h(v)) - and(rej, s.tru(v))
      )
      val fls = Array.tabulate(n)(v =>
        s.fls(v) + impF(v) + first(v) * (1f - h(v)) + and(found, g(1 + n + v)) +
          and(found, g(1 + 2 * n + v)) * h(v) - and(rej, s.fls(v))
      )
      Slot(s.active - rej - acc + found, s.free - found + rej, s.done + acc, tru, fls)
    }.toVector
  require(slots.forall(s => (s.tru ++ s.fls :+ s.active :+ s.free :+ s.done).forall(v => v == 0f || v == 1f)))
  accepted = slots.find(_.done == 1f).map(s => s.tru.map(_.toInt))
  val models = ladder(
    softmaxRead(lambda +: slots.indices.map(i => lambda * slots(i).done), Array(1f) +: slots.map(_ => Array(0f)))(0),
    P
  )
  (models, turns, peak, nodes)

// ---- instances with hidden structure: k KEY variables decide everything else through chains of definitions
// (y = a XOR b, y = a AND b — each as clauses), plus random clauses over the keys; indices shuffled, so that the
// plain "first unassigned" order knows nothing about the structure.
def models(n: Int, cnf: Seq[Seq[(Int, Int)]]) =
  (0 until (1 << n)).count(a => cnf.forall(_.exists((v, p) => ((a >> v) & 1) == p)))
def clauses(n: Int, cnf: Seq[Seq[(Int, Int)]]) = cnf.map { c =>
  Clause(
    Array.tabulate(n)(v => if c.contains(v -> 1) then 1f else 0f),
    Array.tabulate(n)(v => if c.contains(v -> 0) then 1f else 0f)
  )
}
def structured(k: Int, derived: Int, extra: Int, r: Random): (Int, Seq[Seq[(Int, Int)]], Set[Int]) =
  val n = k + derived
  val perm = r.shuffle((0 until n).toList).toVector // logical variable i lives at index perm(i)
  val cl = scala.collection.mutable.ArrayBuffer.empty[Seq[(Int, Int)]]
  for j <- k until n do
    val a = r.nextInt(j); var b = r.nextInt(j); while b == a do b = r.nextInt(j)
    val (y, x1, x2) = (perm(j), perm(a), perm(b))
    if true then // y = x1 XOR x2 (two of three needed)
      cl ++= Seq(
        Seq(y -> 0, x1 -> 1, x2 -> 1),
        Seq(y -> 0, x1 -> 0, x2 -> 0),
        Seq(y -> 1, x1 -> 0, x2 -> 1),
        Seq(y -> 1, x1 -> 1, x2 -> 0)
      )
    else // y = x1 AND x2
      cl ++= Seq(Seq(y -> 0, x1 -> 1), Seq(y -> 0, x2 -> 1), Seq(y -> 1, x1 -> 0, x2 -> 0))
  for _ <- 0 until extra do // constraints on the deepest derived variables only
    val deep = (n - derived / 2 until n).toList
    val vs = r.shuffle(deep).take(3).map(perm); cl += vs.map(v => v -> r.nextInt(2))
  (n, cl.toSeq, (0 until k).map(perm).toSet)
// the model's intuition: a score per variable = 1 for a key, 0 otherwise, plus noise of strength ε; the order is
// the variables sorted by score (highest first)
def intuition(n: Int, keys: Set[Int], eps: Double, r: Random): Seq[Int] =
  (0 until n).sortBy(v => -((if keys(v) then 1.0 else 0.0) + eps * r.nextGaussian()))

val rnd = new Random(42)
println(f"${"instance"}%-26s ${"models"}%7s | branch nodes by the order of decisions")
println(f"${""}%-26s ${""}%7s | ${"index"}%6s ${"keys last"}%9s ${"ε=1.0"}%6s ${"ε=0.5"}%6s ${"ε=0.25"}%6s ${"ε=0"}%5s")
for (k, derived, extra, seed) <- Seq((4, 12, 6, 1), (4, 12, 6, 2), (5, 13, 8, 3), (5, 13, 8, 4), (6, 12, 10, 5)) do
  val (n, cnf, keys) = structured(k, derived, extra, new Random(seed))
  val ref = models(n, cnf); val cls = clauses(n, cnf)
  val orders = Seq(
    "index" -> (0 until n),
    "keys last" -> ((0 until n).filterNot(keys) ++ (0 until n).filter(keys)),
    "ε=1.0" -> intuition(n, keys, 1.0, rnd),
    "ε=0.5" -> intuition(n, keys, 0.5, rnd),
    "ε=0.25" -> intuition(n, keys, 0.25, rnd),
    "ε=0" -> intuition(n, keys, 0.0, rnd)
  )
  val nodes = orders.map { (name, ord) =>
    val (sol, _, _, nd) = solve(n, cls, P = 512, order = ord)
    check(sol == ref, s"n=$n seed=$seed order $name: $sol models, reference $ref"); nd
  }
  println(
    f"${s"n=$n ($k keys), ${cnf.size} clauses"}%-26s $ref%7d | ${nodes(0)}%6d ${nodes(1)}%9d ${nodes(2)}%6d " +
      f"${nodes(3)}%6d ${nodes(4)}%6d ${nodes(5)}%5d"
  )

// ---- FINDING one solution: a DIVE that follows the model's guesses of VALUES, no forking; exact propagation fixes
// what it can, a conflict ends the dive (then the full search runs — the brute force as the backup). Instances:
// random 3-SAT with a planted solution; the guesses are that solution with each bit flipped with probability p.
def planted(n: Int, m: Int, r: Random): (Seq[Seq[(Int, Int)]], Array[Int]) =
  val sigma = Array.fill(n)(r.nextInt(2))
  val cl = Iterator
    .continually {
      val vs = r.shuffle((0 until n).toList).take(3); vs.map(v => v -> r.nextInt(2))
    }
    .filter(_.exists((v, p) => sigma(v) == p))
    .take(m)
    .toSeq
  (cl, sigma)
println()
println(
  f"${"planted 3-SAT"}%-22s ${"models"}%6s ${"full search"}%11s | " +
    "the dive: found in turns (− = conflict) by guess noise p"
)
println(f"${""}%-22s ${""}%6s ${"nodes"}%11s | ${"p=0"}%5s ${"p=0.1"}%6s ${"p=0.2"}%6s ${"p=0.3"}%6s ${"p=0.5"}%6s")
val dives = scala.collection.mutable.Map.empty[Double, (Int, Int)].withDefaultValue((0, 0))
for seed <- 1 to 6 do
  val n = 20; val (cnf, sigma) = planted(n, 85, new Random(500 + seed)); val cls = clauses(n, cnf)
  val ref = models(n, cnf)
  val (sol, _, _, nodes) = solve(n, cls, P = 512, order = 0 until n)
  check(sol == ref, s"planted #$seed: full search $sol models, reference $ref")
  val r = new Random(seed)
  val cells = Seq(0.0, 0.1, 0.2, 0.3, 0.5).map { p =>
    val guess = sigma.map(b => if r.nextDouble() < p then 1 - b else b).toSeq
    val (found, turns, _, _) = solve(n, cls, P = 1, order = 0 until n, hint = guess, dive = true)
    if found == 1 then
      val a = accepted.get
      check(cnf.forall(_.exists((v, pol) => a(v) == pol)), s"planted #$seed p=$p: the dive's answer satisfies all")
    val (ok, all) = dives(p); dives(p) = (ok + found, all + 1)
    if found == 1 then s"$turns" else "−"
  }
  println(
    f"${s"#$seed: n=20 m=85"}%-22s $ref%6d $nodes%11d | " +
      f"${cells(0)}%5s ${cells(1)}%6s ${cells(2)}%6s ${cells(3)}%6s ${cells(4)}%6s"
  )
println(
  "found by the dive: " + Seq(0.0, 0.1, 0.2, 0.3, 0.5).map(p => s"p=$p ${dives(p)._1}/${dives(p)._2}").mkString(", ")
)
if failures > 0 then sys.exit(1) else println("all checks passed")
