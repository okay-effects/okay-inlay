# Effects

okay-inlay meets algebraic effects in two ways, and they are worth keeping apart:

1. **Effects inside the weights** — a transformer that *is* a machine for a language with effect handlers: perform,
   capture the continuation, resume it once or twice — compiled into attention heads and ReLU neurons.
2. **The weights as a handler** — a program is data, and the compiled weights are one of several interchangeable
   handlers that run it, tied by a law. The same idea carries into the applications, where the language model
   itself is reached only through an effect.

Both come from the same place: okay-effects' rule that a program is a description and its meaning is given by
handlers.

## 1. Effects inside the weights

### The machine

`experiments/Cek.sc` builds a CEK machine — the standard abstract machine for call-by-value λ-calculus (Control,
Environment, Kontinuation) with de Bruijn indices — as one transformer block applied once per step:

- the program's term nodes sit at the context's first positions;
- every step **appends one record**: the machine's registers and at most one heap cell (an environment link or a
  continuation frame);
- **the heap is the trace itself**: a cell's address is the position of the record that wrote it, read back by
  attention;
- head A reads the term node at C; head B reads the cell at ADDR (prefetched by the step before);
- the MLP is the transition function, AND-neurons generated from a table of about ten rules.

On 6 programs (Church booleans) the transformer equals an ordinary CEK interpreter in the answer **and in the number
of steps**; about 40k neurons, every value exactly 0 or 1 throughout.

### Handlers with multi-shot continuations

`experiments/Handlers.sc` extends the language with `handle body with h` and `perform`, and the machine with three
modes (PERF, RESUME, POP) and one more kind of heap cell (META: an installed handler — the handler, its environment,
the outer continuation, the outer handler stack). The rules, quoted from the experiment:

- **handle** — install the handler (a META cell); the body runs in a fresh continuation segment (K = NIL);
- **perform** — read the nearest handler, then run it *outside itself*, with `k` = (this segment, this handler)
  bound at index 0: **capturing the continuation is copying two pointers**;
- **resume** (applying `k` to a value) — read its handler, reinstall it (deep handlers), continue the segment with
  the value;
- **return** — a segment that returns pops its handler (the return clause is the identity), or the machine halts.

Why multi-shot costs nothing here: memory is append-only, nothing is ever overwritten, so a captured segment is
still there after it has been resumed. **Resuming twice is walking from the same two pointers twice.** In a mutable
machine this needs copying stacks; in a transformer's residual and trace it is free.

The handlers that use it:

```scala
val exists = app(or,  app(V(0), tru), app(V(0), fls))   // resume k twice, with true and with false: "is there …"
val forall = app(and, app(V(0), tru), app(V(0), fls))   // "for every choice …"
```

Six programs, from `handle choose with k true` to `∃x. ∃y. ∃z. x ∧ y ∧ ¬z` (822 steps), equal to a reference CEK
machine with deep multi-shot handlers written the ordinary way.

`experiments/HandlersBinary.sc` is the same machine and rules with **binary pointers** (log₂ N bits of ±1, an
increment circuit for "the next position", a NIL test as one neuron): the six programs equal again with 1,931–2,112
neurons instead of 168,458–176,975, and a residual of 145–157 dimensions instead of 8,087.

### In the library: `Machine` and `Cek`

