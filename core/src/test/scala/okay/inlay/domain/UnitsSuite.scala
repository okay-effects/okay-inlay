package okay.inlay.domain

import Units.{Date, Point}

/** THE LAWS A DOMAIN PASSES (specs/domains.md §1), on units and the calendar; the kit end to end with a reader of
 * keywords (a baseline: the bus's probes are measured against it) */
class UnitsSuite extends munit.FunSuite:
  val rnd = scala.util.Random(7)

  test("the engine against hand-computed answers"):
    assertEquals(Units.run(Point.Convert(3, "hour", "minute"), Units), Right("180"))
    assertEquals(Units.run(Point.Convert(90, "minute", "hour"), Units), Right("1.5"))
    assertEquals(Units.run(Point.Convert(1, "minute", "hour"), Units), Right("1/60"))
    assertEquals(Units.run(Point.Convert(2, "kilometre", "metre"), Units), Right("2000"))
    assertEquals(Units.run(Point.Convert(1, "gigabyte", "megabyte"), Units), Right("1024"))
    assert(Units.run(Point.Convert(1, "hour", "metre"), Units).isLeft)
    assertEquals(Units.run(Point.Shift(Date(2026, 1, 31), 1, "month", true), Units), Right("2026-02-28"))
    assertEquals(Units.run(Point.Shift(Date(2024, 2, 29), 1, "month", true), Units), Right("2024-03-29"))
    assertEquals(Units.run(Point.Shift(Date(2026, 3, 1), 1, "day", false), Units), Right("2026-02-28"))
    assertEquals(Units.run(Point.Shift(Date(2024, 3, 1), 1, "day", false), Units), Right("2024-02-29"))
    assertEquals(Units.run(Point.Shift(Date(2025, 12, 25), 2, "week", true), Units), Right("2026-01-08"))
    assertEquals(Units.run(Point.Between(Date(2026, 1, 1), Date(2026, 12, 31), "day"), Units), Right("364"))
    assertEquals(Units.run(Point.Between(Date(2026, 1, 1), Date(2026, 1, 15), "week"), Units), Right("2"))

  test("the calendar: ordinal and back for every day of 1999-2031"):
    var x = Date.ordinal(Date(1999, 1, 1)); val end = Date.ordinal(Date(2032, 1, 1))
    while x < end do
      assertEquals(Date.ordinal(Date.ofOrdinal(x)), x); x += 1

  test("parts and assemble: a drawn point comes back as itself; every enumerated point too"):
    (1 to 500).foreach { _ =>
      val p = Units.random(rnd, Units)
      assertEquals(Units.assemble(Units, Units.parts(p)), Some(p), p.toString)
    }
    Units.enumerate(Units).foreach(p => assertEquals(Units.assemble(Units, Units.parts(p)), Some(p), p.toString))

  test("the space: counted; the drawn points it contains are answered by the engine"):
    val all = Units.enumerate(Units).toVector
    assertEquals(all.size, 886)
    all.foreach(p => assert(Units.run(p, Units).isRight, p.toString))

  test("every phrase carries its copied parts: the copier reads them back exactly"):
    (1 to 500).foreach { _ =>
      val p = Units.random(rnd, Units)
      val lang = if rnd.nextBoolean() then "en" else "ru"
      val q = Units.say(rnd, p, Units, lang)
      Units.slots(Units).filter(_.copied).foreach { s =>
        Units.parts(p).get(s.name).foreach(v => assertEquals(Units.copier.copy(q, Units, s), Some(v), q))
      }
    }

  /** a baseline reader: the slot's choice whose words appear in the question */
  object keywords extends Kit.Reader[Units.type]:
    private val ru = Map("second" -> "секунд", "minute" -> "минут", "hour" -> "час", "day" -> "дн", "week" -> "недел",
      "millimetre" -> "миллиметр", "centimetre" -> "сантиметр", "metre" -> "метр", "kilometre" -> "километр",
      "gram" -> "грамм", "kilogram" -> "килограмм", "tonne" -> "тонн", "byte" -> "байт", "kilobyte" -> "килобайт",
      "megabyte" -> "мегабайт", "gigabyte" -> "гигабайт", "month" -> "месяц")
    def read(q: String, s: Units.type, slot: Slot): Map[String, Double] =
      val lower = q.toLowerCase
      def at(u: String) = Vector(lower.indexOf(u), ru.get(u).fold(-1)(lower.indexOf)).filter(_ >= 0).minOption
      slot.name match
        case "kind" =>
          if lower.contains("between") || lower.contains("между") || lower.contains(" from ") || lower.contains(" с ")
          then Map("between" -> 1.0)
          else if """\d{4}-\d{2}-\d{2}""".r.findFirstIn(q).nonEmpty then Map("shift" -> 1.0) else Map("convert" -> 1.0)
        case "direction" =>
          if Vector("before", "minus", "до ", "минус").exists(lower.contains) then Map("before" -> 1.0)
          else Map("after" -> 1.0)
        case _ =>
          // the longest unit word found, by its first place: "to" is the one asked first in "how many X in…",
          // "from" the one beside the number
          val found = slot.choices.flatMap(u => at(u).map(i => u -> i))
          val clean = found.filterNot((u, i) => found.exists((v, j) => v != u && v.length > u.length && v.contains(u) &&
            j <= i && i < j + v.length + 4))
          val ordered = clean.sortBy(_._2).map(_._1)
          val number = """\b\d+\b""".r.findFirstMatchIn(lower).map(_.start).getOrElse(0)
          slot.name match
            case "from" =>
              clean.filter(_._2 > number).sortBy(_._2).headOption.map(u => Map(u._1 -> 1.0)).getOrElse(Map.empty)
            case "to" =>
              val beside = clean.filter(_._2 > number).sortBy(_._2).headOption.map(_._1)
              ordered.filterNot(beside.contains).headOption.map(u => Map(u -> 1.0)).getOrElse(Map.empty)
            case _ => ordered.headOption.map(u => Map(u -> 1.0)).getOrElse(Map.empty)

  test("the kit end to end: pairs, the reading assembled and validated, the measure by constructor"):
    val pairs = Kit.pairs(Units)(Units, 300, 11, Vector("en", "ru"))
    assertEquals(pairs.size, 300)
    val m = Kit.measure(Units)(Units, pairs, keywords, Units.copier)
    println(s"units, the keyword baseline: ${m.text}")
    assert(m.whole > 150, m.text)
    // with a margin no reading of a single sure choice abstains
    assertEquals(Kit.measure(Units)(Units, pairs, keywords, Units.copier, margin = 0.5).abstained, m.abstained)
