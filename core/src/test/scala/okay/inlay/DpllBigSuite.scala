package okay.inlay

/** DPLL in the DSL on 20 variables, 85 clauses (the experiment's size): the weights == the reference, the models ==
 * brute force over a million assignments. Opt-in: DPLL_BIG=1 (minutes) */
class DpllBigSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "min")
  test("random 3-SAT n=20 m=85"):
    assume(sys.env.contains("DPLL_BIG"), "DPLL_BIG=1 to run")
    val cnf = Dpll.random3(20, 85, new scala.util.Random(301))
    val m = Dpll.Program(20, slots = 24, maxLen = 1536)
    val w = Compile(m.step)
    val t0 = System.nanoTime()
    val (byRef, turns, peak, nodes, refRun) = m.run(cnf, Machine.reference(m.step))
    val (byW, _, _, _, wRun) = m.run(cnf, Machine.weights(m.step, w))
    println(f"n=20 m=85: models $byW (brute force ${Dpll.models(20, cnf)}), $turns turns, $peak branches at once, " +
      f"$nodes branch nodes against 1,048,576 leaves — residual ${w.layout.dims}, " +
      f"${w.layers.map(_.mlp.w1.length).sum} neurons, ${(System.nanoTime() - t0) / 1e9}%.0f s")
    assertEquals(byRef, Dpll.models(20, cnf))
    assertEquals(byW, byRef)
    assertEquals(wRun.trace, refRun.trace)
