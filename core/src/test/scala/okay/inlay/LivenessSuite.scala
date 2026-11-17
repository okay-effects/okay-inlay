package okay.inlay

/** LIVENESS (specs/dsl.md "Liveness"): the `Clear` head — its meaning in the reference, its law, its checks — and
 * the allocation: ranges, the sweep, the coordinates handed on. (Every program's own suite runs its packed form on
 * its own inputs: `Packing`.) */
class LivenessSuite extends munit.FunSuite:
  private val r = scala.util.Random(5)
  private def c(v: String) = Val.C(Some(v))
  private val vocab = Vector("a", "b", "c")
  private val tok = Cat("tok", vocab)
  private val (pos, prev) = (Builtin.pos(5), Builtin.prev(5))
  private val (ptok, echo, again, seen) = (Cat("ptok", vocab), Cat("echo", vocab), Cat("again", vocab), Flag("seen"))
  private def text(n: Int) = Vector.fill(n)(Map[String, Val]("tok" -> c(vocab(r.nextInt(3)))))

  /** the previous token, echoed by a rule; then `ptok` and `prev` cleared in a layer whose other head still reads
   * `prev`, and whose rule reads the echo */
  private def program(causal: Boolean) = Program(Seq(tok, pos, prev), Seq(
    Layer(heads = Seq(Lookup(Seq(ptok -> tok), Seq(prev), Seq(pos)))),
    Layer(rules = Seq(Rule(Nil, Seq(Write.Copy(echo, ptok))))),
    Layer(heads = Seq(Clear(Seq(ptok, prev)), Lookup(Seq(again -> tok), Seq(prev), Seq(pos))),
      rules = Seq(Rule(Seq(Guard.Is(echo, "a")), Seq(Write.Raise(seen)))))), 32, causal)

  for causal <- Seq(true, false) do
    test(s"a clear (causal: $causal): the fields absent afterwards, their coordinates exactly zero; the law"):
      val p = program(causal)
      assert(p.problems.isEmpty, p.problems)
      assertEquals(p.outputs.map(_.name), Vector("tok", "pos", "echo", "again", "seen"))
      val w = Compile(p)
      for _ <- 1 to 200 do
        val in = text(1 + r.nextInt(31))
        val ref = Interpret.run(p, in)
        assertEquals(ref.map(_.keySet), in.map(_ => Set("tok", "pos", "echo", "again", "seen")))
        assertEquals(ref.map(_("again")), Val.C(None) +: in.init.map(_("tok")))
        assertEquals(Forward.run(p, w, in), ref)
        // in the weights: every position's coordinates of the cleared fields, the start's too, are 0 exactly
        val xs = Forward.layers(w, Forward.embed(p, w, in))
        for x <- xs; f <- Seq(ptok, prev); i <- 0 until f.dims do assertEquals(x(w.layout.ix(f, i)), 0f)

  test("the checks: read after cleared, cleared twice, cleared before written, a name written again"):
    def bad(layers: Layer*)(what: String) =
      val problems = Program(Seq(tok, pos, prev), layers, 32).problems
      assert(problems.exists(_.contains(what)), s"$what: $problems")
    val first = Layer(heads = Seq(Lookup(Seq(ptok -> tok), Seq(prev), Seq(pos))))
    // the rules of the clearing layer, and a head of a later one
    bad(first, Layer(heads = Seq(Clear(Seq(ptok))), rules = Seq(Rule(Nil, Seq(Write.Copy(echo, ptok))))))(
      "reads ptok after it is cleared")
    bad(first, Layer(heads = Seq(Clear(Seq(prev)))),
      Layer(heads = Seq(Lookup(Seq(again -> tok), Seq(prev), Seq(pos)))))("reads prev after it is cleared")
    bad(first, Layer(heads = Seq(Clear(Seq(ptok)))), Layer(heads = Seq(Clear(Seq(ptok)))))("ptok cleared twice")
    // a head's write lands in the layer's output: the same layer's clear would subtract nothing
    bad(Layer(heads = Seq(Lookup(Seq(ptok -> tok), Seq(prev), Seq(pos)), Clear(Seq(ptok)))))(
      "ptok cleared before it is written")
    bad(first, Layer(heads = Seq(Clear(Seq(ptok)))),
      Layer(heads = Seq(Lookup(Seq(ptok -> tok), Seq(prev), Seq(pos)))))("ptok written twice")
    val blind = Program(Seq(tok), Seq(Layer(heads = Seq(Clear(Seq(tok))))), 32, causal = false)
    assert(blind.problems.exists(_.contains("needs the input pos")), blind.problems)

  test("the ranges and the sweep: a field's coordinates handed to a later one, the width the peak"):
    val p = Program(Seq(tok, pos, prev), program(true).layers.map(l => l.copy(heads = l.heads.filter {
      case _: Clear => false
      case _ => true
    })), 32)
    val packed = Liveness(p, Set("again", "seen"))
    val ranges = packed.ranges.map(x => x.field.name -> (x.from, x.until)).toMap
    // tok: read by the last layer's head; pos: its key; prev: its query; ptok: the rule of layer 1; echo: of layer 2
    assertEquals(ranges, Map("tok" -> (-1, 2), "pos" -> (-1, 2), "prev" -> (-1, 2), "ptok" -> (0, 2),
      "echo" -> (1, 3), "again" -> (2, Liveness.Forever), "seen" -> (2, Liveness.Forever)))
    // alive at layer 2: echo 3, again 3, seen 1; before it: tok 3, pos 6, prev 6, ptok 3, echo 3 — the peak
    assertEquals(Liveness.peak(packed.ranges), 21)
    assertEquals(packed.layout.dims, 3 + 21)
    assertEquals(Layout.of(p).dims, 3 + 25)
    // again and seen sit where tok and a bit of pos were; one head clears them, in the layer that reads them last
    assertEquals(packed.clears, 1)
    assertEquals(packed.program.layers(2).heads.collect { case Clear(fs) => fs.map(_.name) }.flatten, Seq("tok", "pos"))
    assertEquals(packed.layout.scattered("again"), packed.layout.scattered("tok"))
    val packing = Packing(p, Seq(again, seen))
    for _ <- 1 to 200 do
      val in = text(1 + r.nextInt(31))
      packing.law(in, Interpret.run(p, in))
    // a mutation: the same layout without the clears is not the program
    val unclear = packing.packed.program.copy(layers = p.layers)
    val in = Vector("a", "b", "a", "c").map(t => Map[String, Val]("tok" -> c(t)))
    val broken = try Some(Forward.run(unclear, Compile(unclear, packing.packed.layout), in)) catch
      case _: Inexact => None
    assert(!broken.contains(Interpret.run(unclear, in)), "coordinates shared with nothing cleared: not the reference")
