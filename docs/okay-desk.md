# okay-desk: the first application

**okay-desk** (https://okay-effects.github.io/okay-desk/, source `okay-effects/okay-desk`, its spec
`specs/data.md`) is a chat with your data that runs entirely in the browser, offline: open CSV/TSV/JSON files, ask a
question in English or Russian, get an exact answer. It is where okay-inlay's ideas meet a real model and real
users.

## How a question is answered

1. **A small model reads the question** — Qwen3-0.6B cut and distilled to a core (146 MB on the page), run by the
   page's own forward pass (okay-llm's `Qwen3Compute` on WebGPU, else WebGL2, else the CPU). It generates nothing:
   its states are read.
2. **Readers read the states** — a 4 MB skill file of linear and bilinear probes: the table, the form of the answer
   (count, sum, list, groups, order, limit), the columns, the condition. Exact rules decide first wherever the
   question or the data says it (a table or a value named in the question, a number after "over", "по каждому
   городу" → a group); the readers decide the rest; two readers (a probe and the model asked) are summed where a
   judgement is needed.
3. **The program is data** — a JSON query (relational, or Datalog), shown with every answer.
4. **An exact engine runs it** — okay-desk's relational engine, okay-datalog, or a **compiled okay-inlay block**:
   reachability queries over a two-column table are answered by the transformer compiled from `Closure`, run by
   `Forward` in the page (up to 60 rows and 40 names; else okay-datalog). The page says which one answered.
5. **The answer is said** from the result by a grammar, every number a cell of the result.

Numbers come only from the engine. The model never computes them.

## What okay-desk uses from okay-inlay

| okay-inlay | In okay-desk | Where |
|---|---|---|
| `Closure.program`, `Closure.inputs`, `Compile`, `Forward.run`, `Interpret.run`, `Val` | Datalog reachability answered by compiled weights; the law weights == interpreter == okay-datalog on 30 random graphs | `core/.../Weights.scala`, `WeightsSuite` |
| `domain.Domain`, `domain.Slot` | `TablesDomain`: the space of table questions as a domain | `core/.../TablesDomain.scala` |
| `domain.Kit` | pairs and measures over that domain | `TablesDomainSuite` |
| the bus, the core (specs) | the models the page loads and the skill file's contract | `bench/*`, `specs/data.md` §19–§31 |

## The readers and the `Read` effect

The readers never call a model directly; they perform an effect:

```scala
enum Read[X]:
  case States(ids: Array[Int], at: Vector[Int]) extends Read[Vector[Array[Float]]]  // the bus at positions
  case Logits(ids: Array[Int]) extends Read[Array[Float]]                          // the model asked
  case Encode(text: String) extends Read[Array[Int]]
  case Decode(ids: Seq[Int]) extends Read[String]
  case Prompt(system: String, user: String) extends Read[Array[Int]]
  case Note(words: String) extends Read[Unit]                                      // a step shown to the user

trait Reading:                       // a backend: one method, the operation answered
  def answer[X](op: Read[X]): X
```

Handlers: `MlxReading` (mlx-scala on the JVM, Apple's GPU), `ComputeReading` (the page's own forward on the JVM),
`OnQwen` (the browser). The `Readers` class: `train(pairs)`, `program(tables, question)`, `measure(pairs)`,
`measureByConstructor(pairs)`; the skill file is its probes' weights (`bytes` / `load`).

## The questions: a space of meanings

`Space` enumerates the fragment of queries over any schema (87,445 programs over the sample); `Phrase` says each
point in English or Russian, compositionally; training pairs are the space × the phrases. The readers are measured
constructor by constructor on held-out pairs. A data dictionary (`Words`, made once per dataset by a generating
model) gives a dataset's names in Russian ("Украины" → Ukraine, "ВВП" → the gdp table). Questions outside the
fragment (a share, two years compared, two things compared) are refused with the reason; when the readers hesitate
between two tables the page asks, and the person's choice is kept as a training pair.

## The numbers (okay-desk `specs/data.md`)

| Measure | Result |
|---|---|
| the sample, 13 questions × 2 languages, the core (JVM and the page on WebGPU) | 13/13 en, 13/13 ru |
| held-out generated pairs, read whole, the core | 99.0% (50% before the exact rules of §27) |
| people's questions on the World Bank GDP series (16 + 3 outside the fragment) | en 16 right, 3 refused, 0 wrong; ru the same with the data's words |
| Qwen3.6-35B on the same 40-row count, thinking off | 15 (the answer is 14) |
| a question on the JVM | ~0.5 s |

## Running it

```sh
sbt coreJVM/test uiJVM/test                   # the laws and the page program played on the JVM
scripts/page.sh                               # the page built and self-tested in a browser (port 8734 must be free)
scripts/publish.sh                            # to GitHub Pages
sbt "bench/runMain okay.desk.bench.RealBench" # the readers on the GDP series
```
