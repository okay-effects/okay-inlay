package okay.inlay

/** A FIELD of every position's record, and how it is coded in the residual stream (specs/dsl.md) */
sealed trait Field:
  def name: String
  def dims: Int

/** one of `values`, or none: one-hot (none: all zeros) */
final case class Cat(name: String, values: Vector[String]) extends Field:
  require(values.nonEmpty && values.distinct == values, s"$name: distinct values")
  def dims: Int = values.size
  def index(v: String): Int =
    val i = values.indexOf(v)
    require(i >= 0, s"$name has no value $v")
    i

/** an integer in [0, 2^bits), or none: ±1 per bit (none: zeros) — positions, pointers */
final case class Bits(name: String, bits: Int) extends Field:
  require(bits > 0 && bits < 31, s"$name: 1 to 30 bits")
  def dims: Int = bits

/** true or false: 1 dim, 1 / 0 */
final case class Flag(name: String) extends Field:
  def dims: Int = 1

/** a set of `values`: one 0/1 dim each */
final case class Many(name: String, values: Vector[String]) extends Field:
  require(values.nonEmpty && values.distinct == values, s"$name: distinct values")
  def dims: Int = values.size

/** the inputs the embedding fills itself: a position's own position, the previous one, the one `d` back */
object Builtin:
  def pos(bits: Int): Bits = Bits("pos", bits)
  def prev(bits: Int): Bits = Bits("prev", bits)
  /** the position `d` back (none before the first) */
  def back(d: Int, bits: Int): Bits = Bits(s"back$d", bits)
  def isBuiltin(f: Field): Boolean =
    f.isInstanceOf[Bits] && (f.name == "pos" || f.name == "prev" || f.name.matches("back[1-9][0-9]*"))

/** a field's value at a position */
enum Val:
  case C(value: Option[String])
  case B(value: Option[Int])
  case F(value: Boolean)
  case M(values: scala.collection.immutable.Set[String])

/** a rule's condition */
enum Guard:
  case Is(field: Cat, value: String)
  case IsNot(field: Cat, value: String)
  case On(flag: Flag)
  case Off(flag: Flag)

  def reads: Field = this match
    case Is(f, _) => f
    case IsNot(f, _) => f
    case On(f) => f
    case Off(f) => f

/** what a rule writes */
enum Write:
  case To(field: Cat, value: String)
  case Raise(flag: Flag)
  case Copy(dst: Cat, src: Cat)

  def target: Field = this match
    case To(f, _) => f
    case Raise(f) => f
    case Copy(d, _) => d

/** an op of a layer */
sealed trait Op

/**
 * A HEAD: each position i attends to the positions j ≤ i (not the start) whose `key` fields equal i's `query` fields,
 * pair by pair, and copies `copy`'s sources there into its destinations. No match: none. Several: one if they agree,
 * else inexact — unless `latest`, the latest match winning. `all`: every match's source value gathered into a
 * `Many` destination (a set, never inexact). `wild`: a query `Cat` field that is none matches any key (its pair
 * skipped; every pair skipped: every position matches).
 */
final case class Lookup(copy: Seq[(Field, Field)], query: Seq[Field], key: Seq[Field], latest: Boolean = false,
    all: Boolean = false, wild: Boolean = false) extends Head:
  def writes: Seq[Field] = copy.map(_._1)

/**
 * A HEAD COUNTING (selector width): the number of positions j ≤ i (not the start) whose `key` fields equal i's
 * `query` fields, capped — into `dst`, a `Cat` of the values "0", "1", … "K" (K and more: "K"). Compiled: the start
 * scores as a match, so it gets 1/(c+1) of the attention; a ladder of steps reads c from it.
 */
final case class Count(dst: Cat, query: Seq[Field], key: Seq[Field], wild: Boolean = false) extends Head:
  def writes: Seq[Field] = Seq(dst)
  def cap: Int = dst.values.size - 1

