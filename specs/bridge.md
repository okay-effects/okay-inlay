# The bridge — learned interfaces to exact machines (spec, 2026-11-04)

Status: planned (operator's direction, 2026-11-04: "something much bigger": a model that works more exactly AND
learns better). Builds on specs/inlay.md (the machines), specs/dsl.md (programs → weights, the law), specs/forge.md
(training in Scala). The question it answers first: can the codes of a TRAINED model reach the codes of a COMPILED
circuit through a small learned map — and does the composition then do what neither part does alone?

## 1. The idea: a model built like software

What can be specified is compiled (exact by construction, the law `Forward(Compile(p)) == Interpret(p)`); what
cannot be specified is trained; the boundary between them is typed and small. Three parts:

- **M** — the trained model (Qwen through mlx-scala), its residual stream `h` at a layer, its logits;
- **C** — a compiled circuit (a DSL program): fields in one-hot / ±1 codes, heads and rules, no error;
- **E** (the writer) — a linear map from `h` at layer ℓ_in to C's INPUT fields; **D** (the reader) — from C's OUTPUT
  fields to M (a logits bias through the decode port, or an addition to the residual at ℓ_out).

The property this buys: **error is attributable.** C contributes none, so every error of the whole lives in E and D —
small linear maps, measured on their own, trained from tens of examples (they are probes, not transformer layers).
A trained model never tells where it went wrong; this one does, and marks a result that rests on an unsure read.

## 2. Three regimes of exactness

1. **Mask** (done: `Decode.sc`, `WeightsPort`, `Names`) — C's output restricts the logits. A guarantee, not
   knowledge: Qwen3-4B derails or goes around (specs/symbols.md). Learning fixes exactly that: LoRA UNDER the mask,
   so M learns good continuations inside the allowed set instead of fighting it.
2. **Read** (new; BACKLOG 1 generalized from Datalog to every machine) — E reads a relation / predicate from M's
   states; C counts, joins, deduces, searches over it exactly; D returns the result. E is trained as a probe against
   gold facts — NO gradient through C (hard attention at large λ has none; none is needed). What M learned on short
   inputs holds on long ones, because C does the counting, not M: length generalization where a circuit exists.
3. **Write** (later; inlay.md §8.4) — M writes typed records (facts, clauses) into positions that C reads exactly; the
   interface is a token grammar (regime 1's mask). Chain-of-thought becomes program state: everything derived from
   the records is exact, and the records are checkable.

## 3. How it learns better

- **Few parameters per skill**: a skill is a DSL program plus E and D (kilobytes), not an adapter over every layer.
- **A verifier inside**: C checks as well as derives (a violated clause, a move against the hints). Proposals M makes
  are accepted or rejected by C with no labels — okay-forge's loop (Verify on the CPU) brought to every token.
- **Compiled priors**: a small model initialized with circuits (count, induction, lookup) and then trained; measured
  against random initialization on examples needed and on length generalization (RASP-L's failure, exact here by
  construction). If the gain is in orders of magnitude, it is the fundamental result of this line.
- **Crystallization** (later): a learned fuzzy circuit found by matching against a compiled one (the compiler as an
  oracle for interpretability), then replaced by the exact one — more reliable without retraining; and the reverse
  swap where flexibility is needed. One program, two handlers (Compile / Train) with one interface.

The limit, stated: understanding stays in M and stays soft. The bridge makes everything AFTER a read exact; a wrong
read gives an exactly wrong answer — but a known one, attributed to that read.

## 4. Experiments (each self-checking, numbers into this spec from their output)

**bridge-1 — count with a neural predicate (regime 2, the first and the deciding one).** Task: a list of n short
items (reviews, log lines, statements); "how many are negative / errors / about X"; the predicate is M's judgement,
the count is C's. Build: a probe E on Qwen3-4B's hidden state at each item's last token (`Qwen3.Hook`, as
SteerBench) → `Flag(hit)`; C = `Count` (selector width + the ladder) over the items' positions → `Cat(count)`; D =
the decode port forcing the answer's digits (or allowing them: `WeightsPort`). Train E on items with gold labels at
n ≤ 10 (a logistic probe; the layer chosen by held-out accuracy); evaluate at n = 5, 10, 20, 40, 80 on fresh items.
Measure: (a) the probe's accuracy per item, (b) the count's accuracy — arms: M alone (prompted, with and without
thinking), M + bridge, gold predicate + C (the ceiling: must be 100%, the law). Decided: the bridge beats M alone at
large n while the probe stays as accurate as at n ≤ 10 — the codes crossed; the count error at n = 80 is explained
by the probe's per-item error (binomial), nothing else — attribution holds.

