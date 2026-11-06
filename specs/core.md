# A minimal core that understands, and the mechanisms around it (2026-11-06)

The operator's idea: take a model apart into components, optimize each on its own, and build a model back — the
knowledge separated from the other abilities (understanding, reasoning, intuition). The core that remains should be
the smallest thing that UNDERSTANDS information; everything else — knowing, computing, remembering, saying — is a
mechanism beside it. This is the bridge (specs/bridge.md) taken to its end: the model as a reader of questions, the
truth elsewhere.

## 1. What is known about where things are

- Facts live mostly in the MLP layers, as key–value memories: a context's key selects a value (a fact edited by
  changing a few MLP weights — ROME, MEMIT — stays edited, the rest untouched).
- Attention routes and copies: which position looks at which, induction (go on as before), moving information
  between positions. That is nearer to understanding the structure of what was said.
- The residual stream is the bus the layers share; its format at a layer is the interface between components.
  The readers (okay-desk) read from this bus.
- Features are in superposition: a neuron holds several meanings, an ability is spread over many layers. So no
  ability can be lifted out as a block of weights; the boundary between components is not a layer boundary.

## 2. What was already done, under other names

- The knowledge taken out entirely: in okay-desk the facts are tables; the model only understands the question and
  routes (the readers); the exact engine computes. The limit case of "separate the knowledge".
- The cut: 19 of 28 layers keep everything the readers need (specs/data.md §14) — the measure by component, not a
  guess; the last 9 layers prepare the next token, which the readers never ask for.
- The readers: components for tasks, trained over the frozen core, replaced without touching it; the head and the
  embedding already live apart (the head on the CPU on a phone).

## 3. "Understands", measured

Two scales, both exact:

1. **Readability**: the decisions a task needs read linearly from the core's states where it makes them — the
   readers' bench (12/12 en, 9/12 ru on the sample; the measure on 200 held-out pairs per slot).
2. **Ignorance**: the core does not know facts by itself — asked a fact with no table, it has nothing; a fact
   changed in the table changes the answer. (The cut model still has some facts in its early MLPs: to be measured.)

## 4. The plan

1. **The map by component** (this week): for every layer of the cut model, two variants — the attention's output
   (o_proj) zeroed, the MLP's output (down_proj) zeroed — each a model in the cache (okay-desk `Ablate`); the
   readers trained again on each (the states move) and the bench run (`scripts/ablate.sh` → bench/ablation.txt).
   The result: which MLPs and which attentions the understanding needs, and which only carried facts or the next
   token. The first honest number for "how much of the core is understanding".
2. **The memory of facts out**: the MLPs the readability does not need are shrunk or dropped; what remains of the
   facts is measured (ignorance) and, where it must go, unlearned.
3. **The bus as a contract**: the residual at the layer L — its format, a tolerance — is the interface; a component
   distilled, quantized or rewritten is right when the bus is the same (the laws we already run on three devices).
4. **The assembly**: the core (the layers that understand, shrunk) + the mechanisms as handlers of effects — the
   readers (decisions from states), the exact engines (tables, Datalog, okay-inlay's weights), the memory (a prefix
   cache, a store of facts and documents), a generating head where words are needed. The core knows none of them;
   the program over the effects composes them (okay-desk is this already, in small).
5. **Learning without breaking**: a new ability is a new reader, or a training with the core frozen and the bus's
   law kept; the core itself changes only with the whole map measured again.

## 5. What is not promised

A clean "intuition" or "reasoning" as separate weights: these are distributed; they can be measured and kept while
shrinking, not taken out as a part. The core is minimal by measurement, not by anatomy.

## 6. The logical level (the operator's question, 2026-11-06)

Three layers, with three different answers:

- **The mechanics** — what the forward computes — is a formal object already: `Qwen3Compute` is a program over ten
  operations with laws on three devices; a Lean statement of it (okay-lean) would complete this layer.
- **The semantics of the interface** — which predicates read from the bus, where: a reader is a linear functional
  on the residual, that is a predicate ("the table is orders", "the shape is count with a condition"); the map by
  component (§4.1) adds the structure (which components each predicate needs). This is the deconstruction to a
  logical level: a theory of what the core GIVES, with laws. okay-inlay is the other direction — logic into weights
  with `Forward(Compile(p)) == Interpret(p)` — so what is expressed as predicates and programs is rebuilt by
  compilation, not by training.
- **The inside** — why layer 7's weights do what they do — stays partial (superposition; single circuits such as
  induction heads). The core is GROWN (trained, distilled) under the laws; the mechanisms are BUILT from logic. The
  theory constrains and tests the core; it does not generate it.

## 7. Modules that learn

