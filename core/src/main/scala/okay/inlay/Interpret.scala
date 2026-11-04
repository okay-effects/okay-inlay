package okay.inlay

/** THE REFERENCE: each op on records of values, as specs/dsl.md states it — the truth the weights must equal */
object Interpret:
  type Record = Map[String, Val]

  def none(f: Field): Val = f match
    case _: Cat => Val.C(None)
    case _: Bits => Val.B(None)
    case _: Flag => Val.F(false)
    case _: Many => Val.M(Set.empty)

  /** every position's record, the builtins (`pos`, `prev`, `backD`) filled: positions count from 1, the start 0 */
  def records(p: Program, inputs: Vector[Record]): Vector[Record] =
    require(inputs.size + 1 <= p.maxLen, s"${inputs.size} positions: the program runs on ${p.maxLen - 1} at most")
    inputs.zipWithIndex.map { (r, i) =>
      val at = i + 1
      p.inputs.map { f =>
        f.name -> (if !Builtin.isBuiltin(f) then r.getOrElse(f.name, none(f)) else f.name match
          case "pos" => Val.B(Some(at))
          case "prev" => Val.B(Some(at - 1))
          case back =>
            val d = back.stripPrefix("back").toInt
            Val.B(if at - d >= 1 then Some(at - d) else None))
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

  private def defined(v: Val): Boolean = v match
    case Val.C(x) => x.isDefined
    case Val.B(x) => x.isDefined
    case _ => false

  /** position i's reading of one head (`rs` indexed from position 1) */
  def lookup(h: Lookup, rs: Vector[Record], i: Int): Seq[(String, Val)] =
    // a wild lookup skips the pairs whose query `Cat` is none
    val pairs = h.query.zip(h.key).filterNot { (q, _) =>
      h.wild && q.isInstanceOf[Cat] && rs(i)(q.name) == Val.C(None)
    }
    val matches =
      if !pairs.forall((q, _) => defined(rs(i)(q.name))) then Vector.empty
      else (0 to i).filter(j => pairs.forall((q, k) => rs(j)(k.name) == rs(i)(q.name))).toVector
    if h.all then
      h.copy.map((d, s) => d.name -> Val.M(matches.flatMap(j => rs(j)(s.name) match
        case Val.C(Some(v)) => Some(v)
        case _ => None).toSet))
    else if matches.isEmpty then h.copy.map((d, _) => d.name -> none(d))
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
    for (d, ws) <- writes.groupBy(_.target.name) if ws.size > 1 do
      throw Inexact(s"$d written by ${ws.size} rules at once")
    val all = rs.flatMap(_.sets.map(_.target)).distinct
    all.map { d =>
      d.name -> writes.find(_.target.name == d.name).fold(none(d)) {
        case Write.To(_, v) => Val.C(Some(v))
        case Write.Raise(_) => Val.F(true)
        case Write.Copy(_, s) => r(s.name)
      }
    }
