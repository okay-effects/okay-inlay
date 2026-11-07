package okay.inlay.web

import okay.effects.*
import okay.inlay.bridge.{Bridge, Deduce, Judge}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g

/** THE BRIDGE IN THE BROWSER: the same program as on the JVM (`Bridge.above`), run in one line with the page's
 * handlers — the rules over the known phrasings as the reader, the compiled closure (`Forward` on Scala.js) as the
 * deducer. Sentences in; the answer with the sentences it rests on, the cycles, the lines not understood */
object Main:
  type Row[X] = (Judge + Deduce)[X]
  val block: Bridge.Block = Bridge.block

  def run(text: String, qa: String, qb: String): String =
    val lines = text.split("\n").map(_.trim).filter(_.nonEmpty).toVector
    val t0 = js.Date.now()
    val said = okay.effects.run(Bridge.reading(Bridge.phrasings(_))(Bridge.deducing(block.forward)(
      Bridge.above[Row](lines, (qa, qb)))))
    val ms = js.Date.now() - t0
    val read = lines.size - said.unread.size
    if read == 0 then return "no sentence understood (use: Ann manages Bob. / Bob reports to Cid.)"
    def numbers(s: Set[Int]) = s.toVector.sorted.map(_ + 1).mkString(", ")
    val a = said.answer
    (if a.yes then s"$qa is above $qb — rests on lines ${numbers(a.restsOn)}"
     else s"$qa is not above $qb by these sentences") +
      (if a.onCycle.nonEmpty then s"\ncontradiction: lines ${numbers(a.onCycle)} form a cycle" else "") +
      (if said.unread.nonEmpty then s"\nnot understood (lines): ${numbers(said.unread.toSet)}" else "") +
      f"\n$read sentences, ${block.program.layers.size} layers, ${block.weights.layout.dims} dims, $ms%.0f ms" +
      s"\n\n${lines.zipWithIndex.map((l, i) => s"${i + 1}. $l").mkString("\n")}"

  def main(args: Array[String]): Unit =
    val doc = g.document
    val text = doc.getElementById("text"); val qa = doc.getElementById("qa"); val qb = doc.getElementById("qb")
    val out = doc.getElementById("out")
    val go: js.Function0[Unit] = () =>
      out.textContent = run(text.value.asInstanceOf[String], qa.value.asInstanceOf[String].trim,
        qb.value.asInstanceOf[String].trim)
    doc.getElementById("go").onclick = go
    go()
