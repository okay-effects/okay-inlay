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
          case "next" => Val.B(Some(at + 1))
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
      val heard = rs.indices.map(i => l.heads.flatMap(h => lookup(h, rs, i, p.causal)).toMap)
      rs = rs.indices.map(i => rs(i) ++ heard(i)).toVector
      rs = rs.map(r => r ++ rules(l.rules, r))
    rs

  private def defined(v: Val): Boolean = v match
    case Val.C(x) => x.isDefined
    case Val.B(x) => x.isDefined
    case Val.M(x) => x.nonEmpty
    case _ => false

  /** a key equal to the query — or, for a set query, a member of it */
  private def matching(query: Val, key: Val): Boolean = (query, key) match
    case (Val.M(s), Val.C(Some(v))) => s(v)
    case (Val.M(_), _) => false
    case _ => query == key

  /** the positions (indexes into `rs`) up to i whose key fields equal i's query fields — a wild head skipping the
   * pairs whose query `Cat` is none */
  def matches(h: Head, rs: Vector[Record], i: Int, causal: Boolean = true): Vector[Int] =
    val pairs = h.query.zip(h.key).filterNot { (q, _) =>
      h.wild && (rs(i)(q.name) == Val.C(None) || rs(i)(q.name) == Val.B(None))
    }
    if !pairs.forall((q, _) => defined(rs(i)(q.name))) then Vector.empty
    else (if causal then 0 to i else rs.indices).filter(j => pairs.forall((q, k) => matching(rs(i)(q.name), rs(j)(k.name))))
      .toVector

  /** position i's reading of one head (`rs` indexed from position 1) */
  def lookup(h: Head, rs: Vector[Record], i: Int, causal: Boolean = true): Seq[(String, Val)] = h match
    case c: Count => Seq(c.dst.name -> Val.C(Some(math.min(matches(c, rs, i, causal).size, c.cap).toString)))
    case h: Lookup =>
      val found = matches(h, rs, i, causal)
      if h.all then
        h.copy.map((d, s) => d.name -> Val.M(found.flatMap(j => rs(j)(s.name) match
          case Val.C(Some(v)) => Set(v)
          case Val.M(vs) => vs
          case _ => Set.empty[String]).toSet))
      else if found.isEmpty then h.copy.map((d, _) => d.name -> none(d))
      else
        val chosen = if h.latest then Vector(found.last) else found
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
    case Guard.In(f, v) => r(f.name) match { case Val.M(s) => s(v); case _ => false }
    case Guard.NotIn(f, v) => r(f.name) match { case Val.M(s) => !s(v); case _ => true }
    case Guard.Has(f) => r(f.name) != Val.B(None)
    case Guard.Lacks(f) => r(f.name) == Val.B(None)

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
        case Write.Union(_, ss) => Val.M(ss.flatMap(s => r(s.name) match
          case Val.C(Some(v)) => Set(v)
          case Val.M(vs) => vs
          case _ => Set.empty[String]).toSet)
      }
    }
