package okay.inlay.domain

/**
 * THE KIT'S NUMBERS, FIXED (specs/domains.md "On okay-frames"): `Kit.read` and `Kit.measure` on fixed seeds, for two
 * readers and three margins, against the numbers the kit gave on `master` (f7983a9) before it was rewritten over
 * okay-frames. Only whole numbers and strings are compared, so the JVM and Scala.js print the same.
 */
class KitNumbersSuite extends munit.FunSuite:
  private val keywords = new UnitsSuite().keywords

  /** a reader of graded scores: the keywords' choice blended with a fixed noise over every choice and "-" (a slot
   * not used); weak scores dropped, and now and then nothing read at all — so second choices, margins, an explicit
   * "-" and an empty reading all occur */
  object graded extends Kit.Reader[Units.type]:
    private def noise(q: String, slot: Slot, c: String): Double =
      ((q + "|" + slot.name + "|" + c).hashCode & 0x7fffffff) % 1000003 / 1000003.0
    def read(q: String, s: Units.type, slot: Slot): Map[String, Double] =
      val kw = keywords.read(q, s, slot)
      if noise(q, slot, "?") < 0.15 then Map.empty
      else (slot.choices :+ "-").map(c => c -> (kw.getOrElse(c, 0.0) * 0.6 + noise(q, slot, c) * 0.5))
        .filter(_._2 > 0.2).toMap

  private def said(m: Kit.Measure): String =
    s"${m.n} whole ${m.whole} abstained ${m.abstained} unread ${m.unread} wrong ${m.wrongAnswered} " +
      m.bySlot.toVector.sortBy(_._1).map { case (s, (r, t)) => s"$s $r/$t" }.mkString(" ")

  private def margin(m: Double): String = if m.isInfinity then "inf" else math.round(m * 1e6).toString

  /** every reading of the pairs as one string's hash, and how many answered */
  private def readings(reader: Kit.Reader[Units.type], pairs: Vector[Pair[Units.Point]], bar: Double): String =
    val rs = pairs.map(p => Kit.read(Units)(Units, p.question, reader, Units.copier, bar))
    val text = rs.map(r => s"${r.parts.toVector.sorted}|${r.margins.toVector.sortBy(_._1).map((s, m) =>
      s"$s:${margin(m)}")}|${r.abstained.sorted}|${r.point}").mkString("\n")
    s"${rs.count(_.answered)} answered, hash ${text.hashCode}"

  private val pairs = Kit.pairs(Units)(Units, 300, 11, Vector("en", "ru"))

  /** (reader, margin) → the measure and the readings on `master` f7983a9 */
  private val keywordSlots = " between_unit 64/64 date1 126/126 date2 64/64 direction 62/62 from 162/174 " +
    "kind 300/300 n 62/62 quantity 174/174 shift_unit 61/62 to 151/174"
  private val gradedSlots = " between_unit 51/64 date1 126/126 date2 64/64 direction 56/62 from 147/174 " +
    "kind 248/300 n 62/62 quantity 174/174 shift_unit 54/62 to 125/174"
  private val master: Map[(String, Double), (String, String)] = Map(
    ("keywords", 0.0) -> ("300 whole 264 abstained 0 unread 36 wrong 0" + keywordSlots,
      "264 answered, hash -911142543"),
    ("keywords", 0.2) -> ("300 whole 264 abstained 0 unread 36 wrong 0" + keywordSlots,
      "264 answered, hash -911142543"),
    ("keywords", 0.5) -> ("300 whole 264 abstained 0 unread 36 wrong 0" + keywordSlots,
      "264 answered, hash -911142543"),
    ("graded", 0.0) -> ("300 whole 169 abstained 0 unread 128 wrong 3" + gradedSlots,
      "172 answered, hash -1410653296"),
    ("graded", 0.2) -> ("300 whole 30 abstained 247 unread 23 wrong 0" + gradedSlots,
      "30 answered, hash 1637540104"),
    ("graded", 0.5) -> ("300 whole 1 abstained 299 unread 0 wrong 0" + gradedSlots,
      "1 answered, hash -543590860"))

  for (name, reader) <- Seq("keywords" -> keywords, "graded" -> graded); bar <- Seq(0.0, 0.2, 0.5) do
    test(s"$name, margin $bar: the measure and every reading as on master"):
      val got = (said(Kit.measure(Units)(Units, pairs, reader, Units.copier, bar)), readings(reader, pairs, bar))
      assertEquals(got, master((name, bar)))
