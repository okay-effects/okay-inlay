var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// OR-PARALLEL SEARCH IN A TRANSFORMER. A pool of slots (positions) are processors; every turn of the loop, every
// active slot executes one instruction of the same program (in the context), in parallel. CHOOSE r forks: the slot
// itself takes r = true, a FREE slot becomes its child with r = false — allocated without conflicts by prefix counts:
// a requester of rank k (requesters before it, by position) takes the free slot of rank k (free slots before it).
// REJECT frees the slot (recycled), ACCEPT keeps a result; at the end a COUNT head reads the number of solutions.
// All traffic between slots is attention (hard, λ large); all local logic is AND-gates and ladders. float32, exact.
// The program: SAT over n variables — CHOOSE each, then each clause checked (jump if a literal holds, else REJECT).
import scala.util.Random
val lambda = 250f
val P = 24; val NPC = 40; val NV = 3 // slots, program size, variables
val ops = Vector("CHOOSE", "JT", "REJECT", "ACCEPT")
case class Ins(op: String, r: Int = 0, pol: Int = 0, k: Int = 0) // JT r pol k: jump to k if reg r == pol
type G = Array[Float]
def hot(n: Int, i: Int): G = { val a = Array.fill(n)(0f); if i >= 0 then a(i) = 1f; a }
def relu(x: Float) = math.max(0f, x)
def and(xs: Float*) = relu(xs.sum - (xs.size - 1)) // one AND-neuron

// hard attention: each query position reads the values of the key position with the highest score (+ BOS: zeros)
def attend(qs: Seq[G], ks: Seq[G], vs: Seq[G], bosScore: Float): Seq[G] = qs.map { q =>
  val scores = bosScore +: ks.map(k => lambda * q.indices.map(i => q(i) * k(i)).sum)
  val mx = scores.max; val e = scores.map(s => math.exp((s - mx).toDouble).toFloat); val w = e.map(_ / e.sum)
  val out = Array.fill(vs.head.length)(0f)
  for (v, wi) <- vs.zip(w.tail); i <- out.indices do out(i) += wi * v(i)
  out
}
// a selector-width count: for each slot, how many keys are selected (BOS scores λ like a match, carries 1)
def counts(qs: Seq[G], ks: Seq[G]): Seq[Int] =
  qs.map { q =>
    val scores = lambda +: ks.map(k => lambda * q.indices.map(i => q(i) * k(i)).sum)
    val mx = scores.max; val e = scores.map(s => math.exp((s - mx).toDouble).toFloat)
    ladder(e(0) / e.sum)
  }
val ts = (0 until P).map(k => ((1.0 / (k + 1) + 1.0 / (k + 2)) / 2).toFloat); val steep = 4f * (P + 2) * (P + 2)
def ladder(y: Float): Int = // the MLP: 1/(k+1) -> k, two ReLUs a threshold
  math.round(ts.map(t => 1f - (relu(steep * (y - t)) - relu(steep * (y - t) - 1))).sum)

