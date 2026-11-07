package okay.inlay

import Interpret.Record

/**
 * A MACHINE: a step program run in a loop, one position a step (specs/dsl.md §machines). A position holds the
 * machine's state as inputs; the step program computes the next state at that position; `carry` names which outputs
 * become the next position's inputs; the loop appends that position and runs again, until `halted` holds of the
 * appended state. The trace is the memory: a record written by a step stays at its position, and later steps read
 * it back by attention (a heap whose addresses are positions — `Builtin.next` gives a step the address it writes).
 *
 * How a step is computed is a handler, a `Machine.Runner`: the reference (`Interpret`'s ops at the new position) or
 * the compiled weights (`Forward.row` at the new position) — each keeping what it computed for the earlier
 * positions, which a causal program never changes. The law: the same trace by either.
 */
final case class Machine(step: Program, carry: Seq[(Field, Field)], halted: Record => Boolean):
  require(step.causal, "a machine's step program is causal: the earlier positions never change")
  require(carry.forall((o, i) => step.fields.contains(o) && step.inputs.contains(i)), "carry: outputs to inputs")

  /** the machine run from `prefix` (positions given before it starts: a program, a table) and its `start` state:
   * every step's record (all fields), the halting state, and the steps taken */
  def run(prefix: Vector[Record], start: Record, runner: Machine.Runner, maxSteps: Int): Machine.Run =
    prefix.foreach(runner.next)
    val trace = Vector.newBuilder[Record]
    var state = start
    var steps = 0
    var done = false
    while !done do
      require(steps < maxSteps, s"no halt in $maxSteps steps")
      val out = runner.next(state)
      trace += out
      state = carry.map((o, i) => i.name -> out(o.name)).toMap
      steps += 1
      done = halted(state)
    Machine.Run(trace.result(), state, steps)

  /** A WIDE MACHINE: many processors (slots) a round — the positions of a round one per slot, in order, each slot's
   * next state its own record's outputs named by `carry`; rounds until `stop` holds of all the slots' states. A
   * slot's record sees the earlier positions, this round's earlier slots among them (prefix counts: ranks); a step
   * needing every slot of a round reads the round before (a machine of two phases a turn: `OrParallel`) */
  def runWide(prefix: Vector[Record], starts: Vector[Record], runner: Machine.Runner, maxRounds: Int,
      stop: Vector[Record] => Boolean): Machine.WideRun =
    prefix.foreach(runner.next)
    val trace = Vector.newBuilder[Record]
    var states = starts
    var rounds = 0
    while !stop(states) do
      require(rounds < maxRounds, s"no stop in $maxRounds rounds")
      states = states.map { s =>
        val out = runner.next(s)
        trace += out
        carry.map((o, i) => i.name -> out(o.name)).toMap
      }
      rounds += 1
    Machine.WideRun(trace.result(), states, rounds)

object Machine:
  final case class Run(trace: Vector[Record], last: Record, steps: Int)
  final case class WideRun(trace: Vector[Record], states: Vector[Record], rounds: Int)

  /** a step at the newest position: its inputs given, its record (every field) returned; the earlier kept */
  trait Runner:
    def next(inputs: Record): Record

  /** THE REFERENCE, a position at a time: per layer, the records as that layer found them kept */
  def reference(p: Program): Runner = new Runner:
    private val q = p.checked
    private val inputs = Vector.newBuilder[Record]
    private var n = 0
    private val seen = q.layers.map(_ => scala.collection.mutable.ArrayBuffer.empty[Record])
    def next(in: Record): Record =
      inputs += in; n += 1
      var r = Interpret.records(q, inputs.result()).last
      for (l, k) <- q.layers.zipWithIndex do
        seen(k) += r
        val rs = seen(k).toVector
        r = r ++ l.heads.flatMap(h => Interpret.lookup(h, rs, n - 1, causal = true))
        r = r ++ Interpret.rules(l.rules, r)
      r

  /** THE WEIGHTS, a position at a time: per layer, every earlier position's input vector kept (the start first) */
  def weights(p: Program, w: Weights): Runner = new Runner:
    private val q = p.checked
    private val inputs = Vector.newBuilder[Record]
    private val xs = w.layers.map(_ => scala.collection.mutable.ArrayBuffer.empty[Array[Float]])
    private val nz = w.layers.map(_ => scala.collection.mutable.ArrayBuffer.empty[Array[Int]])
    private var started = false
    def next(in: Record): Record =
      inputs += in
      val embedded = Forward.embed(q, w, inputs.result())
      if !started then
        // the start's own way through the layers, once
        var s = embedded(0)
        for (l, k) <- w.layers.zipWithIndex do
          xs(k) += s; nz(k) += Forward.nonZeroDims(s)
          s = Forward.row(w, l, xs(k).toIndexedSeq, nz(k).toIndexedSeq, 0)
        started = true
      var x = embedded.last
      for (l, k) <- w.layers.zipWithIndex do
        xs(k) += x; nz(k) += Forward.nonZeroDims(x)
        x = Forward.row(w, l, xs(k).toIndexedSeq, nz(k).toIndexedSeq, xs(k).size - 1)
      q.fields.map(f => f.name -> Forward.decode(w.layout, f, x)).toMap
