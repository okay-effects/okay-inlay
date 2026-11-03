var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// A LOOPED TRANSFORMER AS AN INTERPRETER: one block (attention + MLP), the same weights applied T times;
// the program is data at the context's positions, the machine's state lives in the residual stream. float32.
// Machine: registers A, B in 0..15, PC in 0..7; ops INC_A, DEC_B, JZ_B k, JMP k, HALT.
val nPc = 8; val nR = 16
val ops = Vector("INC_A", "DEC_B", "JZ_B", "JMP", "HALT")
// residual layout
val PC = 0; val A = PC + nPc; val B = A + nR; val HALTED = B + nR; val OP = HALTED + 1; val ARG = OP + ops.size
val POS = ARG + nPc; val D = POS + nPc
def op(name: String) = OP + ops.indexOf(name)

type Vec = Array[Float]
def zero = Array.fill(D)(0f)
def state(pc: Int, a: Int, b: Int): Vec = { val x = zero; x(PC + pc) = 1; x(A + a) = 1; x(B + b) = 1; x }
def instr(i: Int, name: String, arg: Int = 0): Vec = {
  val x = zero; x(POS + i) = 1; x(op(name)) = 1; x(ARG + arg) = 1; x
}

// ---- attention: fetch the instruction at position PC. q = PC one-hot, k = POS one-hot, v = (OP, ARG) copied.
val lambda = 250f
def attention(x: Vec, program: Seq[Vec]): Vec =
  val scores = program.map(p => lambda * (0 until nPc).map(j => x(PC + j) * p(POS + j)).sum)
  val mx = scores.max
  val e = scores.map(s => math.exp((s - mx).toDouble).toFloat); val w = e.map(_ / e.sum)
  val out = zero
  for (p, wi) <- program.zip(w); d <- OP until POS do out(d) += wi * p(d) // W_OV: copy OP and ARG
  out

// ---- MLP: neurons are AND gates ReLU(x1 + … + xn - (n-1)); each writes a DELTA (new one-hot minus old):
// the residual stream only adds, so an assignment is "add the difference"
case class Neuron(in: Seq[(Int, Float)], bias: Float, out: Seq[(Int, Float)])
def and(ins: Int*)(out: (Int, Float)*) = Neuron(ins.map(_ -> 1f), -(ins.size - 1).toFloat, out)
val mlp: Seq[Neuron] =
  (for a <- 0 until nR - 1 yield and(op("INC_A"), A + a)(A + a + 1 -> 1f, A + a -> -1f)) ++
    (for b <- 1 until nR yield and(op("DEC_B"), B + b)(B + b - 1 -> 1f, B + b -> -1f)) ++
    (for o <- Seq("INC_A", "DEC_B"); j <- 0 until nPc - 1 yield and(op(o), PC + j)(PC + j + 1 -> 1f, PC + j -> -1f)) ++
    // JZ_B k: B = 0 -> PC := k
    (for k <- 0 until nPc; j <- 0 until nPc if k != j
    yield and(op("JZ_B"), B + 0, ARG + k, PC + j)(PC + k -> 1f, PC + j -> -1f)) ++
    // JZ_B k: B != 0 -> PC := PC + 1   (ReLU(op + pc - b0 - 1))
    (for j <- 0 until nPc - 1
    yield Neuron(Seq(op("JZ_B") -> 1f, PC + j -> 1f, B + 0 -> -1f), -1f, Seq(PC + j + 1 -> 1f, PC + j -> -1f))) ++
    (for k <- 0 until nPc; j <- 0 until nPc if k != j
    yield and(op("JMP"), ARG + k, PC + j)(PC + k -> 1f, PC + j -> -1f)) ++
    // HALT: set the flag once (ReLU(op - halted)), PC stays: from then on every step changes nothing — a fixed point
    Seq(Neuron(Seq(op("HALT") -> 1f, HALTED -> -1f), 0f, Seq(HALTED -> 1f))) ++
    // clear the fetched instruction, so the next step's attention writes into an empty subspace
    (for d <- OP until POS yield Neuron(Seq(d -> 1f), 0f, Seq(d -> -1f)))
def mlpForward(x: Vec): Vec =
  val out = zero
  for n <- mlp do
    val h = math.max(0f, n.in.map((i, w) => w * x(i)).sum + n.bias)
    if h != 0f then for (i, w) <- n.out do out(i) += w * h
  out

def block(x: Vec, program: Seq[Vec]): Vec =
  val y = x.clone; val a = attention(y, program); for i <- 0 until D do y(i) += a(i)
  val m = mlpForward(y); for i <- 0 until D do y(i) += m(i)
  y

def read(x: Vec, from: Int, n: Int) = (0 until n).maxBy(i => x(from + i))
def run(program: Seq[Vec], a: Int, b: Int, steps: Int): (Int, Int, Boolean, Vec) =
  var x = state(0, a, b)
  for _ <- 0 until steps do x = block(x, program)
  (read(x, A, nR), read(x, B, nR), x(HALTED) > 0.5f, x)

// reference interpreter
def interp(p: Seq[(String, Int)], a0: Int, b0: Int): (Int, Int) =
  var (pc, a, b) = (0, a0, b0)
  while p(pc)._1 != "HALT" do
    p(pc) match
      case ("INC_A", _) => a += 1; pc += 1
      case ("DEC_B", _) => b -= 1; pc += 1
      case ("JZ_B", k)  => pc = if b == 0 then k else pc + 1
      case ("JMP", k)   => pc = k
      case _            => ()
  (a, b)

def compile(p: Seq[(String, Int)]) = p.padTo(nPc, ("HALT", 0)).zipWithIndex.map { case ((o, k), i) => instr(i, o, k) }
val add = Seq("JZ_B" -> 4, "INC_A" -> 0, "DEC_B" -> 0, "JMP" -> 0, "HALT" -> 0) // A := A + B
val add2 = Seq("JZ_B" -> 5, "INC_A" -> 0, "INC_A" -> 0, "DEC_B" -> 0, "JMP" -> 0, "HALT" -> 0) // A := A + 2B
println(s"d_model = $D, MLP neurons = ${mlp.size}, attention: 1 head")
for (name, p, f) <- Seq(("A+B", add, (a: Int, b: Int) => a + b), ("A+2B", add2, (a: Int, b: Int) => a + 2 * b)) do
  val cases = for a <- 0 until nR; b <- 0 until nR if f(a, b) < nR yield (a, b)
  val bad = cases.filter { (a, b) =>
    val (ra, rb, h, _) = run(compile(p), a, b, 80); (ra, rb) != interp(p, a, b) || !h
  }
  check(bad.isEmpty, name)
  println(s"$name: ${cases.size} inputs, same weights, 80 steps: ${
      if bad.isEmpty then "all equal to the interpreter" else s"BAD $bad"
    }")
val (ra, rb, h, x) = run(compile(add), 3, 4, 80)
println(s"A+B on (3, 4): A=$ra B=$rb halted=$h; residual values other than 0/1: ${x.count(v => v != 0f && v != 1f)}")
// the trace: the state after each step
var x0 = state(0, 3, 2)
println(
  "trace A+B (3,2): " + (0 to 12)
    .map { t =>
      val s = s"pc${read(x0, PC, nPc)} A${read(x0, A, nR)} B${read(x0, B, nR)}"
      x0 = block(x0, compile(add)); s
    }
    .mkString(" → ")
)
if failures > 0 then sys.exit(1) else println("all checks passed")
