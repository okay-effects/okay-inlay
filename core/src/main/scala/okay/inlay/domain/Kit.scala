package okay.inlay.domain

import okay.frames
import okay.frames.{Hole, Space}
import SlotSpace.Unused

/**
 * THE KIT (specs/domains.md §2): what a domain gets by declaring its space — pairs (the space × the phrases), reading
 * a question with abstention, the measure by constructor. A `Reader` is whatever gives, per slot, a distribution over
 * the slot's choices (a model's probe on the bus, a rule set for a baseline); a copied slot is read by the domain's
 * `Copier` from the text.
 *
 * The reading is okay-frames' (specs/domains.md "On okay-frames"): a domain is a `Space` (`space`), a reader a
 * weigher of a hole's options (`weigher`), a question read is a `frames.Reading` driven to its end (`reading`).
 * `read` and `measure` say that reading in the kit's older words, with the numbers they always gave.
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

  /** THE DOMAIN AS A SPACE of okay-frames: its slots asked in order, a copied slot's option read by `copier` */
  def space(d: Domain)(copier: Copier[d.Schema]): Space[d.Schema, d.Point] =
    new SlotSpace[d.Schema, d.Point](d, copier)

  /** A READER AS A WEIGHER of a hole's options, for a `frames.Reading` of the domain's space. A copied slot is not
   * the reader's: what the text writes is taken (the first option) with no rival. A closed slot: the reader's
   * scores for the options it names (a name outside the options is ignored); with one option named the others
   * score 0, as the kit always compared a lone choice with 0; with several, the others cannot win; with none the
   * slot is unused, with no rival */
  def weigher(d: Domain)(reader: Reader[d.Schema])
      : (frames.Reading[d.Schema, d.Point], Hole) => Vector[Double] = (r, hole) =>
    def only(option: String) = hole.options.map(o => if o == option then 0.0 else Double.NegativeInfinity)
    val slot = d.slots(r.data)(r.chosen.size)
    if slot.copied then only(hole.options.head)
    else
      val read = reader.read(r.text, r.data, slot).filter((c, _) => hole.options.contains(c))
      if read.isEmpty then only(Unused)
      else
        val unnamed = if read.size == 1 then 0.0 else Double.NegativeInfinity
        hole.options.map(read.getOrElse(_, unnamed))

  /** a question read to its end: okay-frames' reading of the domain's space, weighed by the reader */
  def reading(d: Domain)(schema: d.Schema, question: String, reader: Reader[d.Schema], copier: Copier[d.Schema])
      : frames.Reading[d.Schema, d.Point] =
    frames.Reading.run(space(d)(copier))(schema, question)(weigher(d)(reader))

  /** A READING: per slot the best choice and its margin over the second; a slot under `margin` abstains */
  final case class Reading[P](parts: Map[String, String], margins: Map[String, Double], abstained: Vector[String],
    point: Option[P]):
    def answered: Boolean = abstained.isEmpty && point.nonEmpty

  def read(d: Domain)(schema: d.Schema, question: String, reader: Reader[d.Schema], copier: Copier[d.Schema],
    margin: Double = 0.0): Reading[d.Point] =
    val r = reading(d)(schema, question, reader, copier)
    // a slot nobody read (no score, nothing copied) is unused with no rival: it has no margin to speak of
    val read = r.chosen.zip(r.margins).filterNot((c, m) => c.option == Unused && m.isPosInfinity)
    // a slot read as "-" is not used by the point; the domain's assembler decides what a point needs
    val parts = read.collect { case (c, _) if c.option != Unused => c.hole -> c.option }.toMap
    val margins = read.map((c, m) => c.hole -> m).toMap
    val abstained = read.collect { case (c, m) if c.option != Unused && m < margin => c.hole }
    Reading(parts, margins, abstained, r.meaning)

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

  /** okay-frames' OWN MEASURE of the same reader on the same pairs. Its totals are the kit's (right = whole) but
   * for one rule: it asks about any choice under the bar, an unused slot among them, where the kit abstains only
   * on a slot the reading uses. Its table by hole is another count than `Measure.bySlot` — a hole is counted only
   * while the reading is on the meant path, the right count for holes that depend on each other, not for
   * independent slots — so the kit keeps its own */
  def framed(d: Domain)(schema: d.Schema, pairs: Vector[Pair[d.Point]], reader: Reader[d.Schema],
    copier: Copier[d.Schema], bar: Double = 0.0): frames.Measure =
    frames.Measure.of(space(d)(copier))(schema, pairs.map(p => p.question -> p.point), bar)(weigher(d)(reader))
