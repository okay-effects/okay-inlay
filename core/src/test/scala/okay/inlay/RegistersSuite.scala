package okay.inlay

/** THE REGISTER MACHINE IN THE DSL (experiments/Looped.sc): one compiled step program, the program in the context; A+B
 * and A+2B on every input whose result fits, by the weights and the reference — the same trace, and the registers and
 * the steps of the ordinary interpreter */
class RegistersSuite extends munit.FunSuite:
  val m = Registers.Program()
  lazy val w = Compile(m.step)

  for (name, p, f) <- Seq(("A+B", Registers.add, (a: Int, b: Int) => a + b),
      ("A+2B", Registers.add2, (a: Int, b: Int) => a + 2 * b)) do
    test(s"$name: the same weights, every input — equal to the interpreter"):
      val inputs = for a <- 0 to m.top; b <- 0 to m.top if f(a, b) <= m.top yield (a, b)
      for (a, b) <- inputs do
        val (_, _, _, ref) = m.run(p, a, b, Machine.reference(m.step))
        val (wa, wb, wSteps, got) = m.run(p, a, b, Machine.weights(m.step, w))
        assertEquals(got.trace, ref.trace, s"($a, $b)")
        assertEquals((wa, wb, wSteps), Registers.interpret(p, a, b), s"($a, $b)")
      println(s"$name: ${inputs.size} inputs, one step program (${w.layers.map(_.mlp.w1.length).sum} neurons, " +
        s"${w.layers.map(_.heads.size).sum} head, residual ${w.layout.dims}): all equal to the interpreter")
