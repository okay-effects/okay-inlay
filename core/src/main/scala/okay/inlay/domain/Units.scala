package okay.inlay.domain

/**
 * THE SECOND DOMAIN: UNITS AND THE CALENDAR (specs/domains.md §3). Points: a quantity converted within a dimension,
 * a date shifted by days / weeks / months, the days or weeks between two dates. Exact: rational arithmetic and the
 * Gregorian calendar by hand (no java.time: one code on the JVM and in the browser).
 */
object Units extends Domain:
  /** the domain needs no data: its schema is the units themselves */
  type Schema = Units.type
  type Answer = String

  enum Point:
    case Convert(quantity: Long, from: String, to: String)
    case Shift(date: Date, n: Int, unit: String, forward: Boolean)
    case Between(a: Date, b: Date, unit: String)

  val name = "units"

  /** a unit: its dimension and its size in the dimension's smallest unit; its words in English and Russian (nominative
   * singular, genitive singular, genitive plural, accusative plural) */
  final case class Unit(name: String, dimension: String, size: Long, ru: (String, String, String, String))
  val units: Vector[Unit] = Vector(
    Unit("second", "time", 1, ("секунда", "секунды", "секунд", "секунды")),
    Unit("minute", "time", 60, ("минута", "минуты", "минут", "минуты")),
    Unit("hour", "time", 3600, ("час", "часа", "часов", "часы")),
    Unit("day", "time", 86400, ("день", "дня", "дней", "дни")),
    Unit("week", "time", 604800, ("неделя", "недели", "недель", "недели")),
    Unit("millimetre", "length", 1, ("миллиметр", "миллиметра", "миллиметров", "миллиметры")),
    Unit("centimetre", "length", 10, ("сантиметр", "сантиметра", "сантиметров", "сантиметры")),
    Unit("metre", "length", 1000, ("метр", "метра", "метров", "метры")),
    Unit("kilometre", "length", 1000000, ("километр", "километра", "километров", "километры")),
    Unit("gram", "mass", 1, ("грамм", "грамма", "граммов", "граммы")),
    Unit("kilogram", "mass", 1000, ("килограмм", "килограмма", "килограммов", "килограммы")),
    Unit("tonne", "mass", 1000000, ("тонна", "тонны", "тонн", "тонны")),
    Unit("byte", "data", 1, ("байт", "байта", "байтов", "байты")),
    Unit("kilobyte", "data", 1024, ("килобайт", "килобайта", "килобайтов", "килобайты")),
    Unit("megabyte", "data", 1024L * 1024, ("мегабайт", "мегабайта", "мегабайтов", "мегабайты")),
    Unit("gigabyte", "data", 1024L * 1024 * 1024, ("гигабайт", "гигабайта", "гигабайтов", "гигабайты")))
  private val byName = units.map(u => u.name -> u).toMap
  val shiftUnits = Vector("day", "week", "month")
  val betweenUnits = Vector("day", "week")

  // ---- the calendar, by hand

  final case class Date(y: Int, m: Int, d: Int):
    override def toString: String = f"$y%04d-$m%02d-$d%02d"
  object Date:
    def leap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    def days(y: Int, m: Int): Int = m match
      case 2 => if leap(y) then 29 else 28
      case 4 | 6 | 9 | 11 => 30
      case _ => 31
    /** days since 0000-03-01, the proleptic Gregorian calendar (a civil-from-days inverse below) */
    def ordinal(x: Date): Long =
      val y = if x.m <= 2 then x.y - 1 else x.y
      val era = Math.floorDiv(y, 400); val yoe = y - era * 400
      val mp = (x.m + 9) % 12; val doy = (153 * mp + 2) / 5 + x.d - 1
      val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
      era.toLong * 146097 + doe
    def ofOrdinal(n: Long): Date =
      val era = Math.floorDiv(n, 146097L); val doe = n - era * 146097
      val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
      val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
      val d = (doy - (153 * mp + 2) / 5 + 1).toInt; val m = (if mp < 10 then mp + 3 else mp - 9).toInt
      Date((yoe + era * 400 + (if m <= 2 then 1 else 0)).toInt, m, d)
    def parse(s: String): Option[Date] = s match
      case s"$y-$m-$d" =>
        for { yy <- y.toIntOption; mm <- m.toIntOption; dd <- d.toIntOption
          if mm >= 1 && mm <= 12 && dd >= 1 && dd <= days(yy, mm) } yield Date(yy, mm, dd)
      case _ => None
    def addMonths(x: Date, n: Int): Date =
      val total = x.y * 12 + (x.m - 1) + n
      val y = Math.floorDiv(total, 12); val m = Math.floorMod(total, 12) + 1
      Date(y, m, math.min(x.d, days(y, m)))

  // ---- the exact engine

  /** a fraction in lowest terms, as an integer or a terminating decimal, else "a/b" */
  def exact(num: BigInt, den: BigInt): String =
    val g = num.gcd(den); val (a, b) = (num / g, den / g)
    if b == 1 then a.toString
    else
      var r = b; while r % 2 == 0 do r /= 2
      while r % 5 == 0 do r /= 5
      if r == 1 then (BigDecimal(a) / BigDecimal(b)).bigDecimal.stripTrailingZeros.toPlainString else s"$a/$b"

  def run(p: Point, s: Schema): Either[String, String] = p match
    case Point.Convert(q, from, to) => (byName.get(from), byName.get(to)) match
      case (Some(f), Some(t)) if f.dimension == t.dimension && f != t =>
        Right(exact(BigInt(q) * f.size, BigInt(t.size)))
      case (Some(_), Some(_)) => Left(s"$from and $to are not two units of one dimension")
      case _ => Left(s"unknown unit: $from or $to")
    case Point.Shift(date, n, unit, forward) =>
      val k = if forward then n else -n
      unit match
        case "day" => Right(Date.ofOrdinal(Date.ordinal(date) + k).toString)
        case "week" => Right(Date.ofOrdinal(Date.ordinal(date) + 7L * k).toString)
        case "month" => Right(Date.addMonths(date, k).toString)
        case u => Left(s"not a unit to shift by: $u")
    case Point.Between(a, b, unit) =>
      val days = math.abs(Date.ordinal(b) - Date.ordinal(a))
      unit match
        case "day" => Right(days.toString)
        case "week" => Right(exact(days, 7))
        case u => Left(s"not a unit to count by: $u")

  // ---- the space

  private val quantities = Vector(1L, 2, 3, 5, 10, 12, 24, 60, 100, 250, 1000)
  private val dates = Vector(Date(2026, 1, 31), Date(2026, 2, 28), Date(2026, 3, 1), Date(2024, 2, 29),
    Date(2026, 11, 7),
    Date(2025, 12, 25), Date(2026, 6, 15))
  private val ns = Vector(1, 2, 3, 7, 10, 30)

  def enumerate(s: Schema): Iterator[Point] =
    val convert = for
      f <- units.iterator; t <- units if t.dimension == f.dimension && t != f; q <- quantities
    yield Point.Convert(q, f.name, t.name)
    val shift =
      for d <- dates.iterator; n <- ns; u <- shiftUnits; fw <- Vector(true, false) yield Point.Shift(d, n, u, fw)
    val between = for a <- dates.iterator; b <- dates if a != b; u <- betweenUnits yield Point.Between(a, b, u)
    convert ++ shift ++ between

  def random(rnd: scala.util.Random, s: Schema): Point =
    def pick[A](xs: Vector[A]): A = xs(rnd.nextInt(xs.size))
    def date = Date.ofOrdinal(Date.ordinal(Date(2020, 1, 1)) + rnd.nextInt(3000))
    rnd.nextInt(5) match
      case 0 | 1 | 2 =>
        val f = pick(units); val t = pick(units.filter(u => u.dimension == f.dimension && u != f))
        Point.Convert(pick(quantities) + (if rnd.nextBoolean() then 0 else rnd.nextInt(900)), f.name, t.name)
      case 3 => Point.Shift(date, 1 + rnd.nextInt(40), pick(shiftUnits), rnd.nextBoolean())
      case _ => Point.Between(date, date, pick(betweenUnits))

  // ---- the slots

  def slots(s: Schema): Vector[Slot] = Vector(
    Slot("kind", Vector("convert", "shift", "between")),
    Slot("from", units.map(_.name)), Slot("to", units.map(_.name)),
    Slot("shift_unit", shiftUnits), Slot("direction", Vector("after", "before")), Slot("between_unit", betweenUnits),
    Slot("quantity", Vector.empty, copied = true), Slot("date1", Vector.empty, copied = true),
    Slot("date2", Vector.empty, copied = true), Slot("n", Vector.empty, copied = true))

  def parts(p: Point): Map[String, String] = p match
    case Point.Convert(q, f, t) => Map("kind" -> "convert", "from" -> f, "to" -> t, "quantity" -> q.toString)
    case Point.Shift(d, n, u, fw) =>
      Map("kind" -> "shift", "shift_unit" -> u, "direction" -> (if fw then "after" else "before"),
        "date1" -> d.toString,
        "n" -> n.toString)
    case Point.Between(a, b, u) =>
      Map("kind" -> "between", "between_unit" -> u, "date1" -> a.toString, "date2" -> b.toString)

  def assemble(s: Schema, ps: Map[String, String]): Option[Point] = ps.get("kind") match
    case Some("convert") =>
      for { q <- ps.get("quantity").flatMap(_.toLongOption); f <- ps.get("from"); t <- ps.get("to")
        p = Point.Convert(q, f, t) if run(p, s).isRight } yield p
    case Some("shift") =>
      for { d <- ps.get("date1").flatMap(Date.parse); n <- ps.get("n").flatMap(_.toIntOption); u <- ps.get("shift_unit")
        dir <- ps.get("direction") if dir == "after" || dir == "before" } yield Point.Shift(d, n, u, dir == "after")
    case Some("between") =>
      for { a <- ps.get("date1").flatMap(Date.parse); b <- ps.get("date2").flatMap(Date.parse)
        u <- ps.get("between_unit") } yield Point.Between(a, b, u)
    case _ => None

  // ---- the phrases

  /** a unit's English word for a count */
  private def en(u: String, n: Long): String = if n == 1 then u else u + "s"
  /** a unit's Russian word for a count: 1 → nominative singular, 2–4 → genitive singular, else genitive plural */
  private def ru(u: String, n: Long): String =
    val (nom, gen, genPl, _) = byName.get(u).map(_.ru).getOrElse(ruTime(u))
    val last2 = n % 100; val last = n % 10
    if last2 >= 11 && last2 <= 14 then genPl else if last == 1 then nom
    else if last >= 2 && last <= 4 then gen else genPl
  private def ruTime(u: String): (String, String, String, String) = u match
    case "month" => ("месяц", "месяца", "месяцев", "месяцы")
    case other => byName(other).ru

  def say(rnd: scala.util.Random, p: Point, s: Schema, language: String): String =
    def pick(xs: String*): String = xs(rnd.nextInt(xs.size))
    val russian = language == "ru"
    p match
      case Point.Convert(q, f, t) =>
        if russian then pick(s"Сколько ${ruTime(t)._3} в $q ${ru(f, q)}?",
          s"Переведи $q ${ru(f, q)} в ${ruTime(t)._4}.",
          s"$q ${ru(f, q)} — это сколько ${ruTime(t)._3}?")
        else pick(s"How many ${t}s are in $q ${en(f, q)}?", s"Convert $q ${en(f, q)} to ${t}s.",
          s"$q ${en(f, q)} in ${t}s?")
      case Point.Shift(d, n, u, fw) =>
        if russian then
          if fw then pick(s"Какая дата будет через $n ${ru(u, n)} после $d?", s"$d плюс $n ${ru(u, n)} — какое число?")
          else pick(s"Какая дата была за $n ${ru(u, n)} до $d?", s"$d минус $n ${ru(u, n)} — какое число?")
        else if fw then pick(s"What date is $n ${en(u, n)} after $d?", s"$d plus $n ${en(u, n)}?")
        else pick(s"What date is $n ${en(u, n)} before $d?", s"$d minus $n ${en(u, n)}?")
      case Point.Between(a, b, u) =>
        if russian then pick(s"Сколько ${ruTime(u)._3} между $a и $b?", s"Сколько ${ruTime(u)._3} прошло с $a по $b?")
        else pick(s"How many ${u}s are between $a and $b?", s"How many ${u}s from $a to $b?")

  /** the copied slots read exactly: dates in ISO form in order; numbers not inside a date */
  object copier extends Kit.Copier[Schema]:
    private val dateRe = """\b\d{4}-\d{2}-\d{2}\b""".r
    private val numRe = """\b\d+\b""".r
    def copy(question: String, schema: Schema, slot: Slot): Option[String] =
      val ds = dateRe.findAllIn(question).toVector
      val rest = dateRe.replaceAllIn(question, " ")
      slot.name match
        case "date1" => ds.headOption
        case "date2" => ds.lift(1)
        case "quantity" | "n" => numRe.findFirstIn(rest)
        case _ => None
