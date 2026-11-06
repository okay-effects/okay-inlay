# The bus: the interface between the core and its modules (v1, 2026-11-06)

BACKLOG 14 and specs/core.md §3, §4.3, §8–§10 made one contract. A core gives states on a BUS; skills read the bus;
languages and components sit between the text and the core. Any core that gives the bus serves the same skill
files; a skill written once serves any such core. The contract is versioned and checked by a kit of tests.

## The levels

- **L0 — the text's side**: a tokenizer, and the vocabulary in blocks by language (okay-desk specs/data.md §20):
  a block loaded when a token of it appears. Positions are addressed by characters of the text, never by token ids
  (`Prompted`: character offsets to token positions by decoding), so a skill does not depend on the tokenizer.
- **L0.5 — a language's components** (specs/core.md, the map): a module of a language is its block of the
  vocabulary plus the components the map found that language's — each a contribution to the residual at a layer
  (attention or MLP), ZERO when the module is not loaded — plus, later, an adapter of the first layers.
- **L1 — the core**: the components every language needs; the layers that give the bus.
- **L2 — the bus adapter** of a core that is not the reference: identity (the reference core, its students),
  a linear map (to be tested: ridge from another model's states to bus v1), or distillation (okay-desk §21: the
  proven one). A core and its adapter are "bus-v1 compatible" when the kit passes at the threshold.
- **L3 — the bus v1**, the contract: width 1024; the layer (the reference: after layer 19 of cut19 of
  Qwen3-0.6B-4bit); the normalisation — a mean and a spread per dimension, clipped at ±4, scaled to a norm near 8
  (as the skill file holds them); the prompt template (Qwen3's chat template, thinking off, checked token for token:
  okay-desk TemplateCheck); where a skill reads: the last token of a prompt, the positions of the schema's names,
  the last token after a forced prefix of the program. The API: okay-desk's effect `Read` — States(ids, at),
  Logits(ids), Encode, Decode, Prompt — answered by mlx-scala on the JVM and by the page's device in a browser.
- **L4 — the skills**: readers (linear and bilinear probes: files of float32), compiled blocks (okay-inlay), each
  addressing L3 only; a skill file carries the bus version it was trained for.
- **L5 — the typed protocol**: what a skill yields — a Query (relational or Datalog), a fact, a decision — and the
  effects the programs around the core perform (`Model`, `Disk`, okay-http's `Fetch`, okay-ui's `Ui`).

## The kit (TCK)

- The sample (okay-desk `Sample`): 12 questions in English and Russian, the gold programs, the exact comparison of
  results (`same`); the reference skill file (`readers-Qwen3-0.6B-4bit-cut19-19.skill`) unchanged.
- The measure on 200 held-out generated pairs per slot (`Readers.measure`).
- Cross-lingual: the skill trained on one language read on the other (LayerProbes with DESK_TRAIN_LANG, DESK_TEST_LANG).
- The threshold for "compatible": the reference's own numbers (12/12, 9/12) less one question a language; the measure
  within 5 points per slot.
- The reference's numbers at v1: cut19 244 MB 12/12, 9/12; the student 122 MB 11/12, 7/12 (§21) — compatible by
  distillation, one question short of the threshold on Russian.

## Not in v1

- Writing to the bus (a vector added at a position, a logits mask): the bridge's write regime; a core must be
  trained to listen. v2.
- A bus of another width: an adapter's business (L2).

## First test after the map

The linear adapter (BACKLOG 14): ridge regression from Qwen3-1.7B's states (and Qwen3.5-0.8B's, another family)
to bus v1 on the desk's prompts; the reference skill file unchanged on the sample. The expectation stated before
the test: the coarse predicates (the table, the shape) carry, the fine ones (a column's pointer, a value's start)
mostly do not; the test says where the cheap adapter ends and distillation begins.

## v2, outlined (the operator, 2026-11-06): modules the core USES through the bus

Modules that read the bus exist (readers, engines, memory, compiled blocks: handlers of effects the core knows
nothing of). v2 is modules the core WRITES TO and reads back — the bridge's write regime — and the core must be
taught to use the bus for that. The one rule that makes it a framework: the core is taught a KIND OF SIGNAL, never a
module — a fact of a table (a row, a number, yes/no, an entity) encoded one fixed way at one kind of position; taught
once, any module of that kind plugs in with no training, as any skill plugs into the read side today.

- The ops: `Write(position, vector)`, `Mask(logits)` added to `Read`; the kinds of signal with their encodings; the
  version in a module's file.
- A module: a handler of an effect, and for a writing one, an encoder of its result into a kind of signal (a compiled
  inlay block's encoder is its weights).
- The training per kind: pairs "the teacher saw the fact in the text, the student gets it through the bus alone",
  the loss on the teacher's states after that point (okay-desk `Distill` with the write in the data); the exact
  judge; the kit with a threshold.
- The first experiment: a question needing a fact not in the prompt, the fact given only as a vector on the bus
  (compiled by okay-inlay from the table's row); after training, the readers read the same slot as with the fact in
  the text. Holds on the sample → the framework is real; fails → the text protocol stays (a module's result appended
  as tokens: always works, costs prompt length).