- **Knowledge from a book as an MLP block**: an MLP is a key–value memory, and okay-inlay's Lookup heads compile a
  table into weights with a law — so a block CAN be made; but a block trained on text is a lossy memory no one can
  check. The better form we have: facts as data (tables, Datalog, a store) under exact engines, compiled into
  weights only where that pays (the closure was). "The model reads a book and builds a block" then means: readers
  extract the facts → the store → a compiled block when needed. The hard part is the extraction from free text —
  a skill, not a memory.
- **A skill as a new reader**: done already — a reader trained over the frozen core, judged by the exact judge;
  self-play (okay-desk §13) showed the judge works and the 0.6B cannot yet write its own questions. Two thirds of
  the loop exist: the core proposes, the exact judge checks, the accepted becomes a reader or a compiled block;
  missing: the making of training pairs by the core itself at this size (a larger asker, or a person).
- **Next after the map**: "a book → facts → a block" on the sample — the readers extract facts from a text that
  describes the data, the facts compiled into okay-inlay weights, a question answered with no table: a checkable
  demonstration of learning knowledge.

## 8. The process: a small core distilled from a large model (2026-11-06)

The aim: from a large, able model, a core that is small, understands, and learns; the knowledge and the skills
added beside it as blocks. Every step measured; a step that lowers the measures is undone.

**The core's job, exactly**: text → predicates on the bus (what readers read), nothing else: no generation, no
facts. Three measures: READABILITY (readers over a family of tasks: programs' slots, facts from text, closure,
diagnoses), LEARNABILITY (a new reader on an unseen family of tasks, trained from few examples — "can learn",
measured: trained on tasks A..K, read on L from 50 examples), IGNORANCE (no table, no fact; a fact changed in the
table changes the answer).

**What to distil: the bus, not the tokens.** Logit distillation copies the generating head. The teacher (4B and
up) gives its states at the points of decision; the student learns so that THE TEACHER'S PREDICATES READ FROM THE
STUDENT'S BUS — a learned projection from the student's state to the teacher's, on the coordinates the readers
use alone (the rest carry facts and generation: not copied). The loss: the bus's error on those coordinates plus
the readers' decisions' error. Readability-preserving distillation.

**The data**: generated pairs over many domains and both languages (okay-desk's Pairs, wider), labelled by the
teacher, filtered by the exact judge; the student never sees a pair the judge refused.

**The order of shrinking**, each step under the laws: (1) the cut by depth (done, 28 → 19); (2) the map by
component (§4.1, running) → the components the readability does not need dropped; (3) the vocabulary: the
embedding is 78 of 244 MB — the tokens of the languages used kept (~30k of 152k), the rest gone; (4) heads and
MLP channels pruned by contribution; (5) the distillation into a narrower student (§ above), initialised from the
pruned teacher, never from random (one Mac has neither the data nor the time for that); (6) quantization: 4 bits,
the MLPs tried at 2; (7) after every step the three measures.

**Learnability as the objective**: families of tasks held out during the distillation, and the loss given a term
for how fast a new reader trains on them — the core optimised to be a GOOD BUS FOR FUTURE READERS, which is what
sets this apart from compression.

**The blocks around it**, handlers of effects the core knows nothing of: readers, exact engines (tables, Datalog,
okay-inlay's weights), memory (the prefix cache, a store of facts and documents), compiled blocks of knowledge,
and a small generating head trained over the bus where words are needed.

**The journal**: at every checkpoint — readability by slot, the map by component, the bus's geometry (the
dimensions a predicate takes, the interference between predicates): the process as a sequence of checkable
events, not one loss curve.

**Where it may land** (an estimate, not a promise): the vocabulary −60 MB; five to eight more layers out and the
MLPs narrower: 60–80 MB; 2-bit MLPs: ~40 MB. The measures at each step say where the floor is.

**First after the map**: the vocabulary (cheap, measurable, large), then the first distillation with the bus loss on
mlx-scala (AdamW is there; the student is the same Qwen3 code with smaller sizes, its weights from the teacher).

## 9. The plan, refined (the operator, 2026-11-06): a core of no language, a teacher of 35B

The aim restated: a small core of MEANING tied to no language; every language a module of its own; every skill and
every piece of knowledge a module of its own. Where language sits in a transformer: the embedding and the first
layers are a language's lexicon, the middle layers are where multilingual models' representations meet across
languages, the last prepare the next token. So the core of no language is the middle; a language module is a
block of the vocabulary (§20 of okay-desk's data.md, done) plus an adapter of the first K layers on the way in, and
a head on the way out where words are needed.

1. **The measure of "no language"** (now, on the cut 0.6B): readers trained on English pairs alone, read on
   Russian, layer by layer. Where the cross-lingual readability is high the bus is already meaning; below it,
   language. This says where the core begins and how many layers the language module takes.
2. **The teacher, 35B** (Qwen3.6-35B-A3B, run by mlx-scala; `Qwen35.hook` gives its layers): its states on the
   same pairs, readers trained on them by layer — the ceiling of readability and its best layer: the predicates
   defined by the teacher. The data: generated pairs in several languages (English, Russian, one or two unrelated
   ones to test the independence), labelled by the teacher, filtered by the exact judge.
3. **Readability-preserving distillation of the bus** (§8): the student the cut 0.6B first, smaller after; a
   projection from the student's state to the teacher's subspace of predicates learned with the student; the loss:
   the error on those coordinates, the teacher's readers' decisions, and a cross-lingual term (one question in
   two languages → near states on the core's bus). A full fine-tune of 0.6B fits an M4 Max (weights and Adam ~8 GB).
4. **Language modules**: the vocabulary's blocks (done) and an adapter of the first K layers per language (LoRA,
   the core frozen; K from step 1). A new language = a block + an adapter trained on that language's pairs against
   the same bus; the core untouched; checked by readers trained on the other languages.
5. **Skills and knowledge as modules**: readers, exact engines, stores, compiled inlay blocks — handlers of
   effects (as now).
6. **The core shrunk under the laws** (§8): the map by component (running), pruning, 4 and 2 bits; learnability
   (a new reader from 50 examples on a held-out family) the deciding measure.
7. **The journal** at every checkpoint: readability by slot and by language, the map, the bus's geometry.

What the first two steps decide: step 1, where the border of language and core is in our model; step 2, whether
the 35B has the meaning at a readable layer and which. The distillation does not begin before both are measured.

## 10. The plan, fixed (the operator, 2026-11-06): path 1 — a very small core, extended by modules

Decided: the core is DISTILLED from the cut model (path 1 of the two), and made extensible — a language a module,
an ability a module. Everything else is in service of this.

**What exists already** (okay-desk, branch `student` merged, its §21): the CONTRACT is the state after layer 19 of
cut19 — the state the readers' skill file reads, never retrained; a student is any model that gives that state. A
student of 12 of the 19 layers, LoRA (rank 64) distilled toward the contract over the desk's prompts (z-MSE 0.42 →
0.042, 24 minutes), merged at 4 bits: 122 MB, 11/12 en and 7/12 ru with the teacher's own skill file (the teacher
244 MB, 12/12 and 9/12). The tools: LayerInfluence, Prune, Distill (checkpoints every 500 steps), Merge. Its
vocabulary cut (152k → 38k tokens by a corpus the 35B wrote) is the other way to the same saving as the blocks (§20
of data.md): the blocks keep the ids and load languages on demand — the modular way — so the blocks stay, and the
corpus goes into the English block's definition.

