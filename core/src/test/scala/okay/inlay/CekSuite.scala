package okay.inlay

/** THE CEK MACHINE WITH EFFECT HANDLERS IN THE DSL (docs/effects.md): the six programs of experiments/Handlers.sc —
 * deep handlers, continuations resumed twice — run by the reference machine written the ordinary way, by the DSL's
 * reference (`Interpret` a position at a time) and by the compiled weights (`Forward` a position at a time). The law:
 * the same answer as the ordinary machine; the same trace, step by step, from the reference and the weights */
class CekSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  val cek = Cek.Program(1024)
  lazy val weights = Compile(cek.step)

  test("the step program: two heads, the rules, its size"):
    assert(cek.step.problems.isEmpty, cek.step.problems.mkString("; "))
    val w = weights
    println(s"the step: ${w.layers.size} layer, ${w.layers.head.heads.size} heads, ${w.layers.head.mlp.w1.length} " +
      s"neurons, residual ${w.layout.dims}, ${w.params} parameters, pointers of ${cek.bits} bits")

  for (label, t) <- Cek.cases do
    test(s"$label: the weights == the DSL's reference == the ordinary machine"):
      val (refAnswer, refIt) = Cek.reference(t)
      val (byRef, refSteps, refRun) = cek.run(t, Machine.reference(cek.step))
      val (byW, wSteps, wRun) = cek.run(t, Machine.weights(cek.step, weights))
      println(f"$label%-28s → ${Cek.name(t, byW)}%-5s $wSteps%3d steps (the ordinary machine: $refIt iterations)")
      assertEquals(byRef, refAnswer)
      assertEquals(byW, refAnswer)
      assertEquals(wSteps, refSteps)
      assertEquals(wRun.trace, refRun.trace)
