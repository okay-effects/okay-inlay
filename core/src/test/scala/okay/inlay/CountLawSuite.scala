package okay.inlay
class CountLawSuite extends munit.FunSuite:
  test("Count cap 100 law, Forward"):
    val h = Cat("h", Vector("1")); val q = Cat("q", Vector("1")); val count = Cat("count", (0 to 100).map(_.toString).toVector)
    val p = Program(Seq(h, q), Seq(Layer(heads = Seq(Count(count, Seq(q), Seq(h))))), maxLen = 128)
    val w = Compile(p)
    val rnd = new scala.util.Random(1)
    for n <- Seq(5, 10, 20, 40, 80, 100) do
      for _ <- 1 to 20 do
        val hits = Vector.fill(n)(rnd.nextBoolean())
        val in = hits.map(b => Map("h" -> Val.C(if b then Some("1") else None))) :+ Map("q" -> Val.C(Some("1")))
        val ref = Interpret.run(p, in).last("count"); val got = Forward.run(p, w, in).last("count")
        assertEquals(got, ref, s"n=$n"); assertEquals(ref, Val.C(Some(hits.count(identity).toString)))