**The steps, in order, each measured with the SAME skill file:**

1. **The map by component** (running: attention and MLP of each layer silenced, the readers retrained): what the
   contract needs. Early lines: layer 1's attention is free (12/12, 9/12 without it), its MLP is not (7/12, 4/12);
   layer 0's attention matters most. The map says what to drop — by component, finer than a layer.
2. **The student by the map**: the components the map calls free dropped (not whole layers only), the rest
   distilled toward the contract with `Distill`, merged. The measure: the teacher's skill file on it; the target
   first 12/12 and 9/12 at ~100 MB, then the floor by the same law.
3. **Languages as modules**: the vocabulary's blocks (done) and, where the map shows the first layers are a
   language's, an adapter per language (LoRA on those layers, trained on that language's pairs toward the
   contract), the core frozen. A new language = its block + its adapter.
4. **Abilities as modules**: a new skill is a new readers' file over the same contract (done for the desk's
   questions); the facts and documents as data under exact engines; a compiled block where it pays.
5. **Learnability as the measure**: a new readers' file trained from 50 examples of a family of tasks never seen;
   the student that keeps this is the core.
6. **Then the floor**: 2-bit MLPs, pruned heads, a narrower student — each under the same law.

Memory on this Mac (36 GB): the 35B teacher's states, where used, are read once to disk; the student trains alone.

**A language's components (the operator, 2026-11-06).** The map is read by language: a component the readers need
in every language is the CORE; a component needed in one language and free in the others belongs to THAT
LANGUAGE'S MODULE, beside its block of the vocabulary — its weights in the module's file, run when the module is
loaded, zero when not (as the map silenced it). The module is loaded by the same sign as the block: a token of the
language appears. Early map: layer 1's attention free; layer 2's attention and layer 3's MLP Russian's (12/12
English without them, 6/12 Russian); layer 0 and the MLPs of layers 1–2 the core's. The components are silenced one
at a time; removed together they may compound — the student is assembled by the map and measured whole with the
same skill file before any component is called a module's.

## 11. The map by component — the result (2026-11-06)

cut19, each component silenced alone, the readers retrained on the variant (600 pairs), the sample (the whole model
12/12 en, 9/12 ru); a cell is en/ru:

