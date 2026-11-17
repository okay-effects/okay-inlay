package okay.inlay

import Interpret.Record
import munit.Assertions.assertEquals

/**
 * A PROGRAM PACKED BY LIVENESS BESIDE ITS PLAIN LAYOUT (specs/dsl.md "Liveness"): what every suite runs again, on
 * the inputs it already ran — the law on the packed layout, and the packed program meaning what the plain one
 * means on every field it still has at the end (`keep`, the suite's read-out, among them).
 */
final class Packing(val plain: Program, keep: Seq[Field]):
  val packed: Liveness.Packed = Liveness(plain, keep.map(_.name).toSet)
  val weights: Weights = Compile(packed.program, packed.layout)
  private val seen: Set[String] = packed.program.outputs.map(_.name).toSet & plain.fields.map(_.name).toSet
  require(keep.forall(f => seen(f.name)), "what is kept is there at the end")
  // the layout is sound — fields sharing a coordinate are never alive together — and no layout is narrower
  private val coordinates = packed.layout.scattered
  for a <- packed.ranges; b <- packed.ranges if a.field.name < b.field.name do
    require((coordinates(a.field.name) intersect coordinates(b.field.name)).isEmpty ||
      a.until <= b.from || b.until <= a.from, s"${a.field.name} and ${b.field.name} share a coordinate alive")
  require(packed.layout.dims - 3 == Liveness.peak(packed.ranges), "the sweep is optimal")

  /** a plain record as the packed program leaves it: the fields not cleared */
  def left(r: Record): Record = r.filter((k, _) => seen(k))

  /** THE LAW ON THE PACKED LAYOUT, on inputs whose plain reference is `ref`: the packed program's reference keeps
   * the plain one's values; its weights, run, decode to its reference */
  def law(in: Vector[Record], ref: Vector[Record])(using munit.Location): Unit =
    val want = Interpret.run(packed.program, in)
    assertEquals(want.map(left), ref.map(left), s"the packed reference against the plain one on $in")
    assertEquals(Forward.run(packed.program, weights, in), want, s"the packed weights against their reference on $in")

  /** a machine's step by the packed program: its reference and its weights */
  def reference: Machine.Runner = Machine.reference(packed.program)
  def runner: Machine.Runner = Machine.weights(packed.program, weights)

  /** a machine's trace by the packed runners against the plain reference's */
  def traces(byReference: Vector[Record], byWeights: Vector[Record], plainReference: Vector[Record])(
      using munit.Location): Unit =
    assertEquals(byReference, plainReference.map(left), "the packed reference's trace against the plain one's")
    assertEquals(byWeights, byReference, "the packed weights' trace against their reference's")

  /** the table's row: layers, fields (and the heads' scratch), heads and those added, the width before and after
   * and its fall, the parameters (`Weights.params`: dense matrices) before and after */
  def row(label: String): String =
    val before = Layout.of(plain).dims
    val after = packed.layout.dims
    val scratch = plain.layers.flatMap(Layout.scratch).size
    val heads = plain.layers.map(_.heads.size).sum
    val neurons = weights.layers.map(_.mlp.w1.length.toLong).sum
    def params(heads: Int, dims: Int) = heads * 2L * dims * dims + neurons * dims * 2
    require(params(heads + packed.clears, after) == weights.params)
    f"liveness | $label | ${plain.layers.size} | ${plain.fields.size} + $scratch | $heads + ${packed.clears} | " +
      f"$before | $after | ${100.0 * (before - after) / before}%.1f%% | ${params(heads, before)} | ${weights.params} |"
