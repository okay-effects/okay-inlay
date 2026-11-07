package okay.inlay.gpu

import okay.effects.*
import okay.inlay.Closure
import okay.inlay.bridge.{Bridge, Deduce, Edge, Judge}

/** THE BRIDGE'S DEDUCER ON APPLE'S GPU: the same program (`Bridge.above`), the compiled closure run by `OnMlx` — one
 * more handler, the same answers as plain search */
class BridgeOnMlxSuite extends munit.FunSuite:
  type Row[X] = (Judge + Deduce)[X]

  test("the bridge with the weights on the GPU == plain search"):
    val block = Bridge.block
    val onGpu = OnMlx(block.weights)
    def gpu(edges: Vector[Edge], q: (String, String)) =
      block.answer(onGpu.records(block.program, Closure.inputs(edges.map(_.asTriple), q)))
    val text = Vector("Ann manages Bob.", "Bob oversees Cid.", "Dee reports to Cid.", "Dee supervises Eve.",
      "Fay works under Eve.", "Eve leads Bob.", "The weather was fine.")
    try
      for q <- Seq(("Ann", "Fay"), ("Fay", "Ann"), ("Cid", "Bob"), ("Kim", "Lou")) do
        val rules = Bridge.phrasings(_)
        val bySearch = run(Bridge.reading(rules)(Bridge.deducing(Bridge.search)(Bridge.above[Row](text, q))))
        val byGpu = run(Bridge.reading(Bridge.phrasings(_))(Bridge.deducing(gpu)(Bridge.above[Row](text, q))))
        assertEquals(byGpu, bySearch, q.toString)
        println(s"$q: ${byGpu.answer}")
    finally onGpu.close()