**bridge-2 — learning under the guarantee (regime 1).** SymbolsBench (specs/symbols.md) with the `Names` mask on
during LoRA training (okay-forge's loop, the mask in the forward pass), then measured with the mask on: invented
members (already 0 held), behave, compiles — against LoRA without the mask (5 → 7 behave) and the mask without LoRA
(derails). Decided: trained under the mask, M stops fighting it (no `impure Symbols` style derails) and compiles
more than either alone.

**bridge-3 — compiled priors.** A small transformer (2–4 layers, d ≤ 256) from scratch in mlx-scala (autograd), two
inits: random, and with `Quotes.cheapBits`' induction block / a `Count` circuit inlaid (the rest random); trained on
copy / count tasks at lengths ≤ 32, tested at 64–512. Measure examples to 99% and accuracy by length. Decided: the
inlaid init needs far fewer examples and holds its accuracy at lengths the random init loses.

**Later** (from the results): bridge-4 Datalog with a neural relation over text (reachability stated in sentences);
bridge-5 write (records in context, the grammar as a mask); bridge-6 crystallization on a trained model.

## 5. Gotchas

- λ large means no gradient through C: train E and D with their own losses; end-to-end only with an annealed λ
  (soft → hard) or straight-through — not in v0.
- M's codes are not one-hot: E produces a one-hot by argmax (or a sign for ±1) before C; the probe's margin is the
  measure of how clean the read is — report it.
- fp16 / bf16 break `latest` (dsl.md): C's block runs in float32 beside M as `OnMlx` does.
- A fixed depth gives a window; recurrences need the loop (`Looped.sc`) or the state carried in tokens.

## 6. Results

**bridge-1, 2026-11-04** (`mlx/src/test/.../BridgeBench.scala`, `BRIDGE=1 BRIDGE_THINK=1`; Qwen3.5-4B-MLX-4bit;
`CountLawSuite`: `Count` to a cap of 100 equals the reference on 120 random lists up to n = 100). E: a logistic
probe on the FINAL layer's normed hidden state (mlx-scala's `hiddenStates`; Qwen3.5 has no per-layer hook yet) at
each review's last token, trained on 1,000 reviews from 100 lists of 10 (300 gave the same: the data is not the
limit), on one half of the phrases; tested on the other half, 6 lists per n:

| n | probe per item | bridge exact | alone exact | alone + thinking exact | gold + C | mean abs err bridge | alone |
|---|---|---|---|---|---|---|---|
| 5 | 96.7% | 5/6 | 3/6 | 6/6 | 6/6 | 0.17 | 0.50 |
| 10 | 96.7% | 4/6 | 4/6 | 6/6 | 6/6 | 0.33 | 0.33 |
| 20 | 97.5% | 4/6 | 1/6 | 6/6 | 6/6 | 0.50 | 1.17 |
| 40 | 97.9% | 3/6 | 1/6 | 3/6 | 6/6 | 0.50 | 1.17 |
| 80 | 97.1% | 1/6 | 0/6 | 0/6 | 6/6 | 1.33 | 2.00 |

