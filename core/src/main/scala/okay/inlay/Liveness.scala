package okay.inlay

/**
 * REGISTER ALLOCATION FOR THE RESIDUAL STREAM (specs/dsl.md "Liveness"). `Layout.of` gives every field its own
 * coordinates in every layer; here a field holds coordinates only while it is alive — from the layer that writes
 * it to the last one that reads it — and a later field takes them. The width becomes the most dims alive at once.
 *
 * When coordinates are free. A layer is two stages: the heads (each reads the layer's input, they add in parallel),
 * then the MLP. A `Clear` head subtracts a field at the head stage, so:
 * - a field last read by a HEAD of layer b is cleared in layer b (the heads still read the layer's input);
 * - a field last read by the RULES of layer b (or by its MLP's ladder: a head's scratch) is cleared in layer b + 1;
 * - a field nobody reads and nobody keeps is cleared in the layer after the one that writes it.
 * Coordinates cleared in layer c serve any field written in layer c or later, by a head or by the rules: a head
 * writing in the clear's own layer adds to minus-the-old, which is zero plus the new.
 *
 * A field is as many unit intervals as it has dims — its coordinates need not be neighbours (`Layout.scattered`) —
 * so the sweep by first layer, lowest free coordinate first, is optimal: the width is `peak`.
 */
object Liveness:
  /** kept: never cleared */
  val Forever: Int = Int.MaxValue

  /** a field's life: written in layer `from` (−1: an input, by the embedding); its coordinates free for a field
   * written in layer `until` or later */
  final case class Range(field: Field, from: Int, until: Int)

  /** the program with its `Clear` heads, its layout by liveness, and the ranges they came from */
  final case class Packed(program: Program, layout: Layout, ranges: Vector[Range]):
    /** how many heads were added (one a layer that clears) */
    def clears: Int = program.layers.count(_.heads.exists(_.isInstanceOf[Clear]))

  /** every field's range — the program's own, then each layer's scratch — with `keep` (the read-out: what is read
   * from the last layer, a machine's carried outputs) alive to the end */
  def ranges(p: Program, keep: Set[String]): Vector[Range] =
    val from = scala.collection.mutable.LinkedHashMap.empty[String, (Field, Int)]
    val until = scala.collection.mutable.Map.empty[String, Int]
    def written(f: Field, layer: Int): Unit = if !from.contains(f.name) then from(f.name) = (f, layer)
    def read(f: Field, free: Int): Unit = until(f.name) = until.getOrElse(f.name, 0) max free
    p.inputs.foreach(written(_, -1))
    for (l, n) <- p.layers.zipWithIndex do
      for h <- l.heads do
        (h.query ++ h.key).foreach(read(_, n))
        h match
          case h: Lookup => h.copy.foreach((_, s) => read(s, n))
          case c: Cover =>
            c.copy.foreach((_, s) => read(s, n))
            (c.covered ++ c.apart).foreach { (q, k) => read(q, n); read(k, n) }
          case _ => ()
        h.writes.foreach(written(_, n))
      for s <- Layout.scratch(l) do { written(s, n); read(s, n + 1) }
      for r <- l.rules do
        r.guards.foreach(g => read(g.reads, n + 1))
        r.sets.foreach {
          case Write.Copy(_, s) => read(s, n + 1)
          case Write.Union(_, ss) => ss.foreach(read(_, n + 1))
          case _ => ()
        }
      l.rules.foreach(_.sets.foreach(w => written(w.target, n)))
    from.values.map { (f, layer) =>
      // (a clear in a program that is not causal finds its own position by `pos`)
      val kept = keep(f.name) || !p.causal && f.name == "pos"
      Range(f, layer, if kept then Forever else until.getOrElse(f.name, layer + 1))
    }.toVector

  /** the most dims alive at once: no layout of these ranges is narrower */
  def peak(ranges: Vector[Range]): Int =
    ranges.map(_.from).distinct.map(t => ranges.filter(r => r.from <= t && t < r.until).map(_.field.dims).sum)
      .maxOption.getOrElse(0)

  /** THE PROGRAM PACKED: fields laid out by liveness, a `Clear` head in each layer where coordinates change hands
   * (only fields whose coordinates are taken again are cleared). A program that is not causal gets the input `pos`
   * if it has none (the embedding fills it): its clears find their own position by it */
  def apply(program: Program, keep: Set[String]): Packed =
    require(program.cleared.isEmpty, "a program with clears of its own is not packed again")
    val bits = 32 - Integer.numberOfLeadingZeros(program.maxLen - 1)
    val p = if program.causal || program.inputs.exists(_.name == "pos") then program.checked
      else program.checked.copy(inputs = program.inputs :+ Builtin.pos(bits max 1))
    val rs = ranges(p, keep)
    // a coordinate: the layer it is free from, and who holds it
    val freeFrom = scala.collection.mutable.ArrayBuffer.empty[Int]
    val holder = scala.collection.mutable.ArrayBuffer.empty[Range]
    val taken = scala.collection.mutable.Set.empty[String]
    val coordinates = rs.sortBy(_.from).map { r =>
      val free = freeFrom.indices.filter(freeFrom(_) <= r.from).take(r.field.dims)
      val mine = free ++ (freeFrom.size until freeFrom.size + r.field.dims - free.size)
      for c <- mine do
        if c < freeFrom.size then { taken += holder(c).field.name; freeFrom(c) = r.until; holder(c) = r }
        else { freeFrom += r.until; holder += r }
      r.field.name -> mine.map(_ + 3).toVector
    }.toMap
    val clearedIn = rs.filter(r => taken(r.field.name)).groupBy(_.until)
    val layers = p.layers.zipWithIndex.map { (l, n) =>
      clearedIn.get(n).fold(l)(gone => l.copy(heads = l.heads :+ Clear(gone.map(_.field))))
    }
    Packed(p.copy(layers = layers).checked, Layout(Map.empty, 3 + freeFrom.size, coordinates), rs)
