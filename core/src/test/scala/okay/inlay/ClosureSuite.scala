package okay.inlay

/** the closure program (bridge-4): the weights equal the reference and plain search on random graphs */
class ClosureSuite extends munit.FunSuite:
  val names = Vector("Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal", "Ivy", "Jon", "Kim", "Lou")

  def graph(rnd: scala.util.Random, n: Int, cyclic: Boolean): Vector[(String, String, Boolean)] =
    // a random forest-like order with extra edges; a back edge when cyclic
    val order = rnd.shuffle(names)
    val edges = Vector.fill(n) {
      val i = rnd.nextInt(order.size - 1); val j = i + 1 + rnd.nextInt(order.size - 1 - i)
      (order(i), order(j))
    }
    val all = if cyclic then edges :+ { val (x, y) = edges(rnd.nextInt(edges.size)); (y, x) } else edges
    rnd.shuffle(all).map((x, y) => if rnd.nextBoolean() then (x, y, true) else (y, x, false))

  test("closure in weights == the interpreter == plain search; attribution and cycles"):
    val (p, _) = Closure.program(names, 40, 4)
    val w = Compile(p)
    val rnd = new scala.util.Random(3)
    var yes = 0; var cycles = 0
    for trial <- 1 to 40 do
      val cyclic = trial % 4 == 0
      val sentences = graph(rnd, 8 + rnd.nextInt(16), cyclic)
      val q = (names(rnd.nextInt(names.size)), names(rnd.nextInt(names.size)))
      val in = Closure.inputs(sentences, q)
      val ref = Interpret.run(p, in)
      val got = Forward.run(p, w, in)
      assertEquals(got.map(_.filter((k, _) => Set("yes", "on", "cycle")(k))),
        ref.map(_.filter((k, _) => Set("yes", "on", "cycle")(k))), s"trial $trial")
      val (truth, used, onCycle) = Closure.reference(sentences, q)
      val last = ref.last
      assertEquals(last("yes"), Val.F(truth), s"trial $trial: $q over ${sentences.mkString(" ")}")
      // what the answer rests on: the sentences on paths from qa (a superset of a witness path, exact as a set)
      assertEquals(last("on"), Val.M(used.map(_.toString)), s"trial $trial attribution")
      assertEquals(ref.init.zipWithIndex.filter(_._1("cycle") == Val.F(true)).map(_._2 + 1).toSet, onCycle,
        s"trial $trial cycles")
      if truth then yes += 1
      if onCycle.nonEmpty then cycles += 1
    println(s"closure: 40 graphs, $yes answers yes, $cycles with a cycle; ${w.layout.dims} dims, " +
      s"${w.layers.map(_.mlp.w1.length).sum} neurons, ${w.layers.map(_.heads.size).sum} heads, ${p.layers.size} layers")
