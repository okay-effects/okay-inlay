package okay.inlay.domain

import okay.frames.{Choice, Hole, Space}

/**
 * A DOMAIN AS A SPACE OF okay-frames (specs/domains.md "On okay-frames"): a `Domain`'s slots are independent, so
 * the space asks them one after another in `slots(schema)` order, whatever was chosen before. A slot with a closed
 * set offers its choices and `Unused` ("-": the point does not use the slot); a copied slot offers what the
 * domain's `Copier` reads from the text, and `Unused`. A term is the domain's point, assembled and accepted by the
 * exact engine.
 *
 * `Domain.say` words a point in many ways for training pairs and has no inverse; the CANONICAL words here are the
 * point's parts written plainly, `slot=value; slot=value` in the slots' order, the same in every language, and
 * `parse` is their exact inverse.
 */
final class SlotSpace[S, P](val domain: Domain { type Schema = S; type Point = P }, copier: Kit.Copier[S])
    extends Space[S, P]:
  import SlotSpace.Unused

  def name: String = domain.name

  def next(data: S, text: String, chosen: Vector[Choice]): Option[Hole] =
    domain.slots(data).lift(chosen.size).map { slot =>
      val options =
        if slot.copied then copier.copy(text, data, slot).filter(_ != Unused).toVector :+ Unused
        else if slot.choices.contains(Unused) then slot.choices else slot.choices :+ Unused
      Hole(slot.name, options)
    }

  def assemble(data: S, chosen: Vector[Choice]): Option[P] =
    val parts = chosen.collect { case Choice(slot, option) if option != Unused => slot -> option }.toMap
    domain.assemble(data, parts).filter(p => domain.run(p, data).isRight)

  def path(data: S, t: P): Vector[Choice] =
    val parts = domain.parts(t)
    domain.slots(data).map(slot => Choice(slot.name, parts.getOrElse(slot.name, Unused)))

  def say(data: S, t: P, language: String): String =
    val parts = domain.parts(t)
    domain.slots(data).flatMap(slot => parts.get(slot.name).map(v => s"${escape(slot.name)}=${escape(v)}"))
      .mkString("; ")

  /** the point of canonical words: read back, assembled, and accepted only if it says exactly these words */
  def parse(data: S, words: String, language: String): Option[P] =
    domain.assemble(data, unsay(words)).filter(p => say(data, p, language) == words)

  private def escape(s: String): String = s.flatMap(c => if c == '\\' || c == ';' || c == '=' then s"\\$c" else s"$c")

  /** `slot=value; …` back to slot → value: a backslash keeps the next character, `=` ends a name, `;` an item */
  private def unsay(words: String): Map[String, String] =
    val items = Vector.newBuilder[(String, String)]
    val name = new StringBuilder; val value = new StringBuilder
    var inValue = false
    var i = 0
    def end(): Unit =
      if inValue then items += name.toString -> value.toString
      name.clear(); value.clear(); inValue = false
    while i < words.length do
      val c = words(i)
      if c == '\\' && i + 1 < words.length then
        (if inValue then value else name) += words(i + 1); i += 1
      else if c == '=' && !inValue then inValue = true
      else if c == ';' then
        end()
        if i + 1 < words.length && words(i + 1) == ' ' then i += 1
      else (if inValue then value else name) += c
      i += 1
    end()
    items.result().toMap

object SlotSpace:
  /** how a slot the point does not use is read (the kit's convention) */
  val Unused = "-"
