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

/** an integer in [0, 2^bits), or none: ±1 per bit and a presence dim, 1 (none: all zeros) — positions, pointers,
 * the tokens of a large vocabulary. The presence makes "none" linear: a wildcard, a guarded copy */
final case class Bits(name: String, bits: Int) extends Field:
  require(bits > 0 && bits < 31, s"$name: 1 to 30 bits")
  def dims: Int = bits + 1
  /** the presence dim's offset in the field */
  def present: Int = bits

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
  /** the next position (`pos` + 1): where a step's record lands — the address of a cell a machine writes (needs
   * 2^bits > maxLen) */
  def next(bits: Int): Bits = Bits("next", bits)
  /** the position `d` back (none before the first) */
  def back(d: Int, bits: Int): Bits = Bits(s"back$d", bits)
  def isBuiltin(f: Field): Boolean =
    f.isInstanceOf[Bits] && (f.name == "pos" || f.name == "prev" || f.name == "next" ||
      f.name.matches("back[1-9][0-9]*"))

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
  /** a value in a set, or not */
  case In(field: Many, value: String)
  case NotIn(field: Many, value: String)
  /** a pointer present, or none (a machine's NIL) */
  case Has(field: Bits)
  case Lacks(field: Bits)

  def reads: Field = this match
    case Is(f, _) => f
    case IsNot(f, _) => f
    case On(f) => f
    case Off(f) => f
    case In(f, _) => f
    case NotIn(f, _) => f
    case Has(f) => f
    case Lacks(f) => f

/** what a rule writes */
enum Write:
  case To(field: Cat, value: String)
  case Raise(flag: Flag)
  case Copy(dst: Field, src: Field)
  /** the union of sets (`Many`) and single values (`Cat`) of the same values; a rule with no guards */
  case Union(dst: Many, srcs: Seq[Field])

  def target: Field = this match
    case To(f, _) => f
    case Raise(f) => f
    case Copy(d, _) => d
    case Union(d, _) => d

/** an op of a layer */
sealed trait Op

/**
 * A HEAD: each position i attends to the positions j ≤ i (not the start) whose `key` fields equal i's `query` fields,
 * pair by pair, and copies `copy`'s sources there into its destinations. No match: none. Several: one if they agree,
 * else inexact — unless `latest`, the latest match winning. `all`: every match's source value gathered into a
 * `Many` destination (a set, never inexact). `wild`: a query field (a `Cat` or `Bits`) that is none matches any key
 * (its pair skipped; every pair skipped: every position matches).
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

/**
 * A COVERING HEAD (DPLL's conflicts and units): position i attends to the positions j whose `key` fields equal i's
 * `query` fields, whose key sets are covered by i's query sets but for at most `slack` members in all (`covered`:
 * pairs (i's set, j's set), the members of j's set not in i's counted), and whose `apart` sets meet none of i's (pairs
 * (i's set, j's set)); of those, the ones with the fewest members uncovered — their `copy` sources gathered into sets
 * (never inexact). "A clause falsified": its positive literals among the false, its negative among the true, slack 0;
 * "a unit clause": slack 1, no literal of it true. Compiled: a score linear in the sets' dims — λ per member covered,
 * −λ per member, λ·slack, −λ(slack + 1) per member shared that must not be, the equal pairs λ(slack + 1) each.
 */
final case class Cover(copy: Seq[(Many, Field)], query: Seq[Field], key: Seq[Field], covered: Seq[(Many, Many)],
    apart: Seq[(Many, Many)] = Nil, slack: Int = 0) extends Head:
  require(slack >= 0, "a cover's slack is not negative")
  def wild: Boolean = false
  def writes: Seq[Field] = copy.map(_._1)

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

/** a program: its inputs, its layers, the longest sequence it runs on (the start included); `causal`: a position
 * attends to itself and those before (a decoder), else to every position (a block of its own, as Datalog.sc) */