- **The codes crossed**: a linear map reads the model's judgement at ~97% and keeps it from n = 5 to 80 — trained
  on lists of 10, as accurate on 80. **Attribution holds**: on every list the count equals the probe's hits (the
  assert), the gold flags count exactly (6/6 at every n) — C adds no error, all of it is the probe's.
- **Against the model alone** (no thinking): better mean error at every n, and the gap grows with n. **Against
  thinking**: thinking is exact up to n = 20, then falls (3/6 at 40, 0/6 at 80 — the length problem); the bridge's
  error at n = 80 is the probe's ~3 misses in 80, not length.
- **The bottleneck is the probe**, and it is known: the final normed layer serves next-token prediction, not the
  judgement; the misses are spread over phrases ("would not recommend", "overpriced", "not cheap but worth it"), not
  one pattern. More data did not help. Next: a per-layer hook for Qwen3.5 in mlx-scala and the probe chosen by
  held-out accuracy over layers (as SteerBench reads Qwen3's); then a sharper reader (two layers, or a few tokens).

## 6b. bridge-1 with the layer chosen (2026-11-04, the same run with `BRIDGE_THINK=1`)

Qwen3.5 got a per-layer hook in mlx-scala (`Qwen35.hook`, as `Qwen3.Hook`; 0.1.0 republished locally). The phrases in
three parts (train 12, validate 6, test 12 per polarity); a probe per layer on 600 items from 60 lists of 10; the
layer chosen on the validate phrases: **8 99.0%, 12 99.5%, 16 99.5%, 20 98.0%, 24 97.0%, 28 93.0%, final 96.5%** —
the middle holds the judgement, the end serves the next token. Layer 12, on the test phrases:

| n | probe per item | bridge exact | alone exact | alone + thinking exact | gold + C | mean abs err bridge | alone |
|---|---|---|---|---|---|---|---|
| 5 | 100% | 6/6 | 2/6 | 5/6 | 6/6 | 0.00 | 0.67 |
| 10 | 100% | 6/6 | 3/6 | 5/6 | 6/6 | 0.00 | 0.67 |
| 20 | 99.2% | 5/6 | 5/6 | 4/6 | 6/6 | 0.17 | 0.17 |
| 40 | 100% | 6/6 | 1/6 | 0/6 | 6/6 | 0.00 | 1.00 |
| 80 | 99.8% | 5/6 | 0/6 | 0/6 | 6/6 | 0.17 | 3.00 |

Two misses in 930 test items ("not perfect, but I like it a lot", "fine for a day, then it quit"). **Decided as the
spec asked**: the bridge is exact where the model alone (0/6) and with thinking (0/6) are not at n = 40 and 80, the
probe as accurate at 80 as at 10, the one error at 80 the probe's one miss. ~25 min with the thinking arm.

## 7. The framework: exact reasoning the model uses on its own (operator's direction, 2026-11-04)

The thesis to demonstrate: the model gets exact inference over what it understands, with a proof, and with the
name of the reading that was wrong. One machine (entities and facts, Datalog, SAT, counts, proofs), three sources of
facts, any task that is relations + rules + constraints over judgements — the model states the program itself (it
writes Datalog well: symdex's `query`).

**Readers** (sources of a neural relation, each a handler; every fact carries its source and a confidence):
1. **Text** — the model writes the facts (okay-reliability's `assert` today); general, the error in its writing.
2. **Probe** — a trained linear map on a middle layer (bridge-1: 99–100% at layer 12); exact, needs labels.
3. **Ask** — no training: a predicate stated in words, the model's yes/no logits read at each item's position inside
   one pass, calibrated. The predicate is text, the reading a number — what makes the framework self-serve.

**Where**: okay-inlay proves it (the machine in weights, the readers, attribution — bridge-4 below); okay-datalog
holds the program (readers as EDB sources with a confidence per fact; the Viterbi semiring for a conclusion's
confidence); okay-reliability is the framework the model uses (`assert` from text / probe / ask; `query`, `solve`,
`why_not` with proofs and "rests on" marks; packs).

**Demonstration**: three packs on one program, each a benchmark A / T / E (the model alone / with the framework /
gold facts) on okay-agent with Qwen:
1. **code** (first) — on a real okay-* repository: "what does this change reach, and does unchecked input reach it";
   the graph's facts exact from symdex, source / sink / sanitizer judgements by Ask; the answer a call chain with
   its confidence.
2. **statements** — 50–200 sentences in prose, 5–10 hops, contradictions with the two sentences to blame: the
   model that cannot contradict what it read.
3. **reconciliation** — invoices and payments in mail, discrepancies by line.

**Steps**: bridge-4 (okay-inlay: Datalog with a neural relation in weights over sentences; Probe and Ask readers;
every derived fact attributed to its readings; BACKLOG 1) → okay-datalog's reader source and Viterbi → okay-reliability's
readers in `assert`, the code pack on symdex, the A / T / E benchmark → the block beside Qwen at layer 12 through
okay-mlx, so okay-agent gets the inference on every token, not by text.

## 8. bridge-4, first run (2026-11-04)

The DSL grew what the closure needs (each under the old suites, all green): `Program(causal = false)` (a block of
its own: every position attends to every position, as Datalog.sc), a set query against a one-hot key (`Many` vs
`Cat`: membership), `Write.Union` into a `Many` (min(1, Σ) by two ReLUs per value), guards `In` / `NotIn`; a rule
may not read a set gathered by a head of its own layer (the ladder and the rule would share one MLP). `Closure`
(core): a sentence a position with its two names in order and the reader's judgement "the first is the superior";
the edge by rules, reachability doubled per round (a head whose query is the set reached so far, key the edge's
head, gathering the sets reached; a union), the sentences each set rests on the same way (`p`, attribution), a
question position gathering what starts at `qa` and answering `yes` with `on` the sentences it rests on, `cycle` at a
sentence whose head is reachable from its tail. `ClosureSuite`: weights == interpreter == plain search on 40 random
graphs, answers, attribution sets and cycle sets alike — at the first run. 12 layers, 921 dims for 12 names and 40
positions; 14 layers, 2,930 dims for 16 names, 128 positions, 2⁵ hops (the bench).

`ClosureBench` (Qwen3.5-4B-MLX-4bit, no thinking arm — a GPU "innocent victim" error killed the thinking run;
rerun without): 20 phrasings (10 "above", 10 "below"), train 4 / validate 2 / test 4 per polarity; the probe at layer
12 on 600 sentences; ASK the model's yes − no logits on one sentence ("is X the superior of Y?"); questions 3+ hops
or unreachable; 6 instances per n, 2 of them with a cycle:

| n | probe per sentence | ask per sentence | alone | bridge probe | bridge ask | gold + C | cycles found | wrong yes explained |
|---|---|---|---|---|---|---|---|---|
| 15 | 90.0% | 86.7% | 5/6 | 4/6 | 2/6 | 6/6 | 2/2 | 0/0 |
| 30 | 90.6% | 83.9% | 4/6 | 6/6 | 4/6 | 6/6 | 2/2 | 0/0 |
| 60 | 91.7% | 82.5% | 3/6 | 5/6 | 3/6 | 6/6 | 2/2 | 1/1 |
| 100 | 90.7% | 83.5% | 3/6 | 3/6 | 3/6 | 6/6 | 2/2 | 3/3 |

- The exact part is exact: gold 6/6 at every n, the GPU equal to the reference on every instance (asserted), every
  cycle's sentences found (8/8), and every wrong "yes" rests on a misread sentence (4/4 — attribution).
- **The readers are the bottleneck, and a relation's DIRECTION is harder to read than a polarity**: the probe
  76.7% on validate, 90–92% on test phrasings; ask 82–87%. Ten misreads in 100 sentences break most long paths, so
  the bridge is not yet above the model alone at n = 100 (3/6 each). The lesson: a two-argument relation needs a
  two-argument reader. Next: (a) ask both orders ("X over Y?" minus "Y over X?"), (b) a probe on BOTH names'
  positions (or the pair's states concatenated), (c) more phrasings for the probe, (d) the model's own one-line
  rewrite into a canonical form read by exact heads. Target: 99% per sentence, as bridge-1's polarity reached.

## 9. Effects, other packs, small models in the browser (operator's questions, 2026-11-04)

- **It is effects with handlers.** The program (`Closure`, the DSL) is data; `Interpret` (the reference), `Forward`
  (CPU), `OnMlx` (GPU), later WebGPU are handlers of the same program, equal by the law. The reader is an effect
  too — `Judge(sentence): Boolean` — with handlers Probe, Ask, Text, Gold; the LLM is one handler of it. The bridge is
  a program over Read / Deduce / Answer where only handlers change. To do: state it as `Effectful` on okay-simple, so
  the MCP tool, the block in weights and the browser are one program, run in one line.
- **Not only reliability.** okay-guard: a policy over tool calls is Datalog; the facts about a call ("destructive",
  "leaves the project", "a secret") are judgements — an Ask reader over the call's text; the exact policy on the CPU
  or in weights; a decision with the reading it rests on; the journal with proofs. And the mask regime: a forbidden
  form of a call cannot be generated. Likewise okay-facts, symdex, okay-agent.
- **A small model for the browser is distillation of the PREDICATE, not of the LLM.** The exact part needs no
  training and is already a small transformer (the closure: 14 layers, 2,930 dims, low-rank heads — megabytes);
  `Forward` is plain Scala, so Scala.js runs it as is; WebGPU for speed, WebGL2 as the fallback (float32 keeps hard
  attention). The reader is trained small: Qwen labels generated data, a classifier of 1–10 MB learns it. Closed
  domains (log lines, tool calls, reviews) suit small readers; the open vocabulary is the open question.
- **Small task models in the browser**: the DSL program → compiled; generated data → labelled by Qwen (or probes) →
  a small reader trained (okay-forge, MLX autograd) → reader + block → a Scala.js / WebGPU handler. okay-forge for the
  browser; the thesis "a model built like software" literally.

## 10. Does it make sense at all? (the operator's question, 2026-11-04 — the honest ledger)

What is established: exact composition over the model's judgements with attributable error (bridge-1: exact where
the model alone and with thinking fail; bridge-4: the exact part perfect, every wrong answer traced to a reading).
What is NOT established, and where the whole thing can turn out empty:

1. **"In weights" must buy something a CPU tool does not.** The same programs run on a CPU in microseconds. The
   block in weights is worth it only in three cases: (a) the mask — a forbidden token cannot be generated, which no
   tool can do after the fact; (b) training THROUGH the interface (compiled priors, learning under the guarantee);
   (c) one artifact to deploy with no LLM beside it (the browser). If none of the three matters for a use, the MCP
   tool (okay-reliability as it is) is the product and the weights are research.
2. **Readers must reach ~99%.** Below that, long inference breaks (bridge-4 at 90%: not above the model alone at
   n = 100). If relations cannot be read that well from a 4B model's states, the model WRITING facts as text plus a
   CPU solver beats the bridge, and that is okay-reliability today. bridge-1 reached 99.2–100%; bridge-4 has not.
3. **Scale erodes the length argument** (frontier models count and chain better every year), but not exactness nor
   attribution; and the setting here is local 4B models — private, cheap, offline — where the gap is real now.
4. **Novelty is narrower than it looks**: Tracr compiles programs; Scallop / DeepProbLog do differentiable logic on
   the CPU. What is new is the exact machine as a transformer block beside a real LLM, fed by linear readers, at the
   token level, one architecture — less explored, but the claim must be made that precisely.

The test that decides, in order: (i) a two-argument reader to 99% (if readers cannot get there, the bet is weak);
(ii) the browser demo — a ~10 MB artifact doing exact inference over text offline (if that works, there is a product
nobody has); (iii) learning under the mask (bridge-2) — the one gain no tool can give.

## 8b. bridge-4, the two-argument reader (2026-11-04, three more runs)

1. **Ask in both orders** ("X over Y?" minus "Y over X?") and the **probe on three positions** (the residual at the
   first name, the second name and the last token, concatenated): ask 83–87% → 96–98%, probe 90–92% → 92–96%
   (validate 80%: four training phrasings only).
2. **Twelve more training phrasings per polarity** (validate and test unchanged): the probe's validate 80% → 96.7%.
   A third reader, ASK-WHO (the two names' logits after "who is the superior"), 79–83%: weak on the 4B.
   **The misses are systematic by phrasing and DIFFERENT per reader**: the probe fails "X's boss is Y" (38),
   "X is Y's direct superior" (24), "X is managed by Y" (15); ask fails "X is the manager of Y" (24) and "X signs
   off on Y's work" (18); who fails "manager of" 108 times.
3. **BOTH: the probe's score over its spread plus ask's over its spread** (spreads on the validate sentences: 6.52,
   4.32): **100.0% on all 1,230 test sentences, zero misses**, and the bridge exact at every n:

| n | probe | ask | who | both | alone | bridge probe | bridge ask | bridge both | gold + C | cycles | wrong yes explained |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 15 | 96.7% | 97.8% | 78.9% | 100% | 5/6 | 5/6 | 5/6 | 6/6 | 6/6 | 2/2 | 0/0 |
| 30 | 96.1% | 96.1% | 80.0% | 100% | 4/6 | 5/6 | 5/6 | 6/6 | 6/6 | 2/2 | 1/1 |
| 60 | 94.4% | 96.9% | 83.1% | 100% | 3/6 | 5/6 | 4/6 | 6/6 | 6/6 | 2/2 | 1/1 |
| 100 | 90.8% | 96.3% | 83.2% | 100% | 3/6 | 3/6 | 4/6 | 6/6 | 6/6 | 2/2 | 3/3 |

**The first fork of §10 is passed**: a two-argument relation is read at 100% on unseen phrasings by two readers that
fail on different ones — one trained (the probe), one not (ask) — and the closure over 100 sentences is exact where
the model alone is right half the time. The lesson for the framework: readers are an ensemble by construction; a
reading two readers disagree on is the one to mark "unsure" and to show.

## 11. The browser (operator's direction, 2026-11-04)

One vehicle, one architecture, packs as specializations: a page with a small model in the browser (Qwen3.5-0.6B /
1.7B at 4 bits, ~0.4–1 GB cached once; WebGPU, WebGL2 the slow fallback; the LLM by an existing WebGPU runtime
first, our own on Scala.js later if needed), history and the fact store in IndexedDB, our machines as Scala.js
handlers (`Forward` is plain Scala; WebGPU for speed), chat on top. The small model is weak alone; beside the exact
machines it becomes a small model that is exact where big ones are not — the thesis as a product. Packs:
1. **data** (first; offline, no extension): CSV / JSON / orders / logs pasted; a question in words; the model writes
   the program (Datalog / SQL), the exact handler runs it, the answer with the program and its source rows — no
   number ever computed by the model. okay-datalog, okay-store.
2. **research** (an extension: it opens searches and pages, free, no server — a page cannot read google.com for
   CORS; Brave's API or a Worker proxy are the alternatives): a reader puts facts into the store with sources, the
   machine merges, counts, finds contradictions; answers quote verbatim under the Quote port — the mask regime, the
   gain no tool gives.
3. **incidents** in okay-reliability's serverless web client (§7's pack on logs; the generator labels the reader).
First the riskiest step: the machines on Scala.js in a browser (a day's smoke test with `Closure`), then the LLM
runtime choice.
