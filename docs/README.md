# okay-inlay — documentation

okay-inlay compiles small programs into transformer weights: attention heads and ReLU neurons computed from a
program, so a transformer performs that program **exactly** — and checks, for every program, that the weights give
what an ordinary interpreter gives. Around that core it holds the research on how such exact blocks meet a real
language model: readers of the model's states, a standard interface between a model and its modules (the bus),
and small models trained or cut for a task.

It is a research repository (Scala 3, sbt, scala-cli). Every number in these documents comes from an experiment or
a test in this repository or in okay-desk, and the page that says it names which.

## Read in this order

| Document | What it covers |
|---|---|
| [concepts.md](concepts.md) | Why a transformer can be *built*; how a head becomes a lookup and a neuron a gate; exactness; the law |
| [guide.md](guide.md) | Writing programs with the DSL: fields, heads, rules, compiling, running, checking — with working code |
| [api.md](api.md) | Reference of the core library: every public type and method, how each op is compiled |
| [experiments.md](experiments.md) | The experiments (`experiments/*.sc`): CEK, effect handlers, Datalog, DPLL, Wordle… and their numbers |
| [effects.md](effects.md) | Effects: handlers with multi-shot continuations compiled into weights; the weights as one handler of a program |
| [modules.md](modules.md) | The other sbt modules: `mlx` (the weights on Apple's GPU, the decode port), `forge`, `web`, the benches |
| [bridge-and-bus.md](bridge-and-bus.md) | Exact blocks beside a real model: readers, the bridge, the model core, the bus and its adapter |
| [okay-desk.md](okay-desk.md) | The first application: chat with your data in the browser, built on all of the above |
| [results.md](results.md) | Every measured result in one place, with its source |
| [related.md](related.md) | Related repositories and related research |

## In one minute

```scala
import okay.inlay.*

// "how many orders are unpaid?": each row a position with its status, the question a position asking "unpaid"
val statuses = Vector("paid", "unpaid", "unpaid", "paid" /* … one per row */)
val status = Cat("status", Vector("paid", "unpaid"))
val ask    = Cat("ask", Vector("paid", "unpaid"))
val count  = Cat("count", (0 to 64).map(_.toString).toVector)
val program = Program(Seq(status, ask), Seq(Layer(heads = Seq(Count(count, Seq(ask), Seq(status))))), maxLen = 64)

val weights = Compile(program)                                   // matrices: one attention head and one MLP
val rows = statuses.map(s => Map("status" -> Val.C(Some(s)))) :+ Map("ask" -> Val.C(Some("unpaid")))
Interpret.run(program, rows).last("count")                       // the reference (on the 40 rows of OrdersCountSuite: "14")
Forward.run(program, weights, rows).last("count")                // the weights, float32: the same, exactly
```

On 40 real rows this block has a residual of 73 dimensions, 1 layer and 29,492 parameters, and gives 14 — the
number Qwen3.6-35B answered as 15 without thinking (`core/src/test/scala/okay/inlay/OrdersCountSuite.scala`).

## Build and run

```sh
sbt coreJVM/test          # the DSL, the compiler and the laws on the JVM
sbt coreJS/test           # the same code on Scala.js (the browser)
bin/check                 # every experiment (scala-cli), exit 1 on a mismatch
sbt mlx/test              # the weights on Apple's GPU (needs mlx-scala's native library, see modules.md)
```

Plain `sbt test` at the root also runs `mlx` and `forge`, which need mlx-scala's native library
(`MLX_SCALA_LIB`, else `../mlx-scala/native-lib/out/lib`).

## Where the deeper material is

The documents here explain and point; the design records are the specs, each with its history:
`specs/inlay.md` (the idea and every experiment), `specs/dsl.md` (the library), `specs/bridge.md` (exact blocks
beside a model), `specs/core.md` (a small core model), `specs/bus.md` (the interface), `specs/quotes.md`,
`specs/symbols.md`, `specs/forge.md`, `specs/domains.md`, `specs/datalog.md`, `specs/reason.md`,
`specs/assistant.md`. The boards: `BACKLOG.md`, `SPRINT.md`.