case class Slot(active: Float, free: Float, done: Float, pc: G, regs: Vector[G]) // regs: [false, true], unset [0,0]
def run(prog: Vector[Ins]): (Int, Int, Int) = // (solutions, parallel turns, peak active)
  val progVecs = prog.map(i => (hot(ops.size, ops.indexOf(i.op)), hot(NV, i.r), hot(2, i.pol), hot(NPC, i.k)))
  var slots = Vector.tabulate(P)(i =>
    if i == 0 then Slot(1, 0, 0, hot(NPC, 0), Vector.fill(NV)(hot(2, -1)))
    else Slot(0, 1, 0, hot(NPC, -1), Vector.fill(NV)(hot(2, -1)))
  )
  var turns = 0; var peak = 1
  val idx = (0 until P).map(i => hot(P, i));
  val before = (0 until P).map(i => Array.tabulate(P)(j => if j < i then 1f else 0f))
  while slots.exists(_.active == 1f) do
    turns += 1; peak = peak max slots.count(_.active == 1f)
    // head 1: fetch the instruction at PC (active slots; others read BOS = nothing)
    val keysP = prog.indices.map(p => hot(NPC, p))
    def fetch(f: ((G, G, G, G)) => G) = attend(slots.map(s => s.pc.map(_ * s.active)), keysP, progVecs.map(f), 0f)
    val op = fetch(_._1); val rr = fetch(_._2); val pol = fetch(_._3); val tgt = fetch(_._4)
    // MLP: who requests a child, who is free — as keys masked by the slot's own index
    val req = slots.indices.map(i => and(slots(i).active, op(i)(0)))
    val reqIdx = slots.indices.map(i => idx(i).map(x => and(x, req(i))))
    val freeIdx = slots.indices.map(i => idx(i).map(x => and(x, slots(i).free)))
    // heads 2, 3 + ladders: the rank of a requester among requesters, of a free slot among free slots
    val reqRank = counts(before, reqIdx); val freeRank = counts(before, freeIdx)
    val nReq = req.count(_ == 1f); val nFree = slots.count(_.free == 1f)
    require(nReq <= nFree, s"out of slots: $nReq requests, $nFree free")
    // head 4: a free slot of rank k reads the requester of rank k (its state, the register chosen, FOUND = 1)
    val rk = slots.indices.map(i => hot(P, reqRank(i)).map(x => and(x, req(i))))
    val fq = slots.indices.map(i => hot(P, freeRank(i)).map(x => and(x, slots(i).free)))
    val parentVals = slots.indices.map(i => Array(1f) ++ slots(i).pc ++ slots(i).regs.flatten ++ rr(i))
    val got = attend(fq, rk, parentVals, lambda * 0.5f)
    // MLP: the transition of every slot, in parallel
    slots = slots.indices.map { i =>
      val s = slots(i); val g = got(i); val found = and(s.free, g(0))
      val gPc = g.slice(1, 1 + NPC); val gRegs = (0 until NV).map(r => g.slice(1 + NPC + 2 * r, 3 + NPC + 2 * r))
      val gR = g.slice(1 + NPC + 2 * NV, 1 + NPC + 2 * NV + NV)
      val (oC, oJ, oRej, oAcc) = (op(i)(0), op(i)(1), op(i)(2), op(i)(3))
      val a = s.active
      // JT r pol: does register r hold pol? (an AND over the one-hots: r, pol, the register's value)
      val holds = (0 until NV).map(r => (0 until 2).map(v => and(a, oJ, rr(i)(r), pol(i)(v), s.regs(r)(v))).sum).sum
      val jump = holds; val advance = and(a, oC) + and(a, oJ) - jump // CHOOSE and a JT that does not jump: PC + 1
      val pcNext = Array.tabulate(NPC) { p =>
        val stay = s.pc(p) - (if a == 1f && (oC + oJ) == 1f then s.pc(p) else 0f) - and(a, oRej, s.pc(p))
        val inc = if p > 0 then and(advance, s.pc(p - 1)) else 0f
        val jmp = and(jump, tgt(i)(p))
        val child = if p > 0 then and(found, gPc(p - 1)) else 0f
        stay + inc + jmp + child
      }
      val regsNext = (0 until NV).map { r =>
        Array.tabulate(2) { v =>
          val setTrue = if v == 1 then and(a, oC, rr(i)(r)) else 0f // the parent takes true
          val inherit = and(found, gRegs(r)(v)) // the child copies the parent...
          val childFalse = if v == 0 then and(found, gR(r)) else 0f // ...and takes false
          val cleared = and(a, oRej, s.regs(r)(v)) // a rejected slot is recycled
          s.regs(r)(v) + setTrue + inherit + childFalse - cleared
        }
      }.toVector
      Slot(
        active = a - and(a, oRej) - and(a, oAcc) + found,
        free = s.free - found + and(a, oRej),
        done = s.done + and(a, oAcc),
        pc = pcNext,
        regs = regsNext
      )
    }.toVector
  // the end: a COUNT head over the slots that accepted
  val solutions = counts(Seq(Array.fill(P)(1f)), slots.indices.map(i => idx(i).map(_ * slots(i).done))).head
  require(slots.forall(s => (s.pc ++ s.regs.flatten :+ s.active :+ s.free :+ s.done).forall(v => v == 0f || v == 1f)))
  (solutions, turns, peak)

// the program for a CNF; the reference: brute force, and the sequential machine's step count (all branches in turn)
def compile(cnf: Seq[Seq[(Int, Int)]]): Vector[Ins] =
  val b = scala.collection.mutable.ArrayBuffer.empty[Ins]
  for r <- 0 until NV do b += Ins("CHOOSE", r)
  for clause <- cnf do
    val next = b.size + clause.size + 1
    for (r, pol) <- clause do b += Ins("JT", r, pol, next)
    b += Ins("REJECT")
  b += Ins("ACCEPT"); b.toVector
def models(cnf: Seq[Seq[(Int, Int)]]) =
  (0 until (1 << NV)).count(m => cnf.forall(_.exists((r, pol) => ((m >> r) & 1) == pol)))
def sequentialSteps(prog: Vector[Ins]): Int = // one processor, every branch executed in turn
  def go(pc: Int, regs: Map[Int, Int]): Int = prog(pc) match
    case Ins("CHOOSE", r, _, _) => 1 + go(pc + 1, regs + (r -> 1)) + go(pc + 1, regs + (r -> 0))
    case Ins("JT", r, pol, k)   => 1 + go(if regs(r) == pol then k else pc + 1, regs)
    case _                      => 1
  go(0, Map.empty)

val rnd = new Random(7)
val cnfs = Seq(
  "x ∧ y ∧ ¬z" -> Seq(Seq(0 -> 1), Seq(1 -> 1), Seq(2 -> 0)),
  "(x ∨ y) ∧ (¬x ∨ z) ∧ (¬y ∨ ¬z)" -> Seq(Seq(0 -> 1, 1 -> 1), Seq(0 -> 0, 2 -> 1), Seq(1 -> 0, 2 -> 0)),
  "x ∧ ¬x  (unsatisfiable)" -> Seq(Seq(0 -> 1), Seq(0 -> 0)),
  "true (no clauses)" -> Seq()
) ++
  (1 to 4).map(i => s"random 3-CNF #$i" -> Seq.fill(4)(Seq.fill(3)(rnd.nextInt(NV) -> rnd.nextInt(2))))
for (label, cnf) <- cnfs do
  val prog = compile(cnf); val (sol, turns, peak) = run(prog); val ref = models(cnf); val seq = sequentialSteps(prog)
  check(sol == ref, label)
  println(
    f"$label%-34s solutions $sol (reference $ref) | parallel: $turns%2d turns, $peak%d slots at once | " +
      f"sequential: $seq%3d steps"
  )
if failures > 0 then sys.exit(1) else println("all checks passed")
