package okay.inlay.gpu

import _root_.mlx.Dtype
import okay.inlay.*
import okay.inlay.Interpret.Record

/**
 * A PROGRAM, PLAIN AND PACKED BY LIVENESS, ON THE GPU (specs/dsl.md "Liveness on MLX"): the program, what its
 * read-out keeps, and whole input sequences. A machine's sequence is one of its runs — the prefix and every state
 * the plain reference fed its step program — run in one pass: a causal program's positions never change, so the
 * pass's records are the machine's trace.
 */
final class Packed(val label: String, val plain: Program, keep: Seq[Field], val inputs: Vector[Vector[Record]]):
  val packing: Packing = Packing(plain, keep)
  def packed: Program = packing.packed.program
  lazy val plainWeights: Weights = Compile(plain)
  /** the references, sequence by sequence */
  lazy val plainRef: Vector[Vector[Record]] = inputs.map(Interpret.run(plain, _))
  lazy val packedRef: Vector[Vector[Record]] = inputs.map(Interpret.run(packed, _))

  /** per layer with a clear: the coordinates it frees that no head of the layer writes in the same stage — after
   * the layer's heads they hold what the clear left, nothing else */
  val freed: Map[Int, Vector[Int]] =
    val at = packing.packed.layout.scattered
    packed.layers.zipWithIndex.flatMap { (l, n) =>
      val gone = l.heads.collect { case c: Clear => c.fields }.flatten
      val written = (l.heads.filterNot(_.isInstanceOf[Clear]).flatMap(_.writes) ++ Layout.scratch(l))
        .flatMap(f => at.getOrElse(f.name, Vector.empty)).toSet
      Option.when(gone.nonEmpty)(n -> gone.flatMap(f => at(f.name)).filterNot(written).toVector)
    }.toMap

  /** the positions decoded against a reference, sequence by sequence */
  def tally(gpu: OnMlx, p: Program, refs: Vector[Vector[Record]]): Packed.Tally =
    var t = Packed.Tally(0, 0, 0, 0, None)
    for (in, ref) <- inputs.zip(refs) do
      val xs = gpu.run(Forward.embed(p, gpu.w, in)).drop(1)
      for (x, i) <- xs.zipWithIndex do
        val ok =
          try Some(p.outputs.map(f => f.name -> Forward.decode(gpu.w.layout, f, x)).toMap == ref(i))
          catch case _: Inexact => None
        val first = if ok.contains(true) || t.first.exists(_._1 <= i + 1) then t.first else Some((i + 1, in.size))
        t = Packed.Tally(t.positions + 1, t.exact + (if ok.contains(true) then 1 else 0),
          t.wrong + (if ok.contains(false) then 1 else 0), t.undecodable + (if ok.isEmpty then 1 else 0), first)
    t

  /** what the clears leave on the coordinates they free, every position (the start too) of every sequence */
  def zeros(gpu: OnMlx): Packed.Zeros =
    var z = Packed.Zeros(0, 0, 0, 0f)
    for in <- inputs do
      val (_, stages) = gpu.tapped(Forward.embed(packed, gpu.w, in), freed.keySet)
      for (layer, (found, left)) <- stages; row <- found.indices; c <- freed(layer) do
        val v = left(row)(c)
        z = Packed.Zeros(z.cells + 1, z.held + (if found(row)(c) != 0f then 1 else 0),
          z.notZero + (if v != 0f then 1 else 0), if v.isNaN then Float.NaN else z.largest max math.abs(v))
    z

