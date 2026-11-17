package okay.inlay.domain

import okay.frames.{Choice, Laws, Reading}

/** A DOMAIN AS A SPACE OF okay-frames (specs/domains.md "On okay-frames"): `Units` through `Kit.space` obeys
 * okay-frames' laws on drawn points, and okay-frames' own measure agrees with the kit's where they count alike */
class SlotSpaceSuite extends munit.FunSuite:
  private val space = Kit.space(Units)(Units.copier)
  private val keywords = new UnitsSuite().keywords
  private val pairs = Kit.pairs(Units)(Units, 300, 11, Vector("en", "ru"))

  test("reach: a drawn point's path is offered step by step for its question, and closes to the point"):
    val rnd = scala.util.Random(7)
    (1 to 500).foreach { _ =>
      val p = Units.random(rnd, Units)
      val lang = if rnd.nextBoolean() then "en" else "ru"
      val q = Units.say(rnd, p, Units, lang)
      assertEquals(Laws.reach(space)(Units, q, p), None, q)
    }

  test("reach needs the text: a point with copied parts is not reachable for a text that does not write them"):
    val refused = Laws.reach(space)(Units, "How many minutes are in an hour?", Units.Point.Convert(3, "hour", "minute"))
    assert(refused.exists(_.contains("Choice(quantity,3) is not offered")), refused)

  test("the round trip: the canonical words of a point parse to it — drawn and every enumerated point"):
    val rnd = scala.util.Random(7)
    (1 to 500).foreach { _ =>
      assertEquals(Laws.roundTrip(space)(Units, Units.random(rnd, Units), Seq("en", "ru")), None)
    }
    Units.enumerate(Units).foreach(p => assertEquals(Laws.roundTrip(space)(Units, p, Seq("en")), None))
    assertEquals(Laws.distinct(space)(Units, Units.enumerate(Units).toVector, "en"), None)

  test("the canonical words: parts in the slots' order; any other words parse to nothing"):
    val shift = Units.Point.Shift(Units.Date(2026, 1, 31), 3, "month", true)
    val words = "kind=shift; shift_unit=month; direction=after; date1=2026-01-31; n=3"
    assertEquals(space.say(Units, shift, "en"), words)
    assertEquals(space.parse(Units, words, "ru"), Some(shift))
    Seq("kind=shift; direction=after; shift_unit=month; date1=2026-01-31; n=3", words + "; from=hour", words + ";",
      "kind=shift;shift_unit=month; direction=after; date1=2026-01-31; n=3", "What date is 3 months after 2026-01-31?",
      "").foreach(other => assertEquals(space.parse(Units, other, "en"), None, other))

  /** a domain whose copied value writes the canonical words' own marks */
  object Notes extends Domain:
    type Schema = Unit; type Point = (String, String); type Answer = String
    val name = "notes"
    def enumerate(s: Unit) = Iterator.empty
    def random(rnd: scala.util.Random, s: Unit) = ("to", "x")
    def run(p: Point, s: Unit) = Right(p._2)
    def say(rnd: scala.util.Random, p: Point, s: Unit, language: String) = s"${p._1} «${p._2}»"
    def slots(s: Unit) = Vector(Slot("verb", Vector("to", "from")), Slot("a=b; c", Vector.empty, copied = true))
    def parts(p: Point) = Map("verb" -> p._1, "a=b; c" -> p._2)
    def assemble(s: Unit, ps: Map[String, String]) = for v <- ps.get("verb"); t <- ps.get("a=b; c") yield (v, t)
    object copier extends Kit.Copier[Unit]:
      def copy(q: String, s: Unit, slot: Slot) = "«(.*)»".r.findFirstMatchIn(q).map(_.group(1))

  test("the canonical words escape their own marks: any copied text comes back"):
    val notes = Kit.space(Notes)(Notes.copier)
    for text <- Seq("x", "", " ", "a; b", "k=v", """\""", """\;=\\ ; =""", "verb=from; a=b") do
      val p = ("to", text)
      assertEquals(Laws.roundTrip(notes)((), p, Seq("en")), None, text)
      assertEquals(Laws.reach(notes)((), Notes.say(null, p, (), "en"), p), None, text)

  test("a reading: the copied slots have no rival, an unused slot is read as \"-\", the term is the kit's point"):
    val q = "What date is 3 months after 2026-01-31?"
    val r = Kit.reading(Units)(Units, q, keywords, Units.copier)
    assert(r.done && !r.stuck)
    assertEquals(r.chosen.map(_.hole), Units.slots(Units).map(_.name))
    assertEquals(r.meaning, Some(Units.Point.Shift(Units.Date(2026, 1, 31), 3, "month", true)))
    assert(r.chosen.contains(Choice("date2", "-")) && r.chosen.contains(Choice("date1", "2026-01-31")))
    assertEquals(r.meaning, Kit.read(Units)(Units, q, keywords, Units.copier).point)
    // the same reading by a word with authority at every hole: the meant path closes to the point
    val told = space.path(Units, r.meaning.get).foldLeft(Reading(space, Units, q))((x, c) => x.chose(c.option))
    assertEquals(told.meaning, r.meaning)

  test("okay-frames' measure: the kit's totals; it asks about an unused slot too; its table by hole is another"):
    for bar <- Seq(0.0, 0.5) do
      val kit = Kit.measure(Units)(Units, pairs, keywords, Units.copier, bar)
      val fr = Kit.framed(Units)(Units, pairs, keywords, Units.copier, bar)
      println(s"keywords, bar $bar — the kit: ${kit.text}\n  okay-frames: ${fr.said}")
      assertEquals((fr.texts, fr.right, fr.asked, fr.unread, fr.wrong),
        (kit.n, kit.whole, kit.abstained, kit.unread, kit.wrongAnswered))
    val graded = new KitNumbersSuite().graded
    for bar <- Seq(0.0, 0.2, 0.5) do
      val kit = Kit.measure(Units)(Units, pairs, graded, Units.copier, bar)
      val fr = Kit.framed(Units)(Units, pairs, graded, Units.copier, bar)
      println(s"graded, bar $bar — the kit: ${kit.text}\n  okay-frames: ${fr.said}")
      assertEquals(fr.texts, kit.n)
      // every reading the kit abstains on okay-frames asks about, and more: a doubtful "-" is doubt to it
      assert(fr.asked >= kit.abstained, s"${fr.asked} asked, ${kit.abstained} abstained")
      if bar == 0.0 then
        assertEquals((fr.right, fr.asked, fr.unread, fr.wrong), (kit.whole, 0, kit.unread, kit.wrongAnswered))
