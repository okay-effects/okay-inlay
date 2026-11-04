package okay.inlay.web

import okay.inlay.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g

/** the closure in weights, in the browser: sentences in, the answer with the sentences it rests on and the cycles */
object Main:
  val names = Vector("Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal", "Ivy", "Jon", "Kim", "Lou")
  val above = Vector("manages", "oversees", "supervises", "is the manager of", "is above", "leads")
  val below = Vector("reports to", "works under", "answers to", "is managed by", "is supervised by")
  lazy val (program, _) = Closure.program(names, 40, 4)
  lazy val weights = Compile(program)

  /** a sentence by the known phrasings: (first, second, the first is the superior) */
  def parse(line: String): Option[(String, String, Boolean)] =
    val t = line.trim.stripSuffix(".")
    val pattern = "^(\\w+) (.+) (\\w+)$".r
    t match
      case pattern(x, verb, y) if names.contains(x) && names.contains(y) =>
        if above.contains(verb) then Some((x, y, true)) else if below.contains(verb) then Some((x, y, false)) else None
      case _ => None

  def run(text: String, qa: String, qb: String): String =
    val lines = text.split("\n").map(_.trim).filter(_.nonEmpty).toVector
    val parsed = lines.map(parse)
    val sentences = parsed.flatten
    if sentences.isEmpty then return "no sentence understood (use: Ann manages Bob. / Bob reports to Cid.)"
    val t0 = js.Date.now()
    val recs = Forward.run(program, weights, Closure.inputs(sentences, (qa, qb)))
    val ms = js.Date.now() - t0
    val last = recs.last
    val yes = last("yes") == Val.F(true)
    val on = last("on") match { case Val.M(s) => s.map(_.toInt).toVector.sorted; case _ => Vector.empty }
    val cycles = recs.init.zipWithIndex.filter(_._1("cycle") == Val.F(true)).map(_._2 + 1)
    val skipped = parsed.zipWithIndex.collect { case (None, i) => i + 1 }
    val numbered = sentences.zipWithIndex.map((s, i) => s"${i + 1}. ${lines(lines.indexWhere(l => parse(l).contains(s)))}")
    (if yes then s"$qa is above $qb — rests on sentences ${on.mkString(", ")}"
     else s"$qa is not above $qb by these sentences") +
      (if cycles.nonEmpty then s"\ncontradiction: sentences ${cycles.mkString(", ")} form a cycle" else "") +
      (if skipped.nonEmpty then s"\nnot understood (lines): ${skipped.mkString(", ")}" else "") +
      f"\n${sentences.size} sentences, ${program.layers.size} layers, ${weights.layout.dims} dims, $ms%.0f ms" +
      s"\n\n${numbered.mkString("\n")}"

  def main(args: Array[String]): Unit =
    val doc = g.document
    val text = doc.getElementById("text"); val qa = doc.getElementById("qa"); val qb = doc.getElementById("qb")
    val out = doc.getElementById("out")
    val go: js.Function0[Unit] = () =>
      out.textContent = run(text.value.asInstanceOf[String], qa.value.asInstanceOf[String].trim,
        qb.value.asInstanceOf[String].trim)
    doc.getElementById("go").onclick = go
    go()
