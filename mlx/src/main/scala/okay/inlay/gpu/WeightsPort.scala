package okay.inlay.gpu

import okay.inlay.{Compile, Program, Quotes, Val}

/**
 * EXACT QUOTES BY WEIGHTS (specs/dsl.md v1, specs/quotes.md stage b): mlx-scala's decode port answered by the quote
 * program compiled into weights and run on the GPU beside the model — not by Scala code. Per prompt: a compact
 * vocabulary (the prompt's tokens, the markers, "other" for any other), `Quotes.cheapBits` compiled for it; each step
 * the block runs over the tokens so far and its last position says inside or not, and the allowed tokens. The close
 * marker is always allowed, and a token that is a strict prefix of an allowed one (`prefixes`, a quote may end inside
 * a source token) — a fixed map, no state.
 */
final class WeightsPort(prompt: Array[Int], source: Range, open: Int, close: Int, k: Int,
    prefixes: Int => Array[Int] = _ => Array.empty, maxLen: Int = 1024) extends _root_.mlx.llm.Port:
  private val words = prompt.filter(t => t != open && t != close).distinct.sorted.map(_.toString).toVector
  val vocab: Vector[String] = words ++ Vector("[", "]", "other")
  val program: Program = Quotes.cheapBits(vocab, k, maxLen)
  private val gpu = OnMlx(Compile(program))
  private val tokens = scala.collection.mutable.ArrayBuffer.from(prompt)
  private val known = words.toSet
  /** what the block said at each step: (the tokens so far, inside, the allowed tokens) */
  val said = scala.collection.mutable.ArrayBuffer.empty[(Int, Boolean, Set[Int])]

  def name(t: Int): String =
    if t == open then "[" else if t == close then "]" else if known(t.toString) then t.toString else "other"

  def allowed: Option[Array[Int]] =
    val toks = tokens.toVector.map(name)
    val src = tokens.indices.map(source.contains).toVector
    val rec = gpu.last(program, Quotes.bitsInputs(vocab, toks, src))
    val inside = rec("inside") == Val.F(true)
    val ids = rec("allowed") match
      case Val.M(s) => s.collect { case t if t.forall(_.isDigit) => t.toInt }
      case _ => Set.empty[Int]
    said += ((tokens.length, inside, if inside then ids else Set.empty))
    if !inside then None
    else Some((ids.toArray ++ ids.toArray.flatMap(prefixes) :+ close).distinct)

  def took(token: Int): Unit = tokens += token

  def free(): Unit = gpu.close()