The machine is also a program of the DSL (`core/.../Cek.scala`), compiled by `Compile` like any other: a **step
program** of one layer — two heads (the term node at C, the heap cell at ADDR, each a `Lookup` by position) and 22
rules (the transition, with the handlers' four) — run by `Machine`, which appends one position a step and carries the
next state into it. A pointer is `Bits` (NIL is none, tested by the guards `Has` / `Lacks`); the address of the cell a
step writes is `Builtin.next`, the position its record lands at.

```scala
val cek = Cek.Program(maxLen = 1024)                       // pointers of 11 bits
val w = Compile(cek.step)                                  // 1 layer, 2 heads, 3,909 neurons, residual 467
val t = Cek.cases.last._2                                  // ∃x. ∃y. ∃z. x ∧ y ∧ ¬z
cek.run(t, Machine.reference(cek.step))                    // the DSL's reference, a position at a time
cek.run(t, Machine.weights(cek.step, w))                   // the weights, a position at a time: the same trace
Cek.reference(t)                                           // the machine written the ordinary way: the same answer
```

`Machine.Runner` is the handler of a step: `Machine.reference` runs `Interpret`'s ops at the new position,
`Machine.weights` runs `Forward.row` at the new position — each keeping what it computed for the earlier positions,
which a causal program never changes. `CekSuite`: on the six programs the weights' trace equals the reference's step
by step, and the answers equal the ordinary machine's — ∃x∃y∃z in 822 steps, as in the experiment.

### From a choice effect to parallel search

Resuming `exists` twice explores both branches one after the other. A transformer has many positions, so the next
experiments give the choice effect a **parallel handler**:

- `OrParallel.sc` — `CHOOSE` forks: the current slot (a position) takes `true`, a free slot becomes its child with
  `false`; children are allocated without conflicts by prefix counts (a COUNT head); `REJECT` recycles a slot; a
  COUNT reads the number of solutions. All branches advance in the same turn: 4–14 parallel turns against 15–70
  sequential steps on 3-variable SAT.
In the library too: `OrParallel` (`core/.../OrParallel.scala`) is this machine as a **wide** `Machine` —
`Machine.runWide` runs a round of positions, one a slot. A turn is two rounds: in phase A each slot fetches its
instruction, and `Count` heads give the ranks of this turn's requesters and free slots; in phase B a free slot of rank
k reads the requester of rank k from phase A (`Lookup` by turn and rank) and every slot's transition is computed. Five
layers, 1,065 neurons. `OrParallelSuite`: on eight formulas the weights' trace equals the reference's round by round,
and the solutions equal brute force (4–14 turns, as in the experiment). Writing it found a hole in the DSL: a `Count`'s
value is decoded by its layer's MLP, so a rule of the same layer cannot read it — `Program.problems` now says so.

- `Dpll.sc` — the same handler for DPLL: conflict detection and unit propagation are heads over all clauses and all
  branches at once; a 20-variable UNSAT formula refuted in 15 branch nodes against 1,048,576 leaves.
DPLL is in the library too (`core/.../Dpll.scala`), on a head added for it: **`Cover`** — position i attends to the
positions whose key sets its query sets cover but for at most `slack` members, with some sets required apart; of
those, the ones with the fewest uncovered, their sets gathered. "A clause falsified" is a cover with slack 0 (positive
literals among the false, negative among the true); "a unit clause" slack 1 with no literal true — its unassigned
literal is implied. The score is linear in the sets' dimensions, so it compiles like any head (`CoverSuite`: the law
and the meaning on 300 random cases). `Dpll`: two `Cover` heads over every clause and every branch at once, rules for
reject / propagate / accept / decide, children allocated by prefix counts as in `OrParallel` — 8 layers. `DpllSuite`:
on seven formulas the weights' trace equals the reference's and #SAT equals brute force; on 20 variables and 85
clauses (`DpllBigSuite`) the formula is refuted in 15 branch nodes, 11 turns, 14 branches at once against 1,048,576
leaves — the experiment's numbers, now from a program of the DSL.

- `Branching.sc`, `Gated.sc` — the handler takes advice: a model's guess of which variable and which value first,
  and its confidence (dive where sure, fork where unsure). The search stays exact — a bad guess costs time, never
  correctness — and with good guesses it does 2–4× less work.

The program says "choose"; whether a choice is resumed twice in sequence, forked across positions, or guessed by a
model is the handler's business.

## 2. The weights as a handler

### One program, many handlers

In the DSL a program is data (`Program`: fields, heads, rules). Its handlers:

| Handler | What it is | Where |
|---|---|---|
| `Interpret` | the reference: records of values, each op as stated — the truth | `core` |
| `Compile` + `Forward` | the matrices, run as a float32 transformer on the CPU (JVM or the browser) | `core` |
| `OnMlx` | the same matrices on Apple's GPU | `mlx` |
| `WeightsPort` | a quote program run beside a real model as a decode port: its output masks the next token | `mlx` |
| later: `Attach` | the block added to a real model's layers | `specs/dsl.md` |

They are tied by **the law** — `decode(Forward(Compile(p), embed(x))) == Interpret(p, x)`, and the same for
`OnMlx` — checked on random inputs. Changing the handler never changes the meaning.