| layer | attention | MLP | | layer | attention | MLP |
|---|---|---|---|---|---|---|
| 0 | 6/3 core | 10/6 core | | 10 | 12/6 ru | 11/5 core |
| 1 | 12/9 free | 7/4 core | | 11 | 7/5 core | 12/7 ru |
| 2 | 12/6 ru | 7/4 core | | 12 | 12/6 ru | 12/8 near |
| 3 | 12/8 near | 12/6 ru | | 13 | 11/7 core | 10/6 core |
| 4 | 12/7 ru | 12/5 ru | | 14 | 11/6 core | 11/7 core |
| 5 | 12/8 near | 11/7 core | | 15 | 7/6 core | 12/7 ru |
| 6 | 10/6 core | 12/8 near | | 16 | 12/9 free | 12/9 free |
| 7 | 12/8 near | 12/8 near | | 17 | 10/6 core | 12/8 near |
| 8 | 12/8 near | 12/8 near | | 18 | 12/6 ru | 12/8 near |
| 9 | 12/9 free | 12/8 near | | | | |

Read by language: FREE (nothing lost): attention 1, 9, 16, MLP 16 — 14 MB. NEAR FREE (English whole, Russian one
question): attention 3, 5, 7, 8; MLP 6, 7, 8, 9, 12, 17, 18 — 45 MB. RUSSIAN'S (English whole, Russian two or
more): attention 2, 4, 10, 12, 18; MLP 3, 4, 11, 15 — 34 MB, a Russian module's. THE CORE (both lose): attention 0,
6, 11, 13, 14, 15, 17; MLP 0, 1, 2, 5, 10, 13, 14 — 73 MB. So the shape, if the map adds up: a core of ~73 MB of
layers, an English page ~107 MB with its vocabulary, Russian a module of ~36 MB (its components and its block).
The components were silenced one at a time; the next step assembles the sets and measures them whole, then
distils what the removal cost back toward the contract.

**The sets whole, and distilled back (2026-11-06 evening).** The map's sets silenced together, measured whole on the
sample (the whole model 12/12 en, 9/12 ru): the teacher's skill unchanged / the readers retrained — FREE (4
components, −14 MB): 11/2 / 10/6; FREE+NEAR (15, −59 MB): 6/1 / 6/3; FREE+RU (13, −48 MB): 9/5 / 6/3; all three (24,
−94 MB): 6/1 / 6/3. Removals compound: what is free alone is not free together, and Russian goes first. Then the
contract DISTILLED BACK (Distill: LoRA rank 32 on every projection toward the teacher's state at layer 19, 2,000
steps, 13 minutes, z-MSE 0.026): FREE removed and distilled, the teacher's skill unchanged — **12/12 en, 7/12 ru**.
So the rule of §4.3 holds in practice: a component is removed with the bus trained back, never alone; the cost of
the free set after distillation is two Russian questions. Next: FREE+RU (the module hypothesis: English whole with
Russian's components gone?) and FREE+NEAR (−59 MB), each distilled the same way.
FREE+RU removed and distilled: **12/12 en, 7/12 ru** — the same as FREE alone: the nine components the map called
Russian's are not needed by Russian once the bus is trained back; the others take their work. FREE+NEAR (−59 MB):
**11/12 en, 7/12 ru**. Two conclusions: the core compresses further than the map promised (−48 MB at no cost beyond
FREE), and a component's language cannot be read from a single ablation without retraining — it is measured on the
distilled model by the cross-lingual test. Running: all three sets (−94 MB) distilled.
ALL THREE removed (24 components, −94 MB, a core of 73 MB of layers) and distilled (2,000 steps, rank 32): **11/12 en,
5/12 ru**. The floor by this route at this budget of training: English nearly whole, Russian two thirds. Running:
the same with 5,000 steps at rank 64 (the student branch's budget), to see what training alone buys back.

**The core chosen and merged (2026-11-06, late).** The honest distillations — no adapter on the removed projections
(mlx-scala `Lora.except`), so the merge keeps nothing of them: FREE+RU (−48 MB) **12/12 en, 7/12 ru** (z-MSE 0.049;
the same as with adapters everywhere — the components are truly not needed); ALL THREE (−94 MB) 11/12, 4/12
(z-MSE 0.087; with adapters everywhere and 5,000 steps at rank 64 it reached 12/12, 6/12 — the adapters were doing
the removed components' work). THE CORE: FREE+RU removed, distilled, merged at 4 bits — **12/12 en, 6/12 ru** with
the teacher's skill unchanged (the merge at 4 bits costs one Russian question, as §21 found). Its files leave the
removed components out (SplitWeights; okay-llm's forward skips what the file has not — SkipSuite: absent ==
silenced). Deployed as the page's default, `model-core`, beside the 12-layer student (`model-small`) and the cut.
