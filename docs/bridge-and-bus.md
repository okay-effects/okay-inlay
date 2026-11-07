# Exact blocks beside a real model: the bridge, the core, the bus

A compiled block computes exactly but understands nothing. A language model understands but computes loosely. The
work in `specs/bridge.md`, `specs/core.md` and `specs/bus.md` is about joining them so that the joint system keeps
the block's exactness and the model's reading — and so that its errors have an address.

## 1. The bridge (`specs/bridge.md`)

A model "built like software":

- **M** — the trained model;
- **C** — a compiled circuit, exact by `Forward(Compile(p)) == Interpret(p)`;
- **E** — a reader from M's residual stream into C's inputs (a linear probe: a predicate on the state);
- **D** — a writer from C's outputs back to M (a logits bias, or an addition to the residual).

C adds no error, so every error is in E or D: **error is attributable**. Three regimes:

1. **Mask** — C's output restricts the next token (the decode port): a guarantee, not knowledge. Done:
   `Decode.sc`, `WeightsPort` (exact quotes beside Qwen3-4B), the `Names` mask for code.
2. **Read** — E reads a predicate or a relation from M's states; C counts, joins, deduces; D returns the result.
   No gradient flows through C; length generalisation comes from C, not from M.
3. **Write** — M writes typed records into positions C reads (later).

### bridge-1: a count with a neural predicate

Qwen3.5-4B reads a list of items; a probe on its states at one layer decides, per item, whether it satisfies the
question's predicate; a compiled `Count` head (cap 100) counts. With the layer chosen by validation (layer 12:
99.5%), the probe is right on 99.2–100% of items, and the whole count is exact on 5–6 of 6 lists at every length —
at n = 40 and n = 80, where the model alone and the model thinking get 0 of 6. Two misses in 930 test items.

### bridge-4: deduction over sentences

Sentences "X manages Y", "Y reports to X" in 10 + 10 phrasings; a reader decides each sentence's direction; the
`Closure` program computes reachability, the sentences an answer rests on, and contradiction cycles. Readers: a
probe on the states, ASK (the model's own next-token log-probabilities for both orders), ASK-WHO — and BOTH, the
probe's and ask's log-probabilities summed: **100.0% on 1,230 test sentences**, the closure exact 6/6 at every
size (15–100 sentences) against 3–5/6 for the model alone; cycles 8/8; every wrong "yes" explained by its sentences.
The lesson used everywhere since: two readers that fail on different phrasings, summed.

### bridge-2: training under the mask — inconclusive

LoRA under the `Names` mask against plain LoRA on Qwen3.5-4B: the mask did not hold on Qwen3.5's tokenizer. Open: a
unit law for `Names` there, then a rerun.

### The honest ledger (bridge.md §10)

"In weights" pays only for (a) the mask, (b) training through the interface, (c) one artifact with no LLM. Readers
must reach about 99%. The novelty is narrower than it looks (Tracr, Scallop, DeepProbLog are close).

## 2. The core: a small model that only reads (`specs/core.md`)

If exact modules do the knowing and the computing, the model's job shrinks to **text → predicates on the bus**:
which table, which column, which value, what form of answer. So the model can be cut, distilled and split by
language. The work was done in okay-desk (its bench tools: `Cut`, `Prune`, `Distill`, `Merge`, `Silence`,
`AblationMap`, `StudentByMap`, `SplitWeights`):

- **cut19** — the first 19 of Qwen3-0.6B's 28 layers: 244 MB, the reference (bus v1 = its state after layer 19);
- **the vocabulary cut** — 152k → 38k tokens, the embedding in blocks by language, loaded when a token appears;
- **the student** — 12 of cut19's layers distilled toward layer 19's state (LoRA rank 64, z-MSE 0.42 → 0.042 in 24
  minutes), merged at 4 bits: 122 MB, the teacher's skill file unchanged;
- **the map by component** — each attention block and MLP of cut19 silenced alone, the readers retrained, scored in
  two languages: FREE (14 MB, nothing lost), NEAR (45 MB), Russian's (34 MB), CORE (73 MB);
- **the core** — FREE and Russian's components removed (−48 MB), the contract distilled back with no adapter on the
  removed parts, merged at 4 bits: 146 MB on the page.

The lesson of the map: removals compound, and a component's language cannot be read from one ablation — the bus
trained back makes the rest take over.

## 3. The bus (`specs/bus.md`)

The interface between a model and its modules: a model gives states on a **bus**; modules read the bus (and, in v2,
write to it). Any model that gives the bus serves the same module files.

**The goal**, and what of it is possible:

- **any model learns the bus from a description** — if the description is machine-readable (the kit: prompts,
  reading points, reference answers, a pair generator); a fixed recipe then learns the adapter with no human, once
  per model;
- **any model whose states can be read** — open weights; a model behind an API gets the **text tier** (the program
  as text, the result as tokens);
- **exact computation, measured reading** — numbers come only from modules; the reading is shown (a program),
  validated before it runs, and below a threshold the system abstains;
- **efficiency** — a reader is a probe on states the model computes anyway.

**The levels.** L0 the text's side (the tokenizer; positions addressed by characters, never token ids). L0.5 a
language's module (its vocabulary block + a trained delta). L1 the model. L2 the adapter (identity, a linear map,
relative coordinates, or distillation; it also maps reading positions). L3 the bus. L4 the modules (readers,
compiled blocks, engines, memory). L5 the typed protocol (a query, a fact, a decision; the effects around).

**Contract v1.** Width 1024; the state after layer 19 of cut19 of Qwen3-0.6B-4bit; per-dimension mean and spread,
clipped at ±4, scaled to a norm near 8; Qwen3's chat template with thinking off; the reading points (the prompt's
last token, the schema's names, the last token after a forced prefix of the program); the API is okay-desk's `Read`
effect — `States(ids, at)`, `Logits(ids)`, `Encode`, `Decode`, `Prompt`, `Note`.

**The recipe** (how a model learns the bus): render the kit's prompts in the model's template and map the reading
points by characters; pick the layer with the least held-out error of a linear map; try the cheapest adapter first
(identity → ridge → relative coordinates → distillation); run the kit.

**The first test: can the compatibility layer be a matrix?** (okay-desk `BusAdapter`, specs/data.md §31.) The readers
ran on the reference over 400 questions, every state they asked for recorded (9,916 reading points); Qwen3-1.7B's
states at the same positions; ridge regression to bus v1; the reference skill file **unchanged** read through it:

| Qwen3-1.7B, the skill unchanged | R² | sample en / ru | held-out whole |
|---|---|---|---|
| through the ridge map (layer 19) | 0.906 | 13/13, 13/13 | 95.3% |
| control: states replaced by a constant | — | 13/13, 13/13 | 91.3% |
| the reference itself | — | 13/13, 13/13 | 97.7% |

A linear map carries the bus within one family. But the control shows the measure is too easy: the exact rules
decide most questions without any state. The kit needs questions the rules cannot decide before it can tell a good
adapter from none — that is the next step, then Qwen3.5-0.8B (another tokenizer).

**v2 (outlined).** Modules the model writes to and reads back: `Write(position, vector)` and `Mask(logits)` beside
`Read`; a model taught a *kind of signal* (a row, a number, yes/no), never a module, so any module of that kind plugs
in. A compiled block computes in its own residual (73 dims for the count above), so its output reaches the model's
bus through a learned projection per kind of signal.
