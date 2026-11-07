package okay.inlay.bridge

import okay.effects.*
import okay.inlay.{Closure, Compile, Forward, Interpret, Program, Val, Weights}

/**
 * THE BRIDGE AS A PROGRAM OF EFFECTS (specs/bridge.md §9, docs/effects.md): reading and deducing are effects; the
 * bridge is one program over them; who reads (rules over the words, a probe on a model's states, the model asked,
 * the gold) and who deduces (plain search, the DSL's reference, the compiled weights on the CPU or a GPU) are
 * handlers — chosen in one line, the program unchanged. The law: every deducing handler gives the same answer.
 */

/** a sentence read: who is above whom (`firstIsSuperior`: "first manages second") */
final case class Edge(first: String, second: String, firstIsSuperior: Boolean):
  def asTriple: (String, String, Boolean) = (first, second, firstIsSuperior)

/** an answer: whether a is above b; the sentences it rests on and those on a cycle (indices into what was deduced
 * over — the bridge maps them back to the text's lines) */
final case class Answer(yes: Boolean, restsOn: Set[Int], onCycle: Set[Int])

/** READING: a sentence's relation, if the sentence states one */
enum Judge[X]:
  case Read(sentence: String) extends Judge[Option[Edge]]

/** DEDUCING: is a above b by these edges — with what it rests on and the cycles */
enum Deduce[X]:
  case Above(edges: Vector[Edge], question: (String, String)) extends Deduce[Answer]

object Bridge:
  def judge[R[_]: Has[Judge]](sentence: String): Option[Edge] ! R = perform[Judge](Judge.Read(sentence))
  def deduce[R[_]: Has[Deduce]](edges: Vector[Edge], question: (String, String)): Answer ! R =
    perform[Deduce](Deduce.Above(edges, question))

  /** what the bridge says about a text: the answer (its indices the text's lines) and the lines not understood */
  final case class Said(answer: Answer, unread: Vector[Int])

  /** THE PROGRAM: every line read, the edges deduced over, the answer mapped back to the lines */
  def above[R[_]: Has[Judge]: Has[Deduce]](text: Vector[String], question: (String, String)): Said ! R =
    def readAll(i: Int, acc: Vector[Option[Edge]]): Vector[Option[Edge]] ! R =
      if i == text.size then pure(acc) else judge[R](text(i)).flatMap(e => readAll(i + 1, acc :+ e))
    for
      read <- readAll(0, Vector.empty)
      kept = read.zipWithIndex.collect { case (Some(e), line) => (e, line) }
      a <- deduce[R](kept.map(_._1), question)
    yield Said(Answer(a.yes, a.restsOn.map(kept(_)._2), a.onCycle.map(kept(_)._2)),
      read.zipWithIndex.collect { case (None, line) => line })

  // ---- the readers

  /** READ BY A FUNCTION: rules, a probe, a model asked — any `String => Option[Edge]` */
  def reading[R[_], Rest[_], A](read: String => Option[Edge])(p: A ! R)(using Split[Judge, R, Rest]): A ! Rest =
    handle(p, new TailHandler[Judge, Rest, A, A]:
      def ret(a: A) = pure(a)
      def answer[X](op: Judge[X]): X = op match
        case Judge.Read(s) => read(s))

  /** the known phrasings over the known names: "Ann manages Bob", "Bob reports to Ann" */
  final case class Phrasings(names: Vector[String], above: Vector[String], below: Vector[String]):
    private val pattern = "^(\\w+) (.+) (\\w+)$".r
    def apply(line: String): Option[Edge] = line.trim.stripSuffix(".") match
      case pattern(x, verb, y) if names.contains(x) && names.contains(y) =>
        if above.contains(verb) then Some(Edge(x, y, true)) else if below.contains(verb) then Some(Edge(x, y, false))
        else None
      case _ => None

  val names: Vector[String] = Vector("Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal", "Ivy", "Jon", "Kim", "Lou")
  val phrasings: Phrasings = Phrasings(names,
    Vector("manages", "oversees", "supervises", "is the manager of", "is above", "leads"),
    Vector("reports to", "works under", "answers to", "is managed by", "is supervised by"))

  // ---- the deducers

  /** DEDUCED BY A FUNCTION over the edges */
  def deducing[R[_], Rest[_], A](by: (Vector[Edge], (String, String)) => Answer)(p: A ! R)(
      using Split[Deduce, R, Rest]): A ! Rest =
    handle(p, new TailHandler[Deduce, Rest, A, A]:
      def ret(a: A) = pure(a)
      def answer[X](op: Deduce[X]): X = op match
        case Deduce.Above(edges, q) => by(edges, q))

  /** PLAIN SEARCH (`Closure.reference`) */
  def search(edges: Vector[Edge], q: (String, String)): Answer =
    val (yes, used, cycle) = Closure.reference(edges.map(_.asTriple), q)
    Answer(yes, used.map(_ - 1), cycle.map(_ - 1))

  /** THE CLOSURE PROGRAM, for these names, up to `len` positions, 2^rounds hops: its reference and its weights */
  final class Block(val names: Vector[String], val len: Int, val rounds: Int):
    lazy val program: Program = Closure.program(names, len, rounds)._1
    lazy val weights: Weights = Compile(program)
    /** the answer read from the closure program's records (any handler's: the reference, the weights, a GPU) */
    def answer(rs: Vector[Interpret.Record]): Answer =
      val last = rs.last
      Answer(last("yes") == Val.F(true),
        last("on") match { case Val.M(s) => s.map(_.toInt - 1); case _ => Set.empty },
        rs.init.zipWithIndex.collect { case (r, i) if r("cycle") == Val.F(true) => i }.toSet)
    /** the DSL's reference */
    def interpret(edges: Vector[Edge], q: (String, String)): Answer =
      answer(Interpret.run(program, Closure.inputs(edges.map(_.asTriple), q)))
    /** the compiled weights, run as a float32 transformer */
    def forward(edges: Vector[Edge], q: (String, String)): Answer =
      answer(Forward.run(program, weights, Closure.inputs(edges.map(_.asTriple), q)))

  /** the block of the browser smoke: 12 names, 40 positions, 2⁴ hops */
  lazy val block: Block = Block(names, 40, 4)
