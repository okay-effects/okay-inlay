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