final case class Program(inputs: Seq[Field], layers: Seq[Layer], maxLen: Int = 64, causal: Boolean = true):
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
    for b <- inputs.collect { case b: Bits if Builtin.isBuiltin(b) => b }
        if (1 << b.bits) < maxLen || b.name == "next" && (1 << b.bits) <= maxLen do
      out += s"${b.name}: ${b.bits} bits do not reach maxLen $maxLen"
    if inputs.map(_.name).distinct.size != inputs.size then out += "an input given twice"
    for (l, n) <- layers.zipWithIndex do
      def read(f: Field, by: String) =
        if !known(f.name) then out += s"layer $n: $by reads ${f.name} before it is written"
      val headWrites = l.heads.flatMap(_.writes)
      for h <- l.heads do
        if h.query.size != h.key.size || h.query.isEmpty then out += s"layer $n: a head's query and key pair up"
        for (q, k) <- h.query.zip(h.key) do
          read(q, "a head"); read(k, "a head")
          val member = (q, k) match
            case (Many(_, x), Cat(_, y)) => x == y
            case _ => false
          if !(member || same(q, k) && !q.isInstanceOf[Flag] && !q.isInstanceOf[Many]) then
            out += s"layer $n: ${q.name} and ${k.name} cannot be compared"
          if member && h.wild then out += s"layer $n: a set query (${q.name}) is not wild"
        h match
          case h: Lookup =>
            for (d, s) <- h.copy do
              read(s, "a lookup")
              if h.all then (d, s) match
                case (Many(_, dv), Cat(_, sv)) if dv == sv => ()
                case (Many(_, dv), Many(_, sv)) if dv == sv => ()
                case _ => out += s"layer $n: gathering ${s.name} needs a Many of its values, not ${d.name}"
              else if !same(d, s) then out += s"layer $n: ${s.name} copied into ${d.name} of another kind"
            if h.all && h.latest then out += s"layer $n: a lookup gathers all or takes the latest, not both"
          case c: Cover =>
            for (d, s) <- c.copy do
              read(s, "a cover")
              (d, s) match
                case (Many(_, dv), Cat(_, sv)) if dv == sv => ()
                case (Many(_, dv), Many(_, sv)) if dv == sv => ()
                case _ => out += s"layer $n: a cover gathers ${s.name} into a Many of its values, not ${d.name}"
            for (q, k) <- c.covered ++ c.apart do
              read(q, "a cover"); read(k, "a cover")
              if q.values != k.values then out += s"layer $n: a cover's ${q.name} and ${k.name} have other values"
          case c: Count =>
            if c.dst.values != (0 to c.cap).map(_.toString) then
              out += s"layer $n: a count's ${c.dst.name} has the values 0 to K in order"
      for w <- headWrites do
        if known(w.name) then out += s"layer $n: ${w.name} written twice" else known += w.name
      val ruleWrites = l.rules.flatMap(_.sets.map(_.target)).distinct
      // what this layer's MLP decodes (a gathered set, a count: the head writes a share, the MLP the value) — not
      // yet there for the same layer's rules
      val gathered = (l.heads.collect { case h: Lookup if h.all => h.copy.map(_._1.name) }.flatten ++
        l.heads.collect { case c: Count => c.dst.name } ++
        l.heads.collect { case c: Cover => c.copy.map(_._1.name) }.flatten).toSet
      for r <- l.rules do
        r.guards.foreach(g => read(g.reads, "a rule"))
        for f <- r.guards.map(_.reads) ++ r.sets.collect { case Write.Union(_, ss) => ss }.flatten
            if gathered(f.name) do
          out += s"layer $n: ${f.name} is decoded by this layer's MLP (a gathered set, a count); a rule reads it in " +
            "the next layer"
        r.sets.foreach { case Write.Copy(d, s) =>
            read(s, "a rule")
            (d, s) match
              case (Cat(_, x), Cat(_, y)) if x == y => ()
              case (Bits(_, x), Bits(_, y)) if x == y => ()
              case _ =>
                out += s"layer $n: a rule copies ${s.name} into ${d.name}: Cats of the same values or Bits alike"
          case Write.Union(d, ss) =>
            ss.foreach(read(_, "a union"))
            if r.guards.nonEmpty then out += s"layer $n: a union into ${d.name} takes no guards"
            for s <- ss if !(s match
                case Cat(_, x) => x == d.values
                case Many(_, x) => x == d.values
                case _ => false) do
              out += s"layer $n: ${s.name} united into ${d.name} of other values"
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