This also gives the overflow rule (`specs/inlay.md` §8.6): what fits — clauses, facts, branches — runs inside the
weights; beyond capacity, the same program's handler moves to the runtime (a CPU solver as an effect). One program,
two handlers.

### The decode port: generation as an effect

`experiments/Decode.sc`, `WeightsPort` and mlx-scala's `Port` treat each generated token as an operation a handler
answers: **mask** (only these tokens are allowed), **force** (this token is the only one), **roll back** (return to a
snapshot and replace). A tool call by its schema (15 of 21 tokens forced), a secret never printed, a quote that can
only copy the source — each is a handler of generation, not a change to the model.

### The reader and the model as effects

The bridge (`specs/bridge.md` §9) states the same for reading: the judgement a compiled block needs ("is this
sentence's first name the superior?") is an effect, `Judge(sentence): Boolean`, with handlers **Probe** (a linear
probe on the model's states), **Ask** (the model's own next-token probabilities), **Text** (rules over the words) and
**Gold** (the truth, for measuring). The language model is one handler of it.

okay-desk carries this through: the readers never call a model, they perform `Read` —
`States(ids, at)`, `Logits(ids)`, `Encode`, `Decode`, `Prompt`, `Note` — and the backend is a handler:
`MlxReading` (mlx-scala on the JVM), `ComputeReading` (the page's own forward pass on the JVM), `OnQwen` (the
browser, on WebGPU or WebGL2). The same skill file and the same program run on all of them; the bus
([bridge-and-bus.md](bridge-and-bus.md)) is the contract of what `States` returns.

### The bridge, a program of effects (module `bridge`)

`okay.inlay.bridge` states it on okay-simple: two effects — `Judge` (`Read(sentence): Option[Edge]`, who is above
whom) and `Deduce` (`Above(edges, question): Answer`) — and one program over them, `Bridge.above(text, question)`:
every line read, the edges deduced over, the answer (yes, the lines it rests on, the lines on a cycle) mapped back to
the text. The handlers, chosen in one line:

```scala
type Row[X] = (Judge + Deduce)[X]
import Bridge.*
run(reading(phrasings(_))(deducing(search)(above[Row](lines, q))))           // plain search
run(reading(phrasings(_))(deducing(block.interpret)(above[Row](lines, q))))  // the DSL's reference
run(reading(phrasings(_))(deducing(block.forward)(above[Row](lines, q))))    // the compiled weights, on the CPU
run(reading(gold.get)(deducing(gpu)(above[Row](lines, q))))                  // the gold reads, the GPU deduces
```

The readers: `Bridge.reading(f)` for any `String => Option[Edge]` — rules over the known phrasings, the gold, or a
model's reading (a probe, the model asked: `ClosureBench`'s readers are such functions). The deducers: plain search,
the DSL's reference, the compiled weights on the CPU (JVM or the browser), the same weights on Apple's GPU
(`OnMlx`). `BridgeSuite`: on 30 random texts every deducer gives the same answer and the rules read what the gold
says; `BridgeOnMlxSuite`: the GPU the same. The browser page (`web/`) is the same program, run with the rules and the
weights.

**A model as the reader.** `okay.inlay.gpu.ModelJudge` (module `mlx`) makes a language model the handler of `Judge`:
ASK (the model asked whether x is the superior of y, both orders), a PROBE (a linear probe on the residual at the two
names and the last token), and BOTH (the two summed, each over its spread). `ModelBridgeBench` on Qwen3.5-4B, texts in
phrasings the probe never saw, the closure on the GPU: BOTH read 100% of the sentences and gave the gold's whole answer
(yes, the lines it rests on, the cycles) on 10 of 10 texts; ASK alone 6 of 10, the probe alone 2 of 10.

**An MCP tool.** Module `mcp` serves the program over the Model Context Protocol (okay-mcp, stdio): the tool `above`
runs `Bridge.above` with the rules reading and the compiled closure deducing — one more front of the same program, as
the tests, the GPU and the browser are.

## What is not done

- The guided search of `Branching.sc` and `Gated.sc` (a model's guesses and confidences steering DPLL's decisions) is
  still hand-built: in the library it is a `Judge`-like effect for the decision, its handlers the first unassigned
  variable (now) or a model's score.
- A UPLC-shaped CEK (delay/force, builtins as host effects) is planned (BACKLOG 4).
