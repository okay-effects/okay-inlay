# okay-forge — training a small model for a project (spec, 2026-11-04)

Named okay-forge by the operator (2026-11-04; it was "okay-refine" — a word with other meanings): it forges a small
model for a project — tasks from the project's index, a verifier, training on what passes.

The operator's goal (2026-11-04): not a bigger model — a small one (Qwen3-4B) made good at the work, by changing the
MODEL, with inlay as the platform. The measured gap (specs/symbols.md): on symdex's API, Qwen3-4B with the best
prompt (the members it needs, their real calls, thinking, five rounds with the compiler) compiles 9 of 10 and behaves
7 of 10; the misses are habits and meaning — it wraps its answer in its own `object Equivalence { … }` though told
not to, reimplements what it should call, writes `split("(")` (a regex). A mask cannot fix a habit (it goes around
it); knowledge in the prompt fixed most names. What is left is in the weights.

## Four levers, cheapest first, all in Scala, all measured

1. **Steering** (no training): the habit as a direction in the residual stream — the mean difference of hidden states
   between contrasting answers (calls the API / reimplements it; no wrapper / `object X { … }`), added at a layer
   while decoding. A hook in mlx-scala's forward pass. Tells cheaply whether the failure is a direction at all.
2. **Training in Scala**: mlx-c's `mlx_value_and_grad` bound through okm (a C trampoline calling back into Scala: an
   FFM upcall on the JVM, a `CFuncPtr` natively); LoRA adapters on Qwen3's projections; AdamW; cross-entropy on the
   answer's tokens. The adapter a safetensors file beside the model, loaded by mlx-scala, served by okay-mlx.
