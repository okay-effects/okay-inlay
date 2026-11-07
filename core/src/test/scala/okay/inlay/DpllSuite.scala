package okay.inlay

/** DPLL IN THE DSL (docs/effects.md): experiments/Dpll.sc's machine — conflicts and unit propagation by `Cover` heads
 * over every clause and branch at once, decisions forking into free slots — as a wide `Machine`: the weights' trace ==
 * the reference's, round by round; the models (#SAT) == brute force */
class DpllSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")

  def pigeons: Vector[Dpll.Clause] =
    val each = (0 until 3).map(i => Seq((2 * i) -> true, (2 * i + 1) -> true))
    val apart = for h <- 0 until 2; i <- 0 until 3; j <- i + 1 until 3
      yield Seq((2 * i + h) -> false, (2 * j + h) -> false)
    (each ++ apart).toVector

  val cases: Seq[(String, Int, Vector[Dpll.Clause])] = Seq(("pigeons: 3 in 2 holes (UNSAT)", 6, pigeons),
    ("(x ∨ y) ∧ (¬x ∨ z) ∧ (¬y ∨ ¬z)", 3, Vector(Seq(0 -> true, 1 -> true), Seq(0 -> false, 2 -> true),
      Seq(1 -> false, 2 -> false)))) ++
    (1 to 3).map(s => (s"random 3-SAT n=8 m=34 #$s", 8, Dpll.random3(8, 34, new scala.util.Random(s)))) ++
    (1 to 2).map(s => (s"random 3-SAT n=10 m=42 #$s", 10, Dpll.random3(10, 42, new scala.util.Random(s))))

  for (label, n, cnf) <- cases do
    test(s"$label: the weights == the reference; the models == brute force"):
      val m = Dpll.Program(n, slots = 20)
      assert(m.step.problems.isEmpty, m.step.problems.mkString("; "))
      val w = Compile(m.step)
      val (byRef, turns, peak, nodes, refRun) = m.run(cnf, Machine.reference(m.step))
      val (byW, _, _, _, wRun) = m.run(cnf, Machine.weights(m.step, w))
      println(f"$label%-32s models $byW (brute force ${Dpll.models(n, cnf)}), $turns%2d turns, $peak%2d branches at " +
        f"once, $nodes%2d branch nodes (brute force: ${1 << n} leaves) — ${w.layers.size} layers, " +
        f"${w.layers.map(_.mlp.w1.length).sum} neurons, residual ${w.layout.dims}")
      assertEquals(byRef, Dpll.models(n, cnf))
      assertEquals(byW, byRef)
      assertEquals(wRun.trace, refRun.trace)
