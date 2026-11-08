# API reference — the core library

Package `okay.inlay`, module `core` (JVM and Scala.js, no dependencies). Line numbers point into
`core/src/main/scala/okay/inlay/`.

## Fields — `Program.scala`

`sealed trait Field { def name: String; def dims: Int }`

| Type | Meaning | Residual code | dims |
|---|---|---|---|
| `Cat(name, values: Vector[String])` | one of `values`, or none | one-hot; none all zero | `values.size` |
| `Bits(name, bits: Int)` | an integer in [0, 2^bits), or none | ±1 per bit + a presence dim | `bits + 1` |
| `Flag(name)` | true / false | one dim, 1 / 0 | 1 |
| `Many(name, values: Vector[String])` | a set of `values` | one 0/1 dim per value | `values.size` |

`Cat` and `Many` need distinct, non-empty values; `Cat.index(v)` is a value's index. `Bits` needs `0 < bits < 31`;
`Bits.present` is the offset of its presence dim.

**`object Builtin`** — inputs the embedding fills: `pos(bits)` (named `"pos"`: the position, 1-based, the start 0),
`prev(bits)` (`"prev"`: `pos − 1`), `back(d, bits)` (`"back$d"`: `pos − d`, none before position 1),
`next(bits)` (`"next"`: `pos + 1`, the address a machine's step writes — needs `2^bits > maxLen`).

**`enum Val`** — a field's value at a position: `C(Option[String])`, `B(Option[Int])`, `F(Boolean)`,
`M(Set[String])`.

**`enum Guard`** — `Is(Cat, v)`, `IsNot(Cat, v)`, `On(Flag)`, `Off(Flag)`, `In(Many, v)`, `NotIn(Many, v)`,
`Has(Bits)`, `Lacks(Bits)` (a pointer present or none — a machine's NIL; compiled on the presence dim).

**`enum Write`** — `To(Cat, v)`, `Raise(Flag)`, `Copy(dst, src)` (Cat→Cat with the same values, or Bits→Bits of the
same width), `Union(dst: Many, srcs: Seq[Field])` (a union of `Many`s and `Cat`s with the same values; no guards).

## Ops

`sealed trait Op`; `sealed trait Head extends Op` with `query`, `key`, `wild`, `writes`.

### Lookup

```scala
Lookup(copy: Seq[(Field, Field)], query: Seq[Field], key: Seq[Field],
       latest: Boolean = false, all: Boolean = false, wild: Boolean = false)
```

Position i attends to positions j (j ≤ i when causal; the start is never a match) whose `key` fields equal i's
`query` fields, and copies each `(dst, src)`: src at j into dst at i. No match: none. Several matches: their value
if they agree, else `Inexact`; `latest` — the latest wins; `all` — every match's value gathered into a `Many`;
`wild` — a query that is none matches any key (that pair is skipped).

### Count

```scala
Count(dst: Cat, query: Seq[Field], key: Seq[Field], wild: Boolean = false)
```

The number of matching positions, written into `dst`, whose values must be exactly `"0" … "K"`; `cap = K`, counts
≥ K written as K.

### Cover

```scala
Cover(copy: Seq[(Many, Field)], query: Seq[Field], key: Seq[Field], covered: Seq[(Many, Many)],
      apart: Seq[(Many, Many)] = Nil, slack: Int = 0)
```

Position i attends to the positions whose `key` fields equal i's `query` fields, whose key sets are covered by i's
sets but for at most `slack` members in all (`covered`: pairs (i's set, j's set)), and whose `apart` sets meet none of
i's; of those, the ones with the fewest uncovered — their `copy` sources gathered into sets. Compiled with the equal
pairs weighing λ(slack + 1), λ per member covered, −λ per member, λ·slack, −λ(slack + 1) per member shared that must
not be. DPLL's conflict (slack 0) and unit clauses (slack 1, apart: no literal true).

### Rule, Layer, Program

```scala
Rule(guards: Seq[Guard], sets: Seq[Write])          // fires when all guards hold; never at the start
Layer(heads: Seq[Head] = Nil, rules: Seq[Rule] = Nil)  // heads in parallel, then the rules
Program(inputs: Seq[Field], layers: Seq[Layer], maxLen: Int = 64, causal: Boolean = true)
```

`Program.fields` — the inputs, then the written fields in order. `Program.problems: Vector[String]` — everything
wrong, `Program.checked` — require none. The checks: builtins with too few bits (`2^bits < maxLen`); an input given
twice; a field read before it is written; a field written twice; a head's query and key empty or of different
sizes; fields that cannot be compared (allowed: Cat~Cat with the same values, Bits~Bits of one width, a `Many`
query against a `Cat` key; a `Flag` or Many~Many never); a set query in a `wild` head; `all` into anything but a
`Many` of the source's values; `all` with `latest`; a plain copy into a field of another kind; a `Count`'s values
not `0..K`; a rule reading a set gathered in its own layer; a rule `Copy` of mismatched fields; a `Union` with
guards or sources of other values.

`final class Inexact(why: String) extends Exception` — a lookup's matches disagree, two rules write one field, or a
decoded vector is not clean.

## The reference — `Interpret.scala`

```scala
type Record = Map[String, Val]
Interpret.run(p: Program, inputs: Vector[Record]): Vector[Record]
```

Fills the builtins, then per layer: every head reads the records as the layer found them, the results are merged,
the rules run. Returns every field at every position except the start. `inputs.size + 1 <= p.maxLen`.

## Compile and the forward pass — `Weights.scala`

```scala
Compile(p: Program): Weights
Forward.run(p: Program, w: Weights, inputs: Vector[Record]): Vector[Record]
```

**Weights.** `Layout(offsets, dims)` places the fields in the residual after three fixed dims: `One = 0` (always 1),
`Start = 1` (1 at the start only), `PosNum = 2` (the position as a number), then the program's fields, then scratch
fields (`name#raw` for a gathering head's average, `name#share` for a count's share). `HeadW(qk, ov)` — d×d each:
score `xᵢᵀ qk xⱼ`, copy `ov xⱼ`. `MlpW(w1, w2)` — `x += w2 · ReLU(w1 x)`. `Weights(layout, layers, maxLen, causal)`;
`params = Σ heads · 2d² + neurons · 2d`.

**How a head is compiled.** λ = `Compile.Lambda` = 256 (for a `latest` lookup, `4 · Eps · maxLen`, `Eps` = 128).
For each query/key pair:

- Cat~Cat: `qk[query = v][key = v] = λ`, the head's top score grows by λ;
- a `Many` query against a `Cat` key: `qk[set dim v][one-hot v] = λ` (λ when the key is in the set);
- Bits~Bits: `qk[bit b][bit b] = λ/2` per bit (±1 codes: the product is maximal only on equality);
- `wild`: a none query is corrected to score the top against every non-start position.

A lookup's start row scores half a gap below a match (`qk[One][Start] += top − λ/2`): it wins only when nothing
matches, and carries zeros — none. `latest` adds `Eps` per position (`qk[One][PosNum] = Eps`). `ov` is the identity
from src to dst (into `raw(dst)` for `all`).

**Count.** The start scores as a match (`qk[One][Start] += top`), so it receives 1/(c+1) of the attention;
`ov[share][Start] = 1` copies that share. The MLP decodes it: with S = 4(K+1)² and tₙ = 1/(n + ½),
`Gₙ = [c ≥ n] = clamp(S·(tₙ − x), 0, 1)`; the one-hot "n" is `Gₙ − Gₙ₊₁`, "0" is `1 − G₁` — 2K + 1 neurons.

**How rules are compiled.** A guard row has +1 per positive guard, −1 per negative one; the AND neuron adds
`1 − n` on `One` and `−(n + 1)` on `Start` (n positive guards): 1 when all guards hold, ≤ 0 otherwise or at the
start. `To` / `Raise`: one neuron. `Copy` of a `Cat`: one neuron per value. `Copy` of `Bits`: two per bit
(`ReLU(x + 2(G−n)) − ReLU(−x + 2(G−n))`) plus one for presence. `Union`: two per value (`ReLU(x) − ReLU(x − 1)`).
A gathering head's average becomes 0/1 by `ReLU(L·x) − ReLU(L·x − 1)`, L = 2·maxLen.

**The forward pass.** `Forward.embed` — row 0 is the start (One = Start = 1), row i has One = 1, PosNum = i + 1 and
the inputs' codes; `Forward.layers` — float32 softmax over the visible positions, the residual adds, the ReLU MLP;
`Forward.decode` — tolerance 1e-4, `Inexact` if a `Cat` is not one-hot or zero, a `Many` not 0/1, `Bits` not ±1
with presence, a `Flag` not 0/1.

## Programs in the library

### `Closure.scala` — transitive closure (bridge-4)

```scala
Closure.program(ns: Vector[String], len: Int, k0: Int): (Program, Closure.Fields)
Closure.inputs(sentences: Vector[(String, String, Boolean)], question: (String, String)): Vector[Interpret.Record]
Closure.reference(sentences, question): (Boolean, Set[Int], Set[Int])   // yes, the sentences used, on a cycle
```

A sentence is `(first, second, firstIsSuperior)`; the question `(a, b)` asks "is a above b". Non-causal; per round
a gathering head over the previous round's set and a `Union` layer; 2 + 2·rounds + 2 layers reach 2^rounds hops.
Outputs: `yes` (Flag), `on` (the sentences the answer rests on, a set of positions), `cycle` (Flag). Size: 12 layers
and 921 dims for 12 names and 40 positions; 14 layers and 2,930 dims for 16 names, 128 positions, 2⁵ hops.

### `Quotes.scala` — exact quotes

```scala
Quotes.window(vocab: Vector[String], k: Int, maxLen: Int): Program      // 2k heads
Quotes.cheap(vocab, k, maxLen): Program                                // doubling + Count: log₂k + 4 heads
Quotes.cheapBits(vocab, k, maxLen): Program                            // tokens as Bits; one-hot only to gather
Quotes.reference(toks, src, k): Vector[(Boolean, Set[String])]          // QuoteK: inside, allowed
Quotes.bitsInputs(vocab, toks, src)
```

Markers `[` and `]`. Inside a quote, `allowed` is the set of source tokens whose preceding min(m, k) tokens equal
the last ones copied — the next token may only continue a span of the source.

### `domain/` — domains for readers (`okay.inlay.domain`)

`trait Domain` — a space of meanings with an exact engine: types `Schema`, `Point`, `Answer`; `enumerate`,
`random`, `run(p, s): Either[String, Answer]`, `say(rnd, p, s, language)` (a question for a point), `slots`,
`parts`, `assemble` (parts back into a point). `Slot(name, choices, copied = false)`, `Pair(question, point,
language)`.

`object Kit` — `Reader[S]` (`read(question, schema, slot): Map[String, Double]`), `Copier[S]` (`copy(...)`),
`pairs(d)(schema, n, seed, languages)`, `read(d)(schema, question, reader, copier, margin)` (a slot below the margin
abstains), `measure(d)(...)`: `Measure(n, bySlot, whole, abstained, unread, wrongAnswered)`.

`object Units extends Domain` — unit conversion and calendar arithmetic (`Convert`, `Shift`, `Between`), 16 units
in 4 dimensions with Russian forms, an exact proleptic Gregorian calendar; 886 points in its space. okay-desk's
`TablesDomain` is the second domain.

## Machines — `Machine.scala`, `Cek.scala`

```scala
final case class Machine(step: Program, carry: Seq[(Field, Field)], halted: Interpret.Record => Boolean):
  def run(prefix: Vector[Record], start: Record, runner: Machine.Runner, maxSteps: Int): Machine.Run
object Machine:
  trait Runner { def next(inputs: Record): Record }     // a step at the newest position, the earlier kept
  def reference(p: Program): Runner                     // Interpret's ops, a position at a time
  def weights(p: Program, w: Weights): Runner           // Forward.row, a position at a time
  final case class Run(trace: Vector[Record], last: Record, steps: Int)
  // on a Machine: a round of slots, until `stop` holds of all their states
  def runWide(prefix, starts: Vector[Record], runner, maxRounds, stop: Vector[Record] => Boolean): WideRun
  final case class WideRun(trace: Vector[Record], states: Vector[Record], rounds: Int)
```

A machine is a causal step program run in a loop: the positions of `prefix` first (a program, a table), then one
position a step — its inputs the previous step's outputs named by `carry` — until `halted` holds of the appended
state. `Forward.row(w, layer, xs, nz, i)` is one position through one layer (`Forward.layers` is it at every
position).

`Cek` — the CEK machine with deep effect handlers and multi-shot continuations as a step program:
`Cek.Program(maxLen)` (`step`, `machine`, `prefix(term)`, `start`, `run(term, runner)`), `Cek.Term` (`V`, `L`, `A`,
`H` — handle, `P` — perform), `Cek.reference(term)` (the ordinary machine), `Cek.cases` (the six programs of the
experiment). See [effects.md](effects.md).

`OrParallel` — or-parallel SAT as a wide machine: `OrParallel.compile(nv, cnf)`, `OrParallel.models(nv, cnf)` (brute
force), `OrParallel.Program(nv, npc, slots)` (`step`, `machine`, `run(prog, runner)`: solutions, turns, peak slots).

A rule may not read a `Count`'s destination (or a gathered set) in the head's own layer: its MLP decodes it.

`Registers` — the register machine of Looped.sc: `Registers.Program(npc, top)` (`run(prog, a, b, runner)`),
`Registers.add`,
`Registers.add2`, `Registers.interpret`. `Dpll.Program.guided(cnf, runner, advice, firstOnly)`: the decision's handler
is advice (value, sure) per variable.

`Dpll` — DPLL as a wide machine: `Dpll.models(n, cnf)` (brute force), `Dpll.random3(n, m, rnd)`, `Dpll.Program(n,
slots)` (`step`, `machine`, `run(cnf, runner)`: models, turns, peak branches, branch nodes).

## The bridge — module `bridge` (`okay.inlay.bridge`, JVM + JS, on okay-simple)

Effects `Judge` (`Read(sentence): Option[Edge]`) and `Deduce` (`Above(edges, question): Answer`); the program
`Bridge.above(text, question): Said`; handlers `Bridge.reading(f)`, `Bridge.deducing(f)`; deducers `Bridge.search`,
`Bridge.Block(names, len, rounds)` (`interpret`, `forward`, `answer(records)` for any runner's records).
Module `mcp`: `Tool.above` and `Main` (okay-mcp's server over stdio). Module `mlx`: `ModelJudge` (`asking`, `probing`,
`both` — `Judge` handlers from a model). See
[effects.md](effects.md).
