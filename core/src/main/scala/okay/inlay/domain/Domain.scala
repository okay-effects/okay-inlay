package okay.inlay.domain

/**
 * A DOMAIN OF MEANINGS (specs/domains.md): what questions about a world can mean, as an enumerable space of points
 * with an exact engine. A domain declares the space; the kit (`Kit`) gives pairs, reading with abstention and the
 * measure by constructor. Tables are one domain, units and the calendar another; the readers that read a domain off
 * a model's states are the domain's, the machinery is shared.
 */
trait Domain:
  /** the world the questions are about */
  type Schema
  /** a meaning: a program of the space, well-formed by construction */
  type Point
  /** what the exact engine answers */
  type Answer

  def name: String

  /** the space, bounded and lazy */
  def enumerate(s: Schema): Iterator[Point]
  /** a point drawn by the space's structure */
  def random(rnd: scala.util.Random, s: Schema): Point
  /** THE EXACT ENGINE: the answer, or why there is none — nothing else computes */
  def run(p: Point, s: Schema): Either[String, Answer]
  /** a point said in a language ("en", "ru"), one of several wordings */
  def say(rnd: scala.util.Random, p: Point, s: Schema, language: String): String

  /** the constructors a point is made of, over this schema */
  def slots(s: Schema): Vector[Slot]
  /** the point as its constructors' choices (a slot it does not use is absent) */
  def parts(p: Point): Map[String, String]
  /** constructors' choices back to a point; none when they do not make a well-formed one */
  def assemble(s: Schema, parts: Map[String, String]): Option[Point]

  /** whether an answer says something (a pair is kept when it does) */
  def useful(a: Answer): Boolean = true

/** A CONSTRUCTOR of a domain's points: its choices over the schema (a closed set), or copied from the question's text
 * (a number, a date, a name: read exactly, never guessed) */
final case class Slot(name: String, choices: Vector[String], copied: Boolean = false)

/** a question and its meaning, in one language */
final case class Pair[P](question: String, point: P, language: String)
