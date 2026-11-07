package okay.inlay

/** THE COVERING HEAD (DPLL's): on random clauses and assignments, the weights == the reference; the reference == the
 * direct reading — a clause falsified (slack 0), the unit clauses' literals (slack 1, no literal true) */
class CoverSuite extends munit.FunSuite:
  val vars: Vector[String] = (0 until 6).map(_.toString).toVector
  val pos = Many("POS", vars); val neg = Many("NEG", vars); val kind = Cat("KIND", Vector("clause"))
  val tset = Many("TSET", vars); val fset = Many("FSET", vars); val ask = Cat("ASK", Vector("clause"))
  val conf = Many("CONF", Vector("clause")); val upos = Many("UPOS", vars); val uneg = Many("UNEG", vars)
  val p = Program(Seq(pos, neg, kind, tset, fset, ask), Seq(Layer(heads = Seq(
    Cover(Seq(conf -> kind), Seq(ask), Seq(kind), Seq(fset -> pos, tset -> neg)),
    Cover(Seq(upos -> pos, uneg -> neg), Seq(ask), Seq(kind), Seq(fset -> pos, tset -> neg),
      apart = Seq(tset -> pos, fset -> neg), slack = 1)))), maxLen = 32)

  test("the law on random clauses and assignments; the meaning"):
    assert(p.problems.isEmpty, p.problems.mkString("; "))
    val w = Compile(p)
    val rnd = new scala.util.Random(9)
    var conflicts = 0; var units = 0
    for _ <- 1 to 300 do
      val clauses = Vector.fill(4 + rnd.nextInt(12)) {
        val vs = rnd.shuffle(vars).take(1 + rnd.nextInt(3)); vs.map(v => v -> rnd.nextBoolean())
      }
      val assign = vars.flatMap(v => rnd.nextInt(3) match
        case 0 => Some(v -> true)
        case 1 => Some(v -> false)
        case _ => None).toMap
      val in = clauses.map(c => Map(pos.name -> Val.M(c.collect { case (v, true) => v }.toSet),
        neg.name -> Val.M(c.collect { case (v, false) => v }.toSet), kind.name -> Val.C(Some("clause")))) :+
        Map(tset.name -> Val.M(assign.collect { case (v, true) => v }.toSet),
          fset.name -> Val.M(assign.collect { case (v, false) => v }.toSet), ask.name -> Val.C(Some("clause")))
      val ref = Interpret.run(p, in).last
      assertEquals(Forward.run(p, w, in).last, ref)
      // the meaning, directly
      def value(v: String, s: Boolean) = assign.get(v).map(_ == s)
      val falsified = clauses.exists(_.forall((v, s) => value(v, s).contains(false)))
      assertEquals(ref(conf.name) == Val.M(Set("clause")), falsified)
      if falsified then conflicts += 1
      else
        val unitClauses = clauses.filter(c =>
          c.count((v, s) => value(v, s).isEmpty) == 1 && !c.exists((v, s) => value(v, s).contains(true)))
        val lits = unitClauses.flatMap(_.filter((v, s) => value(v, s).isEmpty))
        // (the head gathers the unit clauses' whole sets; the unassigned among them are the implied literals)
        def unassigned(m: Val) = m match { case Val.M(s) => s.filterNot(assign.contains); case _ => Set.empty[String] }
        assertEquals(unassigned(ref(upos.name)), lits.collect { case (v, true) => v }.toSet)
        assertEquals(unassigned(ref(uneg.name)), lits.collect { case (v, false) => v }.toSet)
        if lits.nonEmpty then units += 1
    println(s"cover: 300 cases, $conflicts with a conflict, $units with unit literals; ${w.layout.dims} dims")
