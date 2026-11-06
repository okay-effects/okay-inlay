package okay.inlay

/** "How many orders are unpaid?" over 40 rows, as a compiled block: each row a position with its status, the question
 * a position asking for "unpaid"; a Count head compiled into weights and run numerically (float32) — the count exact,
 * 14, the reference's. The same 40 rows Qwen3.6-35B-A3B was asked (2026-11-06): 15 without thinking; 14 with thinking,
 * after 1,342 tokens and 198 s */
class OrdersCountSuite extends munit.FunSuite:
  val statuses: Vector[String] = Vector("paid","unpaid","unpaid","unpaid","paid","unpaid","paid","paid","paid","unpaid","unpaid","unpaid","paid","unpaid","paid","paid","unpaid","paid","paid","unpaid","paid","paid","paid","paid","unpaid","paid","unpaid","paid","paid","paid","paid","paid","paid","paid","paid","unpaid","unpaid","paid","paid","paid")

  test("how many orders are unpaid: the compiled block counts exactly"):
    val status = Cat("status", Vector("paid", "unpaid"))
    val ask = Cat("ask", Vector("paid", "unpaid"))
    val count = Cat("count", (0 to 64).map(_.toString).toVector)
    val p = Program(Seq(status, ask), Seq(Layer(heads = Seq(Count(count, Seq(ask), Seq(status))))), maxLen = 64)
    val w = Compile(p)
    val in = statuses.map(s => Map("status" -> Val.C(Some(s)))) :+ Map("ask" -> Val.C(Some("unpaid")))
    val ref = Interpret.run(p, in).last("count")
    val got = Forward.run(p, w, in).last("count")
    println(s"rows ${statuses.size}, unpaid ${statuses.count(_ == "unpaid")}; the reference $ref, the weights $got; " +
      s"residual ${w.layout.dims} dims, ${w.layers.size} layer, ${w.params} parameters")
    assertEquals(ref, Val.C(Some("14")))
    assertEquals(got, ref)