/** an attention head: what it compares, what it writes */
sealed trait Head extends Op:
  def query: Seq[Field]
  def key: Seq[Field]
  def wild: Boolean
  def writes: Seq[Field]

/** MLP neurons: when every guard holds (and not at the start), the sets */
final case class Rule(guards: Seq[Guard], sets: Seq[Write]) extends Op

/** heads in parallel, then rules */
final case class Layer(heads: Seq[Head] = Nil, rules: Seq[Rule] = Nil)

/** a program: its inputs, its layers, the longest sequence it runs on (the start included) */
final case class Program(inputs: Seq[Field], layers: Seq[Layer], maxLen: Int = 64):
  /** every field: the inputs, then those the layers write, in order */
  def fields: Vector[Field] =
    (inputs ++ layers.flatMap(l => l.heads.flatMap(_.writes) ++ l.rules.flatMap(_.sets.map(_.target))))
      .distinct.toVector

  /** what is wrong with it, if anything: write-once fields, read only after written, types matching */
  def problems: Vector[String] =
    val out = Vector.newBuilder[String]
    val known = scala.collection.mutable.Set.from(inputs.map(_.name))
    def same(a: Field, b: Field) = (a, b) match
      case (Cat(_, x), Cat(_, y)) => x == y
      case (Bits(_, x), Bits(_, y)) => x == y
      case (Flag(_), Flag(_)) => true
      case (Many(_, x), Many(_, y)) => x == y
      case _ => false
    for b <- inputs.filter(Builtin.isBuiltin) if (1 << b.dims) < maxLen do
      out += s"${b.name}: ${b.dims} bits do not reach maxLen $maxLen"
    if inputs.map(_.name).distinct.size != inputs.size then out += "an input given twice"
    for (l, n) <- layers.zipWithIndex do
      def read(f: Field, by: String) =
        if !known(f.name) then out += s"layer $n: $by reads ${f.name} before it is written"
      val headWrites = l.heads.flatMap(_.writes)
      for h <- l.heads do
        if h.query.size != h.key.size || h.query.isEmpty then out += s"layer $n: a head's query and key pair up"
        for (q, k) <- h.query.zip(h.key) do
          read(q, "a head"); read(k, "a head")
          if !same(q, k) || q.isInstanceOf[Flag] || q.isInstanceOf[Many] then
            out += s"layer $n: ${q.name} and ${k.name} cannot be compared"
        h match
          case h: Lookup =>
            for (d, s) <- h.copy do
              read(s, "a lookup")
              if h.all then (d, s) match
                case (Many(_, dv), Cat(_, sv)) if dv == sv => ()
                case _ => out += s"layer $n: gathering ${s.name} needs a Many of its values, not ${d.name}"
              else if !same(d, s) then out += s"layer $n: ${s.name} copied into ${d.name} of another kind"
            if h.all && h.latest then out += s"layer $n: a lookup gathers all or takes the latest, not both"
          case c: Count =>
            if c.dst.values != (0 to c.cap).map(_.toString) then
              out += s"layer $n: a count's ${c.dst.name} has the values 0 to K in order"
      for w <- headWrites do
        if known(w.name) then out += s"layer $n: ${w.name} written twice" else known += w.name
      val ruleWrites = l.rules.flatMap(_.sets.map(_.target)).distinct
      for r <- l.rules do
        r.guards.foreach(g => read(g.reads, "a rule"))
        r.sets.foreach { case Write.Copy(d, s) =>
            read(s, "a rule")
            if d.values != s.values then out += s"layer $n: ${s.name} copied into ${d.name} of other values"
          case _ => () }
      for w <- ruleWrites do
        if known(w.name) then out += s"layer $n: ${w.name} written twice" else known += w.name
    out.result()

  def checked: Program =
    val p = problems
    require(p.isEmpty, p.mkString("; "))
    this

/** a program not exact on these inputs: a lookup's matches disagree, or two rules write one field */
final class Inexact(why: String) extends Exception(why)
