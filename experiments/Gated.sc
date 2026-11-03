var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// BRANCH WHERE UNSURE. The DPLL transformer with a model's guess AND confidence per variable: a confident
// decision takes the guess without forking (a dive step), an unsure one forks (the guess first); propagation and
// checks stay exact. The search's width follows the model's uncertainty. If it ends without a solution, plain DPLL
// runs as the backup. Work is measured to the first solution: branch nodes and slots.
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
    dive: Boolean = false,
    unc: Seq[Float] = Nil,
    stopAtFirst: Boolean = false
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
  while slots.exists(_.active == 1f) && !(stopAtFirst && slots.exists(_.done == 1f)) do
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
    // fork only on a variable the model is unsure of (unc = 1); a confident decision is a dive step
    val req =
      if dive then step.map(_ => 0f)
      else if unc.isEmpty then step.map(_._5)
      else step.map(st => (0 until n).map(v => and(st._6(v), unc(v))).sum)
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

// ---- instances: random 3-SAT with a planted solution
def clauses(n: Int, cnf: Seq[Seq[(Int, Int)]]) = cnf.map { c =>
  Clause(
    Array.tabulate(n)(v => if c.contains(v -> 1) then 1f else 0f),
    Array.tabulate(n)(v => if c.contains(v -> 0) then 1f else 0f)
  )
}
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
// a CALIBRATED model: on a share q of the variables it "knows" (confidence 0.95), on the rest it is unsure (0.55);
// a guess is right with the probability of its confidence
case class Guess(value: Array[Int], conf: Array[Double])
def model(sigma: Array[Int], q: Double, r: Random): Guess =
  val conf = sigma.map(_ => if r.nextDouble() < q then 0.95 else 0.55)
  Guess(sigma.indices.map(v => if r.nextDouble() < conf(v) then sigma(v) else 1 - sigma(v)).toArray, conf)

def satisfies(cnf: Seq[Seq[(Int, Int)]], a: Array[Int]) = cnf.forall(_.exists((v, p) => a(v) == p))
case class Outcome(found: Boolean, nodes: Int, peak: Int, turns: Int)
def run(n: Int, cnf: Seq[Seq[(Int, Int)]], cls: Seq[Clause], g: Guess, strategy: String): Outcome =
  val byConf = (0 until n).sortBy(v => -g.conf(v)) // confident decisions first
  val (found, turns, peak, nodes) = strategy match
    case "dpll" => solve(n, cls, 512, 0 until n, hint = g.value.toSeq, stopAtFirst = true)
    case "dive" => solve(n, cls, 1, byConf, hint = g.value.toSeq, dive = true)
    case _ =>
      solve(
        n,
        cls,
        512,
        byConf,
        hint = g.value.toSeq,
        unc = (0 until n).map(v => if g.conf(v) < 0.8 then 1f else 0f),
        stopAtFirst = true
      )
  if found > 0 then check(accepted.exists(a => satisfies(cnf, a)), s"$strategy: the answer satisfies every clause")
  Outcome(found > 0, nodes, peak, turns)

val n = 24; val m = 102; val instances = 8
println(s"planted 3-SAT, n = $n, m = $m, $instances instances; a calibrated model knows a share q of the variables")
println(f"${"q"}%5s | ${"plain DPLL"}%18s | ${"lone dive"}%10s | ${"branch where unsure"}%28s | ${"+ backup"}%10s")
println(
  f"${""}%5s | ${"nodes"}%8s ${"slots"}%9s | ${"found"}%10s | " +
    f"${"found"}%8s ${"nodes"}%8s ${"slots"}%10s | ${"nodes"}%10s"
)
for q <- Seq(1.0, 0.9, 0.75, 0.5, 0.25) do
  val rows = (1 to instances).map { s =>
    val (cnf, sigma) = planted(n, m, new Random(700 + s)); val cls = clauses(n, cnf)
    val g = model(sigma, q, new Random(s * 31 + (q * 100).toInt))
    val d = run(n, cnf, cls, g, "dpll"); val v = run(n, cnf, cls, g, "dive"); val u = run(n, cnf, cls, g, "gated")
    check(d.found, s"plain DPLL finds the planted instance #$s")
    (d, v, u, if u.found then u.nodes else u.nodes + d.nodes)
  }
  def mean(xs: Seq[Int]) = xs.sum.toDouble / xs.size
  println(
    f"$q%5.2f | ${mean(rows.map(_._1.nodes))}%8.1f ${mean(rows.map(_._1.peak))}%9.1f | " +
      f"${s"${rows.count(_._2.found)}/$instances"}%10s | ${s"${rows.count(_._3.found)}/$instances"}%8s " +
      f"${mean(rows.map(_._3.nodes))}%8.1f ${mean(rows.map(_._3.peak))}%10.1f | ${mean(rows.map(_._4))}%10.1f"
  )
if failures > 0 then sys.exit(1) else println("all checks passed")
