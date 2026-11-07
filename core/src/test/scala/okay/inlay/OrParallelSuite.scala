package okay.inlay

/** OR-PARALLEL SEARCH IN THE DSL (docs/effects.md): experiments/OrParallel.sc's machine — slots forking on CHOOSE,
 * children allocated by prefix counts — as a wide `Machine`: the weights' trace == the reference's, round by round;
 * the solutions == brute force, on the experiment's formulas and random 3-CNF */
class OrParallelSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  val m = OrParallel.Program(nv = 3, npc = 24, slots = 12, maxLen = 512, maxTurns = 32)
  lazy val weights = Compile(m.step)

  test("the step program"):
    assert(m.step.problems.isEmpty, m.step.problems.mkString("; "))
    val w = weights
    println(s"or-parallel: ${w.layers.size} layers, ${w.layers.map(_.heads.size).sum} heads, " +
      s"${w.layers.map(_.mlp.w1.length).sum} neurons, residual ${w.layout.dims}, ${m.slots} slots")

  val rnd = new scala.util.Random(7)
  val random = (1 to 4).map(i => s"random 3-CNF #$i" -> Seq.fill(4)(Seq.fill(3)(rnd.nextInt(3) -> rnd.nextBoolean())))
  for (label, cnf) <- OrParallel.cases ++ random do
    test(s"$label: the weights == the reference; the solutions == brute force"):
      val prog = OrParallel.compile(3, cnf)
      val (byRef, turns, peak, refRun) = m.run(prog, Machine.reference(m.step))
      val (byW, wTurns, _, wRun) = m.run(prog, Machine.weights(m.step, weights))
      println(f"$label%-34s solutions $byW (brute force ${OrParallel.models(3, cnf)}), $wTurns%2d turns, $peak slots")
      assertEquals(byRef, OrParallel.models(3, cnf))
      assertEquals(byW, byRef)
      assertEquals(wTurns, turns)
      assertEquals(wRun.trace, refRun.trace)