3. **Data from verifiers, not people**: tasks generated from symdex's index for any project — every public member:
   "write f using X.m", its check f against X.m on generated inputs (`equivalent`'s generators). The model's own
   attempts kept when the compiler and the check accept (rejection sampling); its repairs (error → fix) kept as
   trajectories; a bigger model (Qwen3.6-35B through the gateway) as the teacher where the 4B fails. Train on part of
   one project's members, evaluate on held-out members and on OTHER projects (okay-datalog, okay-guard): a habit
   learned, not an API memorised.
4. **Compiled modules** (inlay proper), once 2 works: the symbol table as a compiled lookup block (the DSL), its
   output entering the model's residual through a trained adapter — the knowledge exact, its use learned.

## The measure

SymbolsBench's behaviour checks (the 4B base: 7 of 10 at best), more tasks generated per (3) with a held-out split,
other projects for transfer; and no regression elsewhere (a fixed set of general Scala tasks, before and after).

## Stages

1. Steering: hidden states at a layer (a hook), the direction from contrasting pairs, decoding with it; SymbolsBench.
2. Autograd: `value_and_grad` in mlx-scala (JVM, then Native); a toy (a linear layer learns a line) and one step on
   Qwen3-4B that lowers the loss.
3. LoRA on Qwen3 + AdamW + the adapter's save and load.
4. The task generator and the verified data (rejection sampling, repairs, the teacher).
5. Train, measure (held-out, transfer, regression).
6. The compiled module with a trained adapter.

## Stage 1, steering: no gain (2026-11-04, `SteerBench`)

The direction = the mean hidden state of the reference solutions minus that of the model's own failed answers, per
task, the evaluated task left out; added to every position after a layer. Qwen3-4B, SymbolsBench's ten tasks, the
focused prompt without thinking, one round:

| | compile | behave |
|---|---|---|
| base | 6 | 5 |
| layer 9, c = 2 / 4 | 6 / 6 | 4 / 3 |
| layer 18, c = 2 / 4 | 5 / 2 | 4 / 0 |
| layer 27, c = 2 / 4 | 7 / 3 | 5 / 2 |

Never above the base; strong steering breaks the code. The pairs differ in content as well as in habit (a reference
and a failure of the same task share little text), so the mean difference is mostly content — a cleaner contrast
(the same answer with and without the wrapper) might still find a direction; parked. Stage 2–3 (training in Scala)
done instead: mlx-scala `Mlx.valueAndGrad` (a C trampoline calling back into Scala, JVM and Native), Qwen3 LoRA
adapters, `Lora` (AdamW, the answer's cross-entropy): one example's loss 3.34 → 0.0001 in 20 steps, 0.38 s a step.

## Stages 4–5, first run: a habit learned (2026-11-04, `ForgeBench`)

25 tasks generated from symdex's index — each usable public member of the objects SymbolsBench does NOT ask about
(Archive, Hook, … — ten objects): "symdex's object `X` has `m` — <its doc>. Write `def doM(…): T` with it", the answer
the member called (`= X.m(a, b)`), in the evaluation's own prompt format. A LoRA adapter (rank 8, q/v/o on all 36
layers, ~4.4M parameters) trained on them in Scala: two epochs, 59 s, the loss 0.106 → 0.0001. SymbolsBench's ten
tasks (other objects, never trained on), Qwen3-4B, the focused prompt without thinking, one round:

| | compile | behave |
|---|---|---|
| before | 6 | 5 |
| after | 8 | 7 |

The two gained are the habit's: task 8 (it had declared its own `object Tasty`) and task 9 (its own `object
Equivalence`) — now one-line calls. One minute of training on generated delegations, and the small model without
thinking and in one round does what it did before only with thinking and five rounds (7 of 10). Left: meaning —
`Equivalence.Verdict(out).equivalent` (the case class for the parser), `split(' ')`, `Exec.run[List[String], Ran]`.

Not yet shown: that it holds on more tasks (ten, greedy, one run), that nothing else got worse, that it adds to
thinking and rounds. Next: (1) more evaluation — tasks generated the same way from held-out objects, each checked
against the member itself; (2) a regression set of general Scala tasks before and after; (3) the adapter under the
best arm (thinking, rounds, tests); (4) richer training data — the model's own verified answers, its repairs, other
projects (okay-datalog, okay-guard); (5) save and load the adapter (safetensors), serve it through okay-mlx.

## The tool (operator, 2026-11-04): training a model for a project, end to end

"A really working result, and a tool for developing such technologies further — to develop models efficiently and
purposefully." `okay-forge` — one pipeline, each step a command, each result a row in a history:

1. **tasks** — from a project's symdex index (any project with SemanticDB): every public member whose parameters the
   generators cover becomes tasks — a delegation ("write f with X.m"), and compositions from templates by its return
   type (`.size`, `.isEmpty`, `.headOption`, `.toList.sorted`, `.getOrElse(…)`, `.map(_.toString)`, …) — each with
   its REFERENCE expression and a CHECK: f against the reference on generated inputs (symdex's `Check.scala`
   generators, `compareN`: every input up to a size and random ones). Tasks whose reference does not compile or has
   no generator are dropped. Split by OBJECT: train objects, held-out objects.
2. **eval** — a model (and an adapter) on a task set: compile and behave per task, in batches (one compilation for
   many, the failures alone again); the prompt as the agent gives it (focused API, its real calls).
3. **attempts** — the model answers the training tasks; the verifier keeps what passes (its own distribution), the
   failures with their errors (for repairs).
4. **train** — LoRA on references + verified attempts (+ repairs later), in Scala; the adapter saved (safetensors).
5. **regression** — a fixed set of general Scala tasks with checks, before and after: nothing else lost.
6. **serve** — the adapter loaded by mlx-scala, served by okay-mlx, used by okay-agent.

Every number in history.tsv: model, adapter, task set, compile, behave, time.

## Transfer, first full run (2026-11-04, `TransferBench`, Qwen3-4B)

Training: 319 tasks from eight other projects, each checked against its reference (okay-scala 11, okay-datalog 11,
okay-store 53, okay-proof 45, okay-guard 171, okay-llm 21, okay-mcp 7; okay-simple and okay-ui 0 — their API is of a
higher kind). Evaluation: symdex's 55 usable tasks (a project never trained on) and the 20 general tasks (regression).
Greedy, no thinking, one round. LoRA rank 8 on q/v/o of all 36 layers, two epochs.

| arm | symdex compile | symdex behave | general behave |
|---|---|---|---|
| base | 52 | 47 | 9 / 20 |
| refs (319 references) | 55 | **55** | 10 / 20 |
| refs + own (and 216 of the base model's own answers the verifier kept) | 55 | **55** | 9 / 20 |

On a project it never saw, the adapter takes the 4B from 47 to 55 of 55, and the general tasks do not get worse (9 →
10 → 9: within one task of noise). The base model's own verified answers add nothing on this measure (already at the
ceiling). The run took 1 h 55 min, most of it the verifier's compilations (a task set checked once, then cached).

What it does not show yet: the tasks are generated from templates (a call, a size, an emptiness …) — the same kinds in
training and evaluation, so part of the gain is the task's form. Next: the adapter on SymbolsBench's hand-written
tasks (other kinds, meaning-heavy), and on Qwen3.5-4B; the regression set grown past 20.
