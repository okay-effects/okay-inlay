package okay.inlay

/** THE REFERENCE: each op on records of values, as specs/dsl.md states it — the truth the weights must equal */
object Interpret:
  type Record = Map[String, Val]

  def none(f: Field): Val = f match
    case _: Cat => Val.C(None)
    case _: Bits => Val.B(None)
    case _: Flag => Val.F(false)

  /** every position's record, the builtins (`pos`, `prev`) filled: positions count from 1, the start being 0 */
  def records(p: Program, inputs: Vector[Record]): Vector[Record] =
    require(inputs.size + 1 <= p.maxLen, s"${inputs.size} positions: the program runs on ${p.maxLen - 1} at most")
    inputs.zipWithIndex.map { (r, i) =>
      val at = i + 1
      p.inputs.map { f =>
        f.name -> (f.name match
          case "pos" if Builtin.isBuiltin(f) => Val.B(Some(at))
          case "prev" if Builtin.isBuiltin(f) => Val.B(Some(at - 1))
          case n => r.getOrElse(n, none(f)))
      }.toMap
    }

  /** the program run: every position's record, every field (the start not among them) */
  def run(p: Program, inputs: Vector[Record]): Vector[Record] =
    var rs = records(p.checked, inputs)
    for l <- p.layers do
      // the heads read the records as the layer found them, in parallel
      val heard = rs.indices.map(i => l.heads.flatMap(h => lookup(h, rs, i)).toMap)
      rs = rs.indices.map(i => rs(i) ++ heard(i)).toVector
      rs = rs.map(r => r ++ rules(l.rules, r))
    rs

  /** position i's reading of one head (`rs` indexed from position 1) */
  def lookup(h: Lookup, rs: Vector[Record], i: Int): Seq[(String, Val)] =
    val q = h.query.map(f => rs(i)(f.name))
    val defined = q.forall {
      case Val.C(v) => v.isDefined
      case Val.B(v) => v.isDefined
      case Val.F(_) => false
    }
    val matches =
      if !defined then Vector.empty
      else (0 to i).filter(j => h.key.map(f => rs(j)(f.name)) == q).toVector
    if matches.isEmpty then h.copy.map((d, _) => d.name -> none(d))
    else
      val chosen = if h.latest then Vector(matches.last) else matches
      val read = chosen.map(j => h.copy.map((d, s) => d.name -> rs(j)(s.name)))
      if read.distinct.size > 1 then
        throw Inexact(s"position ${i + 1}: ${h.copy.map(_._1.name).mkString(", ")} from ${chosen.size} matches " +
          "that disagree")
      read.head

  def holds(g: Guard, r: Record): Boolean = g match
    case Guard.Is(f, v) => r(f.name) == Val.C(Some(v))
    case Guard.IsNot(f, v) => r(f.name) != Val.C(Some(v))
    case Guard.On(f) => r(f.name) == Val.F(true)
    case Guard.Off(f) => r(f.name) == Val.F(false)

  /** the fields one layer's rules write at a record: none where no rule fires */
  def rules(rs: Seq[Rule], r: Record): Seq[(String, Val)] =
    val firing = rs.filter(_.guards.forall(holds(_, r)))
    val writes = firing.flatMap(_.sets)
    for (d, ws) <- writes.groupBy(_.target.name) if ws.size > 1 do throw Inexact(s"$d written by ${ws.size} rules at once")
    val all = rs.flatMap(_.sets.map(_.target)).distinct
    all.map { d =>
      d.name -> writes.find(_.target.name == d.name).fold(none(d)) {
        case Write.To(_, v) => Val.C(Some(v))
        case Write.Raise(_) => Val.F(true)
        case Write.Copy(_, s) => r(s.name)
      }
    }
