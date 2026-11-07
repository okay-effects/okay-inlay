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

### From a choice effect to parallel search

Resuming `exists` twice explores both branches one after the other. A transformer has many positions, so the next
experiments give the choice effect a **parallel handler**:

- `OrParallel.sc` — `CHOOSE` forks: the current slot (a position) takes `true`, a free slot becomes its child with
  `false`; children are allocated without conflicts by prefix counts (a COUNT head); `REJECT` recycles a slot; a
  COUNT reads the number of solutions. All branches advance in the same turn: 4–14 parallel turns against 15–70
  sequential steps on 3-variable SAT.
- `Dpll.sc` — the same handler for DPLL: conflict detection and unit propagation are heads over all clauses and all
  branches at once; a 20-variable UNSAT formula refuted in 15 branch nodes against 1,048,576 leaves.
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

## What is not done

- The machine experiments (CEK, handlers, or-parallel, DPLL) are hand-built scripts; the library's DSL does not yet
  express them — the next step is a DSL of step rules that compiles them (BACKLOG 2, 4).
- The bridge as a program on okay-simple's `Effectful` — so that an MCP tool, the block in weights and the browser
  are one program run in one line — is stated, not built.
- A UPLC-shaped CEK (delay/force, builtins as host effects) is planned (BACKLOG 4).
