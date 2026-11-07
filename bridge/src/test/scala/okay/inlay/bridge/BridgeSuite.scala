package okay.inlay.bridge

import okay.effects.*

/** THE BRIDGE AS ONE PROGRAM, MANY HANDLERS: text in sentences of the known phrasings (and lines that are not), a
 * question; the program `Bridge.above` run with the rules as the reader and each deducer — plain search, the DSL's
 * reference, the compiled weights. The law: the same answer by every deducer, its sentences and cycles the text's
 * lines; and the rules reading what the gold says */
class BridgeSuite extends munit.FunSuite:
  type Row[X] = (Judge + Deduce)[X]
  val names = Bridge.names

  /** a random text: edges said in random phrasings, a back edge when cyclic, a line no phrasing reads */
  def text(rnd: scala.util.Random, n: Int, cyclic: Boolean): (Vector[String], Map[String, Edge]) =
    val order = rnd.shuffle(names)
    val edges = Vector.fill(n) {
      val i = rnd.nextInt(order.size - 1); val j = i + 1 + rnd.nextInt(order.size - 1 - i)
      (order(i), order(j))
    }
    val all = if cyclic then edges :+ { val (x, y) = edges(rnd.nextInt(edges.size)); (y, x) } else edges
    val said = rnd.shuffle(all).map { (boss, worker) =>
      if rnd.nextBoolean() then
        val verb = Bridge.phrasings.above(rnd.nextInt(Bridge.phrasings.above.size))
        (s"$boss $verb $worker.", Edge(boss, worker, true))
      else
        val verb = Bridge.phrasings.below(rnd.nextInt(Bridge.phrasings.below.size))
        (s"$worker $verb $boss.", Edge(worker, boss, false))
    }
    val noise = "The weather was fine."
    val lines = said.map(_._1).patch(rnd.nextInt(said.size + 1), Seq(noise), 0)
    (lines, said.toMap)

  test("one program: plain search == the DSL's reference == the weights; the rules read the gold"):
    val rnd = new scala.util.Random(5)
    val block = Bridge.block
    var yes = 0
    for trial <- 1 to 30 do
      val (lines, gold) = text(rnd, 6 + rnd.nextInt(14), trial % 4 == 0)
      val q = (names(rnd.nextInt(names.size)), names(rnd.nextInt(names.size)))
      // the program, run in one line per choice of handlers
      val rules = Bridge.phrasings(_)
      val bySearch = run(Bridge.reading(rules)(Bridge.deducing(Bridge.search)(Bridge.above[Row](lines, q))))
      val byReference = run(Bridge.reading(rules)(Bridge.deducing(block.interpret)(Bridge.above[Row](lines, q))))
      val byWeights = run(Bridge.reading(rules)(Bridge.deducing(block.forward)(Bridge.above[Row](lines, q))))
      val byGold = run(Bridge.reading(gold.get)(Bridge.deducing(block.forward)(Bridge.above[Row](lines, q))))
      assertEquals(byReference, bySearch, s"trial $trial")
      assertEquals(byWeights, bySearch, s"trial $trial")
      assertEquals(byGold, byWeights, s"trial $trial")
      assertEquals(byWeights.unread.map(lines), Vector("The weather was fine."))
      if bySearch.answer.yes then yes += 1
    println(s"the bridge: 30 texts, $yes answers yes; every deducer the same answer, the rules the gold's reading")