object Packed:
  /** positions: decoded to the reference, decoded to something else, not decodable; the earliest position not
   * exact over the sequences (from 1) and that sequence's length */
  final case class Tally(positions: Int, exact: Int, wrong: Int, undecodable: Int, first: Option[(Int, Int)]):
    def holds: Boolean = exact == positions
    override def toString: String =
      if holds then s"holds ($exact of $positions)"
      else
        val (at, of) = first.get
        s"$exact of $positions exact, $wrong wrong, $undecodable not decodable, first at position $at of $of"

  /** cells (a position × a freed coordinate): how many held something before the clear, how many are not exactly
   * zero after it, and the largest magnitude left (NaN if any is) */
  final case class Zeros(cells: Long, held: Long, notZero: Long, largest: Float):
    override def toString: String = s"$notZero of $cells cells not 0 after the clears ($held held a value), " +
      s"largest $largest"

  val dtypes: Seq[Dtype] = Seq(Dtype.Float32, Dtype.Float16, Dtype.BFloat16)

  /** a runner that remembers what it was fed */
  private final class Fed(inner: Machine.Runner) extends Machine.Runner:
    private val ins = Vector.newBuilder[Record]
    def fed: Vector[Record] = ins.result()
    def next(in: Record): Record = { ins += in; inner.next(in) }

  /** a machine's run by its plain reference as one sequence; checked: the sequence in one pass is the trace */
  private def sequence(step: Program, run: Machine.Runner => Vector[Record]): Vector[Record] =
    val runner = Fed(Machine.reference(step))
    val trace = run(runner)
    val in = runner.fed
    require(Interpret.run(step, in).takeRight(trace.size) == trace, "one pass over a run is its trace")
    in

  private def c(v: String) = Val.C(Some(v))

  /** `OnMlxSuite`'s streams: a source, then tokens with quotes opening and closing */
  private def streams(seed: Int, count: Int, words: Vector[String]): Vector[(Vector[String], Vector[Boolean])] =
    val r = scala.util.Random(seed)
    Vector.fill(count) {
      val n = 16 + r.nextInt(47)
      val s = 3 + r.nextInt(n / 2)
      val toks = Vector.tabulate(n)(i =>
        if i >= s && r.nextInt(6) == 0 then (if r.nextBoolean() then "[" else "]") else words(r.nextInt(words.size)))
      (toks, Vector.tabulate(n)(_ < s))
    }

  private def quote(name: String, k: Int): (String, () => Packed) = s"Quotes.$name, k = $k" -> { () =>
    val vocab = Vector("a", "b", "c", "[", "]")
    val p = if name == "window" then Quotes.window(vocab, k, 64) else Quotes.cheap(vocab, k, 64)
    val ins = streams(3, 20, Vector("a", "b", "c")).map((toks, src) => toks.zip(src).map((t, s) =>
      Map[String, Val]("tok" -> c(t)) ++ (if s then Map("src" -> c("y")) else Map.empty)))
    Packed(s"Quotes.$name, k = $k", p, p.fields.filter(f => f.name == "inside" || f.name == "allowed"), ins)
  }

  private def bits(size: Int, k: Int): (String, () => Packed) = s"Quotes.cheapBits, $size words, k = $k" -> { () =>
    val words = (0 until size).map(i => s"t$i").toVector
    val vocab = words ++ Vector("[", "]")
    val p = Quotes.cheapBits(vocab, k, 64)
    val ins = streams(17, 15, words.take(4)).map((toks, src) => Quotes.bitsInputs(vocab, toks, src))
    Packed(s"Quotes.cheapBits, $size words, k = $k", p,
      p.fields.filter(f => f.name == "inside" || f.name == "allowed"), ins)
  }

  val closureNames: Vector[String] =
    Vector("Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal", "Ivy", "Jon", "Kim", "Lou")

  /** the bridge's closure block (12 names, 40 positions, 4 rounds) on random graphs, every fourth with a cycle */
  def closure(): Packed =
    val names = closureNames
    val (p, _) = Closure.program(names, 40, 4)
    val rnd = new scala.util.Random(3)
    val ins = Vector.tabulate(40) { trial =>
      val order = rnd.shuffle(names)
      val edges = Vector.fill(8 + rnd.nextInt(16)) {
        val i = rnd.nextInt(order.size - 1); val j = i + 1 + rnd.nextInt(order.size - 1 - i)
        (order(i), order(j))
      }
      val all = if trial % 4 == 3 then edges :+ { val (x, y) = edges(rnd.nextInt(edges.size)); (y, x) } else edges
      val sentences = rnd.shuffle(all).map((x, y) => if rnd.nextBoolean() then (x, y, true) else (y, x, false))
      Closure.inputs(sentences, (names(rnd.nextInt(names.size)), names(rnd.nextInt(names.size))))
    }
    Packed("Closure, 12 names, 4 rounds", p, p.fields.filter(f => Set("yes", "on", "cycle")(f.name)), ins)

  def dpll(n: Int, cnf: Vector[Dpll.Clause]): Packed =
    val m = Dpll.Program(n, slots = 20)
    Packed(s"Dpll, n = $n, 20 slots", m.step, m.machine.carry.map(_._1) ++ Seq(m.req, m.pHas, m.phase, m.free),
      Vector(sequence(m.step, r => m.run(cnf, r)._5.trace)))

  private def pigeons: Vector[Dpll.Clause] =
    val each = (0 until 3).map(i => Seq((2 * i) -> true, (2 * i + 1) -> true))
    val apart = for h <- 0 until 2; i <- 0 until 3; j <- i + 1 until 3
      yield Seq((2 * i + h) -> false, (2 * j + h) -> false)
    (each ++ apart).toVector

  def dpll8(): Packed = dpll(8, Dpll.random3(8, 34, new scala.util.Random(1)))

  /** every program the MLX module checks plain, and the machines — built when asked (their weights are large) */
  val cases: Seq[(String, () => Packed)] = Seq(quote("window", 3), quote("cheap", 8), quote("cheap", 32),
    bits(6, 4), bits(40, 8), bits(300, 16),
    "Closure, 12 names, 4 rounds" -> (() => closure()),
    "Registers" -> { () =>
      val m = Registers.Program()
      val runs = for (p, f) <- Seq((Registers.add, (a: Int, b: Int) => a + b), (Registers.add2, (a: Int, b: Int) =>
          a + 2 * b)); (a, b) <- Seq((0, 0), (1, 0), (0, 1), (3, 4), (7, 2), (2, 6), (5, 5), (m.top, 0))
          if f(a, b) <= m.top
        yield sequence(m.step, r => m.run(p, a, b, r)._4.trace)
      Packed("Registers", m.step, m.machine.carry.map(_._1), runs.toVector)
    },
    "Cek, 1,024 positions" -> { () =>
      val cek = Cek.Program(1024)
      Packed("Cek, 1,024 positions", cek.step, cek.machine.carry.map(_._1),
        Cek.cases.map((_, t) => sequence(cek.step, r => cek.run(t, r)._3.trace)).toVector)
    },
    "OrParallel, 3 variables, 12 slots" -> { () =>
      val m = OrParallel.Program(nv = 3, npc = 24, slots = 12, maxLen = 512, maxTurns = 32)
      Packed("OrParallel, 3 variables, 12 slots", m.step, m.machine.carry.map(_._1), OrParallel.cases.map((_, cnf) =>
        sequence(m.step, r => m.run(OrParallel.compile(3, cnf), r)._4.trace)).toVector)
    },
    "Dpll, n = 3, 20 slots" -> (() => dpll(3, Vector(Seq(0 -> true, 1 -> true), Seq(0 -> false, 2 -> true),
      Seq(1 -> false, 2 -> false)))),
    "Dpll, n = 6, 20 slots" -> (() => dpll(6, pigeons)),
    "Dpll, n = 8, 20 slots" -> (() => dpll8()),
    "Dpll, n = 10, 20 slots" -> (() => dpll(10, Dpll.random3(10, 42, new scala.util.Random(1)))))
