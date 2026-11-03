var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// DATALOG IN A LOOPED TRANSFORMER. Constants are positions (1..n; 0 is BOS); a binary relation is a bit-row in each
// position's residual: row X of PARENT holds parent(X, ·), row X of ANC holds anc(X, ·). One block = one round of
// bottom-up evaluation for EVERY constant at once (parallel over positions); loop to the fixpoint (nothing new).
//   rule  anc(X,Z) :- R(X,Y), anc(Y,Z)
//   head: query = row X of R (+ BOS), key = the constant's id → attends to every Y with R(X,Y) (and BOS);
//         value = row Y of ANC → the mean over them: > 0 at Z iff some Y has anc(Y,Z)
//   MLP:  new bit = step(old + mean) — an OR, exact; writes the difference (monotone: bits only ever turn on)
// float32, exact.
import scala.util.Random
val lambda = 250f
def step(v: Float, n: Int): Float =                     // 0 below 1/(2(n+2)), 1 from 3/(4(n+2)): two ReLUs
  val s = 4f * (n + 2); val t = 1f / (2 * (n + 2))
  math.max(0f, s * (v - t)) - math.max(0f, s * (v - t) - 1)

// one round for all positions: query rows from `q` (R), values from `anc`
def round(q: Array[Array[Float]], anc: Array[Array[Float]]): Array[Array[Float]] =
  val n = anc.length - 1
  Array.tabulate(n + 1) { x =>
    if x == 0 then anc(0) else
      val query = q(x).clone; query(0) = 1f                                    // BOS always selected
      val scores = (0 to n).map(y => lambda * query(y))                        // key = one-hot id of y
      val mx = scores.max; val e = scores.map(s => math.exp((s - mx).toDouble).toFloat); val w = e.map(_ / e.sum)
      val mean = Array.tabulate(n + 1)(z => (0 to n).map(y => w(y) * anc(y)(z)).sum)
      Array.tabulate(n + 1)(z => if z == 0 then 0f else { val nw = step(anc(x)(z) + mean(z), n); anc(x)(z) + (nw - anc(x)(z)) })
  }
def fixpoint(parent: Array[Array[Float]], doubling: Boolean): (Array[Array[Float]], Int) =
  var anc = parent.map(_.clone)                                                // anc(X,Y) :- parent(X,Y)
  var rounds = 0; var changed = true
  while changed do
    val next = round(if doubling then anc else parent, anc)
    changed = !next.indices.forall(i => next(i).sameElements(anc(i)))
    anc = next; rounds += 1
  (anc, rounds)

def reference(edges: Set[(Int, Int)], n: Int): Set[(Int, Int)] =             // naive bottom-up Datalog
  var anc = edges; var grew = true
  while grew do
    val next = anc ++ (for (x, y) <- edges; (y2, z) <- anc if y == y2 yield (x, z))
    grew = next.size > anc.size; anc = next
  anc
def rows(edges: Set[(Int, Int)], n: Int) =
  Array.tabulate(n + 1)(x => Array.tabulate(n + 1)(y => if edges((x, y)) then 1f else 0f))
def facts(a: Array[Array[Float]]) =
  (for x <- a.indices; y <- a.indices if a(x)(y) == 1f yield (x, y)).toSet
def exact(a: Array[Array[Float]]) = a.forall(_.forall(v => v == 0f || v == 1f))

val n = 16
val graphs = Seq("a chain 1→2→…→16" -> (1 until n).map(i => (i, i + 1)).toSet) ++
  (1 to 5).map { seed => val r = new Random(seed)
    s"random, seed $seed" -> (for x <- 1 to n; y <- 1 to n if x != y && r.nextDouble() < 0.08 yield (x, y)).toSet }
for (label, edges) <- graphs do
  val ref = reference(edges, n)
  val (lin, rl) = fixpoint(rows(edges, n), doubling = false)
  val (dbl, rd) = fixpoint(rows(edges, n), doubling = true)
  val ok = facts(lin) == ref && facts(dbl) == ref && exact(lin) && exact(dbl)
  check(ok, label)
  println(f"$label%-22s ${edges.size}%3d edges → ${ref.size}%3d anc facts | linear rule: $rl%2d rounds | " +
    f"doubling rule: $rd%2d rounds | ${if ok then "= reference" else "DIFFERS"}")
if failures > 0 then sys.exit(1) else println("all checks passed")
