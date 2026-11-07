# Modules: mlx, forge, web, the benches

The sbt build (`build.sbt`, Scala 3.3.8, `-Werror -Wunused:all`):

| Module | Artifact | Depends on | What |
|---|---|---|---|
| `core` (JVM + JS) | `okay-inlay` | — | the DSL, the reference, the compiler, the forward pass, `Closure`, `Quotes`, `domain/` ([api.md](api.md)) |
| `mlx` | `okay-inlay-mlx` | `core`, mlx-scala 0.1.0, mlx-scala-llm 0.1.0 | the weights on Apple's GPU; the quote block beside Qwen; the benches against real models |
| `forge` | `okay-forge` | `mlx` | training a small model for a project: tasks from symdex, verification, LoRA in Scala |
| `bridge` (JVM + JS) | `okay-inlay-bridge` | `core`, okay-simple's `okay-effects-machine` 0.1.1 | the bridge as a program of effects: reading and deducing as effects, the deducers (search, the reference, the weights) as handlers ([effects.md](effects.md)) |
| `mcp` | `okay-inlay-mcp` (not published) | `bridge`, okay-mcp 0.1.0 | the bridge as an MCP tool (`above`) over stdio |
| `web` | `okay-inlay-web` (not published) | `bridge` (JS) | the browser smoke: the bridge program in a page |

`root` aggregates `core`, `bridge`, `mcp`, `mlx` and `forge` (not `web`).

