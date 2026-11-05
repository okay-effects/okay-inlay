package okay.inlay.forge

import _root_.mlx.llm.{Adaptable, Chat, Lora, Session}

/** a project and what verifies against it: its tasks, the verifier, the name the prompt gives it */
final case class Source(name: String, project: Project, verify: Verify)

/**
 * THE PIPELINE'S STEPS (specs/forge.md "The tool"): the prompt as an agent gives it (the member's declaration and
 * doc, its real calls, the rule "call, do not reimplement, no object of your own"); answers generated (greedy, no
 * thinking, one round); answers judged by the verifier; examples trained on.
 */
final class Forge(model: Adaptable, chat: Chat):
  private val tok = chat.tokenizer
  private val end = tok.id("<|im_end|>")

  def prompt(src: Source, t: Task): Array[Int] =
    val m = t.member
    val decl = src.project.declaration(m).fold("")((s, d) => s"  $s" + (if d.nonEmpty then s"   // $d" else ""))
    val calls = src.project.calls(m)
    chat.ask(s"You write Scala 3 for the project ${src.name} (package `${m.pkg}`, already imported). " +
      s"Write `${t.signature}`: ${t.text}\n\nThe API it needs:\n$decl\n" +
      (if calls.nonEmpty then s"\nHow ${src.name} calls it:\n${calls.mkString("\n")}\n" else "") +
      "\nYour definition goes in your own code, outside the project: call its members, do not reimplement them, and " +
      "declare no object or class. Reply with one ```scala code block holding only the definition, no explanation.",
      Map("enable_thinking" -> false))

  /** a meaning task's prompt: every member it uses, declared and called as the project does */
  def meaning(src: Source, mt: MeaningTask): Array[Int] =
    val ms = mt.uses.flatMap((o, n) => src.project.members.find(m => m.owner == o && m.name == n))
    val decls = ms.flatMap(m =>
      src.project.declaration(m).map((s, d) => s"  $s" + (if d.nonEmpty then s"   // $d" else "")))
    val calls = ms.flatMap(m => src.project.calls(m, 2)).distinct
    chat.ask(s"You write Scala 3 for the project ${src.name} (package `${mt.task.member.pkg}`, already imported). " +
      s"Write `${mt.task.signature}`: ${mt.task.text}.\n\nThe API it needs:\n${decls.mkString("\n")}\n" +
      (if calls.nonEmpty then s"\nHow ${src.name} calls it:\n${calls.mkString("\n")}\n" else "") +
      "\nYour definition goes in your own code, outside the project: call its members, do not reimplement them, and " +
      "declare no object or class. Reply with one ```scala code block holding only the definition, no explanation.",
      Map("enable_thinking" -> false))

  /** meaning tasks answered and judged */
  def meaningAttempts(src: Source, mts: Vector[MeaningTask]): Vector[(MeaningTask, String, Verdict)] =
    val codes = mts.map { mt =>
      val s = Session(model, meaning(src, mt), 400, chat.stops)
      try code(tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector))
      finally s.close()
    }
    val verdicts = src.verify(mts.map(_.task).zip(codes))
    mts.indices.map(i => (mts(i), codes(i), verdicts(i))).toVector

  /** a general task's prompt: no project, no API */
  def general(t: Task): Array[Int] =
    chat.ask(s"Write `${t.signature}` in Scala 3: ${t.text}. Reply with one ```scala code block holding only the " +
      "definition, no explanation.", Map("enable_thinking" -> false))

  def code(answer: String): String =
    val a = answer.split("</think>").last
    "(?s)```(?:scala)?\\n(.*?)(```|$)".r.findFirstMatchIn(a).map(_.group(1)).getOrElse(a).linesIterator
      .filterNot(_.trim.startsWith("package ")).mkString("\n").trim

  def answer(src: Source, t: Task, maxTokens: Int = 400): String =
    val s = Session(model, if t.kind == "general" then general(t) else prompt(src, t), maxTokens, chat.stops)
    try tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector)
    finally s.close()

  /** each task answered and judged: (task, code, verdict) */
  def attempts(src: Source, tasks: Vector[Task]): Vector[(Task, String, Verdict)] =
    val codes = tasks.map(t => code(answer(src, t)))
    val verdicts = src.verify(tasks.zip(codes))
    tasks.indices.map(i => (tasks(i), codes(i), verdicts(i))).toVector

  /** a training example: the prompt's tokens and the answer's (a code block, then the turn's end) */
  def example(src: Source, t: Task, code: String): (Array[Int], Int) =
    val p = prompt(src, t)
    (p ++ tok.encode(s"```scala\n$code\n```") :+ end, p.length)

  /** epochs of AdamW over the examples, shuffled; the mean loss of each */
  def train(lora: Lora, examples: Vector[(Array[Int], Int)], epochs: Int, seed: Long = 3): Vector[Float] =
    val r = scala.util.Random(seed)
    (1 to epochs).map { _ =>
      val ls = r.shuffle(examples).map((ids, from) => lora.step(ids, from))
      ls.sum / ls.size
    }.toVector
