# okay-inlay

**Programs compiled into transformer weights — exactly.**

okay-inlay turns small programs into attention heads and ReLU neurons, so that a transformer performs the program
exactly, and checks every program against an ordinary interpreter. It is a Scala 3 library (JVM and the browser)
and a research repository on what such exact blocks can do beside a real language model.

📖 **[Documentation](docs/README.md)** · [Concepts](docs/concepts.md) · [Guide](docs/guide.md) ·
[API](docs/api.md) · [Effects](docs/effects.md) · [Experiments](docs/experiments.md) · [Results](docs/results.md)

## Why

Language models are bad at exact work. Given a table of 40 orders, Qwen3.6-35B, thinking off, says 15 orders are
unpaid; the answer is 14. With thinking on it gets 14 — after 1,342 tokens and 3 minutes, imitating a loop token by
token.

"Count the rows whose status is unpaid" is a tiny program. okay-inlay compiles it into one attention head and one
MLP — 29,492 parameters — that give 14 in milliseconds, every time. *Exactly* is a law here, not a hope: the
weights must give what the program's interpreter gives, checked on random inputs.

Speed is not the point (a CPU runs the same program millions of times faster). The point is what an exact
mechanism *inside a model* can be — data access, counting, deduction, search, effects — and how it composes with
what only the model has: its reading of text, its context, its internal state.

## A taste

```scala
import okay.inlay.*

val status = Cat("status", Vector("paid", "unpaid"))     // each row: a position with its status
val ask    = Cat("ask", Vector("paid", "unpaid"))        // the question: a position asking for a status
val count  = Cat("count", (0 to 64).map(_.toString).toVector)

val program = Program(Seq(status, ask),
  Seq(Layer(heads = Seq(Count(count, Seq(ask), Seq(status))))), maxLen = 64)

val statuses = Vector("paid", "unpaid", "unpaid" /* … a status per row */)
val weights = Compile(program)                            // the matrices
val rows = statuses.map(s => Map("status" -> Val.C(Some(s)))) :+ Map("ask" -> Val.C(Some("unpaid")))

Interpret.run(program, rows).last("count")                // the reference interpreter
Forward.run(program, weights, rows).last("count")         // the weights, run as a float32 transformer: the same
```

The law, for every program: `decode(Forward(Compile(p), embed(x))) == Interpret(p, x)`.

## What is inside

**The library** (`core`, JVM + Scala.js, no dependencies) — a DSL of fields (`Cat`, `Bits`, `Flag`, `Many`),
attention heads (`Lookup` with `latest` / `all` / `wild`, `Count`) and MLP rules (guards and writes); the reference
`Interpret`, the compiler `Compile`, the forward pass `Forward`; ready programs: transitive closure over sentences
(`Closure`) and exact quotes (`Quotes`); domains for model readers (`domain/`).

**The experiments** (`experiments/*.sc`) — hand-built transformers, each checked against a reference:

- a **CEK machine** for the λ-calculus — equal to an interpreter in the answer and the step count;
- **effect handlers with multi-shot continuations** inside it, up to ∃x∃y∃z (822 steps);
- **binary pointers** — 85× fewer neurons;
- **Datalog** bottom-up, **or-parallel SAT**, and **DPLL** with all branches propagated at once — refuting
  n = 20, m = 85 in 15 branch nodes against a million leaves;
- **confidence-gated branching** — 2–4× less search, never a wrong answer;
- a register machine, counting letters over BPE tokens, Wordle's filter, decoding masks.

**Beside a real model** (`mlx`, specs) — the compiled weights on Apple's GPU through
[mlx-scala](https://github.com/sergey-scherbina/mlx-scala); exact quotes as a decoding mask beside Qwen3-4B;
**the bridge**: a model's states read by probes into a compiled block — a count exact at 80 items where the model
alone and thinking get 0 of 6, deduction over sentences read at 100% of 1,230; **the bus**: a standard interface
between a model and its modules — a skill file trained on one model read *unchanged* on Qwen3-1.7B through a linear
map; **forge**: a small model trained in Scala for one project's code.

## In use: okay-desk

[**okay-desk**](https://okay-effects.github.io/okay-desk/) — chat with your data in the browser, offline. A 146 MB
core of Qwen3-0.6B reads the question (nothing is generated), readers turn its states into a program, an exact
engine runs it; reachability questions are answered by a transformer compiled from `Closure`, in the page. Numbers
come only from the engine. See [docs/okay-desk.md](docs/okay-desk.md).

## Getting started

Requirements: JDK 21+, sbt, [scala-cli](https://scala-cli.virtuslab.org/) for the experiments.

```sh
sbt coreJVM/test        # the DSL, the compiler and the laws
sbt coreJS/test         # the same in Scala.js
bin/check               # every experiment; exit 1 on a mismatch
```

The `mlx` and `forge` modules run on Apple silicon and need mlx-scala's native library (`MLX_SCALA_LIB`, else
`../mlx-scala/native-lib/out/lib`) — see [docs/modules.md](docs/modules.md).

## Status

Research, moving fast. The library covers lookups, counts, rules, sets, the closure and quotes, and the machines —
a register machine, the CEK machine with effect handlers, or-parallel search and DPLL guided by advice — as
programs of the DSL, each with its law. Every number in the docs and specs comes from a test,
an experiment or a bench, and says which. The design records: [`specs/`](specs/) (start with
[`specs/inlay.md`](specs/inlay.md)); the plan: [`BACKLOG.md`](BACKLOG.md), [`SPRINT.md`](SPRINT.md).

Related: Tracr (compiling RASP into weights), RASP, looped transformers, DeepProbLog, Scallop —
see [docs/related.md](docs/related.md).

Part of [okay-effects](https://okay-effects.github.io/).
