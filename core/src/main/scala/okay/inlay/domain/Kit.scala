package okay.inlay.domain

/**
 * THE KIT (specs/domains.md §2): what a domain gets by declaring its space — pairs (the space × the phrases), reading
 * a question with abstention, the measure by constructor. A `Reader` is whatever gives, per slot, a distribution over
 * the slot's choices (a model's probe on the bus, a rule set for a baseline); a copied slot is read by the domain's
 * `Copier` from the text.
 */
object Kit:
  /** per slot, a probability for each choice (they need not sum to one; the best two are compared) */
  trait Reader[S]:
    def read(question: String, schema: S, slot: Slot): Map[String, Double]

  /** a copied slot's value read exactly from the text, or none */
  trait Copier[S]:
    def copy(question: String, schema: S, slot: Slot): Option[String]

  /** pairs from the space: points drawn, said, kept when the engine's answer says something */
  def pairs(d: Domain)(schema: d.Schema, n: Int, seed: Long, languages: Vector[String]): Vector[Pair[d.Point]] =
    val rnd = scala.util.Random(seed)
    Iterator.continually {
      val p = d.random(rnd, schema)
      val lang = languages(rnd.nextInt(languages.size))
      Option.when(d.run(p, schema).exists(d.useful))(Pair(d.say(rnd, p, schema, lang), p, lang))
    }.flatten.take(n).toVector

  /** A READING: per slot the best choice and its margin over the second; a slot under `margin` abstains */
  final case class Reading[P](parts: Map[String, String], margins: Map[String, Double], abstained: Vector[String],
    point: Option[P]):
    def answered: Boolean = abstained.isEmpty && point.nonEmpty

  def read(d: Domain)(schema: d.Schema, question: String, reader: Reader[d.Schema], copier: Copier[d.Schema],
    margin: Double = 0.0): Reading[d.Point] =
    val read = d.slots(schema).flatMap { slot =>
      if slot.copied then copier.copy(question, schema, slot).map(v => (slot.name, v, Double.PositiveInfinity))
      else
        val ps = reader.read(question, schema, slot).toVector.sortBy(-_._2)
        ps.headOption.map((best, p) => (slot.name, best, p - ps.lift(1).fold(0.0)(_._2)))
    }
    // a slot read as "-" is not used by the point; the domain's assembler decides what a point needs
    val parts = read.collect { case (s, v, _) if v != "-" => s -> v }.toMap
    val margins = read.map((s, _, m) => s -> m).toMap
    val point = d.assemble(schema, parts).filter(p => d.run(p, schema).isRight)
    val abstained = read.collect { case (s, v, m) if v != "-" && m < margin => s }
    Reading(parts, margins, abstained, point)

  /** THE MEASURE BY CONSTRUCTOR: per slot right of the pairs that use it; the whole point; abstained; wrong above
   * the threshold (answered and wrong — the number exactness rests on) */
  final case class Measure(n: Int, bySlot: Map[String, (Int, Int)], whole: Int, abstained: Int, unread: Int,
    wrongAnswered: Int):
    def text: String =
      val slots = bySlot.toVector.sortBy(_._1).map { case (s, (r, t)) => f"$s ${100.0 * r / t}%.0f%% ($r/$t)" }
      f"$n pairs: whole $whole (${100.0 * whole / n}%.0f%%), abstained $abstained, unread $unread, " +
        "wrong when answered " +
        f"$wrongAnswered" +
        f" (${100.0 * wrongAnswered / n}%.1f%%); by slot: ${slots.mkString(", ")}"

  def measure(d: Domain)(schema: d.Schema, pairs: Vector[Pair[d.Point]], reader: Reader[d.Schema],
    copier: Copier[d.Schema], margin: Double = 0.0): Measure =
    val bySlot = scala.collection.mutable.Map.empty[String, (Int, Int)].withDefaultValue((0, 0))
    var whole, abstained, unread, wrong = 0
    pairs.foreach { pair =>
      val r = read(d)(schema, pair.question, reader, copier, margin)
      val gold = d.parts(pair.point)
      d.slots(schema).foreach { slot =>
        val g = gold.getOrElse(slot.name, "-"); val got = r.parts.getOrElse(slot.name, "-")
        // a slot measured on the pairs whose point uses it
        if gold.contains(slot.name) then
          val (right, total) = bySlot(slot.name)
          bySlot(slot.name) = (right + (if got == g then 1 else 0), total + 1)
      }
      if r.abstained.nonEmpty then abstained += 1
      else if r.point.isEmpty then unread += 1
      else if r.point.contains(pair.point) then whole += 1
      else wrong += 1
    }
    Measure(pairs.size, bySlot.toMap, whole, abstained, unread, wrong)
