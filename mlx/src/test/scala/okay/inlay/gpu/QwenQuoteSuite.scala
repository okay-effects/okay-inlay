package okay.inlay.gpu

import _root_.mlx.llm.{Chat, Model, Models, Quote, Session}
import okay.inlay.Quotes

/** THE BLOCK BESIDE A REAL MODEL: Qwen3-4B generating under the quote program's weights — at every step the block's
 * allowed set equals QuoteK on the tokens so far, and every quote is in the source */
class QwenQuoteSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  private val log =
    """2026-11-04 14:01:58 INFO  checkout: order 81731 paid (PLN 129.00)
      |2026-11-04 14:02:03 WARN  payments: slow response 4200 ms (order 81733)
      |2026-11-04 14:02:07 ERROR payments: timeout after 30000 ms (order 81734)
      |2026-11-04 14:02:09 INFO  checkout: order 81735 paid (PLN 54.50)
      |2026-11-04 14:02:15 ERROR inventory: stock lock lost for sku ZX-4471-B (order 81736)""".stripMargin

  test("Qwen3-4B under the weights: the allowed set is QuoteK's at every step; quotes are in the log"):
    val dir = Models.snapshot("mlx-community/Qwen3-4B-4bit")
    assume(dir.isDefined, "Qwen3-4B is not in the HuggingFace cache")
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true)
    val tok = chat.tokenizer
    val (open, close) = (tok.encode("⟦").head, tok.encode("⟧").head)
    val prompt = chat.ask("Answer from this log only. Put every log line, order number and amount you mention " +
      s"inside ⟦ ⟧, copied exactly.\n\n$log\n\nWhich orders failed, and why? Answer in two sentences.",
      Map("enable_thinking" -> false))
    // the log's first and last tokens merge with what is around it in the prompt: its middle, found there
    val logToks = tok.encode(log).drop(1).dropRight(1)
    val at = prompt.indexOfSlice(logToks)
    assert(at >= 0, "the log's tokens in the prompt")
    val k = 16
    val port = WeightsPort(prompt, at until at + logToks.length, open, close, k, Quote.prefixes(tok))
    println(s"the block: ${port.vocab.size} words, ${port.program.layers.map(_.heads.size).sum} heads")
    val t0 = System.nanoTime
    val s = Session(model, prompt, 160, chat.stops, port = port)
    val out = Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    s.close()
    val secs = (System.nanoTime - t0) / 1e9
    println(f"held by weights (${out.length} tokens, $secs%.1f s): ${tok.decode(out)}")
    // the law: each step's set, against QuoteK on the same tokens
    val all = (prompt ++ out).toVector
    val names = all.map(port.name)
    val src = all.indices.map(i => i >= at && i < at + logToks.length).toVector
    val ref = Quotes.reference(names, src, k)
    for (n, inside, ids) <- port.said do
      val (rIn, rAllowed) = ref(n - 1)
      assertEquals(inside, rIn, s"inside at ${n - 1}")
      assertEquals(ids.map(_.toString), rAllowed, s"allowed at ${n - 1}")
    val steps = port.said.count(_._2)
    println(s"${port.said.size} steps, $steps inside a quote, each equal to QuoteK")
    val quoted = "⟦([^⟧]*)⟧?".r.findAllMatchIn(tok.decode(out)).map(_.group(1)).toVector
    assert(quoted.nonEmpty, "nothing quoted")
    quoted.foreach(q => assert(log.contains(q), s"not in the log: $q"))
    port.free()
    model.close()