**mlx-scala's native library.** `mlx` and `forge` fork their JVMs with `-Dokm.lib=<dir>/libokm.dylib`, the
directory being `$MLX_SCALA_LIB` or `../mlx-scala/native-lib/out/lib` (built by mlx-scala's `native-lib/build.sh`).
Never run two MLX jobs at once on one GPU (Metal kills one of them).

<a id="mlx"></a>
## mlx — the compiled weights on Apple's GPU (`okay.inlay.gpu`)

```scala
final class OnMlx(val w: Weights, dtype: Dtype = Dtype.Float32):
  def run(x0: Array[Array[Float]]): Array[Array[Float]]                     // the residual stream, row by row
  def records(p: Program, inputs: Vector[Interpret.Record]): Vector[Interpret.Record]
  def last(p: Program, inputs: Vector[Interpret.Record]): Interpret.Record
  def close(): Unit
object OnMlx:
  def run(p: Program, w: Weights, inputs: Vector[Interpret.Record]): Vector[Interpret.Record]
```

The matrices go to the GPU once; each head is `softmax(X·qk·Xᵀ + mask)·(X·ovᵀ)`, the MLP `ReLU(X·w1ᵀ)·w2ᵀ`. The law
is the same as on the CPU: `OnMlx.run(p, Compile(p), in) == Interpret.run(p, in)` (`OnMlxSuite`).

**`WeightsPort`** — the quote block beside a real model, as a decode port (mlx-scala's `Port`: `allowed`, `took`):

```scala
final class WeightsPort(prompt: Array[Int], source: Range, open: Int, close: Int, k: Int,
  prefixes: Int => Array[Int] = _ => Array.empty, maxLen: Int = 1024) extends mlx.llm.Port
```

Per prompt it builds a compact vocabulary (the prompt's distinct tokens, the markers, "other"), compiles
`Quotes.cheapBits`, and runs it with `OnMlx` at every generated token; inside a quote, `allowed` is the set of token
ids that continue a span of the source. With Qwen3-4B answering "which orders failed" over a log: 160 steps, 93
inside a quote, the allowed set equal to the reference at every step, 2.8 s, 81 words, 8 heads (`QwenQuoteSuite`).

**Half precision** (`HalfSuite`): float32 805 of 805 positions exact; float16 768 of 880 (112 not even decodable);
bfloat16 705 of 832 (127). The `latest` scores (~65,536) pass float16's maximum and bfloat16's resolution — so a
block runs in float32 beside the model, through an engine hook, not inside a half-precision engine.

**The benches** (munit classes, opt-in by environment variables; without them they are skipped):

| Bench | Switch | What it measures |
|---|---|---|
| `BridgeBench` | `BRIDGE=1` | bridge-1: a count whose predicate is read from Qwen3.5-4B's states by a probe ([bridge-and-bus.md](bridge-and-bus.md)) |
| `ClosureBench` | `CLOSURE=1` | bridge-4: transitive closure over sentences read by probe / ask / both |
| `SymbolsBench` | `SYMBOLS=1` | code without invented APIs: Qwen3-4B on 10 tasks over symdex, arms free / held / outline / sigs / usage / loop / best |
| `SteerBench` | `STEER=1` | steering directions in the residual (no gain) |
| `ForgeBench` | `FORGE=1` | LoRA on delegation tasks, then SymbolsBench again |
| `MaskBench` | `MASK=1` | bridge-2: LoRA plain against LoRA under the mask |
| `DomainBench` | `DOMAINS=1` | readers for the `Units` domain on cut19's bus: keywords, probes, margins, hybrid |
| `ModelBridgeBench` | `MODEL_BRIDGE=1` | the bridge with Qwen3.5-4B reading (`ModelJudge`: ask, probe, both) and the closure on the GPU |

Example: `BRIDGE=1 BRIDGE_THINK=1 sbt "mlx/testOnly okay.inlay.gpu.BridgeBench"`. Each bench's other variables
(model, layer, sizes) are documented at the top of its file.

## forge — a small model trained for a project (`okay.inlay.forge`)

The question: can a small model (Qwen3-4B) be made good at one project's code by changing the model — not the
prompt? The pipeline, all in Scala:

- **`Prepare(name, from, module)`** clones a project into `~/.cache/okay-forge/projects/<name>`, compiles it with
  SemanticDB and exports its classpath;
- **`Project`** reads the project through symdex (`named`, `defined_in`, types, members, signatures, calls);
- **`Tasks.from(project)`** makes tasks with checks from its members (call, size, empty, defined, right, length,
  not; ≤ 3 parameters; type parameters instantiated);
- **`Verify`** compiles each candidate beside the reference and compares them on generated inputs (symdex's
  `Check.compareN`), in batches of 24 split on failure: a verdict `compiles` / `behaves`;
- **`Forge`** prompts the model, keeps verified attempts, and trains a LoRA adapter in Scala (mlx-scala's `Lora`),
  saved as safetensors;
- **`General`** (20 general Scala tasks) and **`Meaning`** (40 hand-written tasks over four projects) are the
  regression and meaning sets.

There is no CLI yet; the pipeline runs through opt-in test classes (`TRANSFER=1`, `MEANING=1`, `PROBE=1`). Results
([results.md](results.md#forge)): LoRA trained on 319 verified tasks from nine other projects took symdex's 55 tasks
from 47 to 55 behaving; on 40 meaning tasks 19 → 27 behaving. Steering gave nothing.

## web — the browser smoke

`web/src/main/scala/okay/inlay/web/Main.scala`: the bridge's program (`Bridge.above`) in the page, run with the rules
as the reader and the compiled `Closure` (`Forward` on Scala.js) as the deducer. Sentences like "Ann manages Bob"
(12 names, known phrasings parsed by a regex — no model), a question "is Ann above Lou", an answer with the lines it
rests on, any contradiction cycle and the lines not understood; about 0.3 s on plain JS.

```sh
sbt web/fullLinkJS && cp web/target/scala-3.3.8/okay-inlay-web-opt/main.js web/page/ && open web/page/index.html
```

## bench/ — data, not code

`bench/forge/history.tsv`, `bench/forge/meaning.tsv`, `bench/forge/usable-*.txt` (tasks whose references behave,
per project), `bench/symbols/history.tsv` (274 rows of SymbolsBench runs). Adapters are kept beside them, not
committed.
