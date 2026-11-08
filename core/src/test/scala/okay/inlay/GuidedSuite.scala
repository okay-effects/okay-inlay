package okay.inlay

/** GUIDED SEARCH IN THE DSL (docs/effects.md; experiments/Branching.sc, Gated.sc): DPLL's decision as an effect whose
 * handler is advice — none (plain DPLL: true first, a fork always), or an adviser's guess and confidence per variable
 * (sure: dive, no fork; unsure: fork, the guess first). The same step program; to the first model. On planted 3-SAT:
 * the weights == the reference; every model found satisfies the formula; exact advice finds it without a fork; noisy
 * advice can fail on a wrong sure guess, and plain DPLL is the backup — never a wrong answer, only time */
class GuidedSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")
  val n = 12

  /** 3-SAT with a planted model: random clauses kept when the planted assignment satisfies them */
  def planted(rnd: scala.util.Random, m: Int): (Vector[Dpll.Clause], Map[Int, Boolean]) =
    val plant = (0 until n).map(v => v -> rnd.nextBoolean()).toMap
    val cnf = Iterator.continually(rnd.shuffle((0 until n).toList).take(3).map(v => v -> rnd.nextBoolean()))
      .filter(_.exists((v, s) => plant(v) == s)).take(m).toVector
    (cnf, plant)
  def satisfies(cnf: Seq[Dpll.Clause], a: Map[Int, Boolean]) = cnf.forall(_.exists((v, s) => a.get(v).contains(s)))

  val m = Dpll.Program(n, slots = 24, maxLen = 1536)
  lazy val w = Compile(m.step)
  /** the search by both handlers of the step — the law — and its outcome */
  def search(cnf: Vector[Dpll.Clause], advice: Map[Int, (Boolean, Boolean)]): Dpll.Search =
    val ref = m.guided(cnf, Machine.reference(m.step), advice, firstOnly = true)
    val got = m.guided(cnf, Machine.weights(m.step, w), advice, firstOnly = true)
    assertEquals(got.run.trace, ref.run.trace)
    got.found.foreach(a => assert(satisfies(cnf, a), s"a model that does not satisfy: $a"))
    got

  test("plain, exact advice, noisy advice with plain DPLL as the backup"):
    val rnd = new scala.util.Random(21)
    var plainNodes = 0; var exactNodes = 0; var noisyNodes = 0; var noisyFound = 0
    for i <- 1 to 4 do
      val (cnf, plant) = planted(rnd, 51)
      val plain = search(cnf, Map.empty)
      assert(plain.models >= 1)
      val exact = search(cnf, plant.map((v, b) => v -> (b, true)))
      assert(exact.models >= 1); assertEquals(exact.nodes, 1)
      // noisy: sure on half the variables (right 95% of the time), unsure elsewhere (right 55%)
      val noisy = plant.map { (v, b) =>
        val sure = rnd.nextBoolean()
        v -> ((if rnd.nextDouble() < (if sure then 0.95 else 0.55) then b else !b), sure)
      }
      val tried = search(cnf, noisy)
      val total = if tried.models >= 1 then { noisyFound += 1; tried.nodes }
        else tried.nodes + search(cnf, Map.empty).nodes
      val outcome = if tried.models >= 1 then "found" else "not found → plain"
      println(f"#$i: plain ${plain.nodes}%2d branch nodes ${plain.turns}%2d turns | exact advice ${exact.nodes} node " +
        f"${exact.turns}%2d turns | noisy ${tried.nodes}%2d nodes, $outcome, " +
        f"$total%2d in all")
      plainNodes += plain.nodes; exactNodes += exact.nodes; noisyNodes += total
    println(s"to the first model, 4 planted formulas (n=$n, m=51): plain $plainNodes branch nodes, exact advice " +
      s"$exactNodes, noisy advice $noisyNodes in all ($noisyFound found by the advice alone)")
