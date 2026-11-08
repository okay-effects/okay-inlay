# The bus: the interface between a model and its modules (v1, rewritten 2026-11-06)

BACKLOG 14; specs/core.md §3, §4.3, §8–§11. A model gives states on a BUS; modules (readers, engines, compiled
blocks, memory) read the bus and, from v2, write to it. Any model that gives the bus serves the same module files;
a module written once serves any such model. The contract is versioned and checked by a kit of tests.

## 1. The goal, and what of it is possible

The operator's goal (2026-11-06): ANY model can work with the bus, LEARNS it from a simple description, and the bus
gives every property we require — exactness and efficiency. Taken part by part:

- **"Learns from a description" — yes, if the description is the kit, not prose.** A model cannot learn to give or
  take vectors from a text in its prompt: in-context learning works on tokens, and the bus is below the tokens. So
  the description is machine-readable: this spec's numbers, the kit (prompts, positions, the reference modules'
  answers) and a generator of training pairs. From it a FIXED RECIPE (§4) learns a model's adapter with no human
  in the loop: minutes for a linear map, about a quarter of an hour for a distillation (okay-desk §21; §11 here:
  2,000 steps, 13 minutes). Learning is per model, once; the modules are never retrained.
- **"Any model" — any model whose states can be read.** Open weights, any architecture with a hidden state per
  position (transformers; SSMs in principle). A model behind an API gives no states: it gets the TEXT TIER (§3),
  which works from a prose description alone but carries the costs of a tool call.
- **Exactness — exact computation, measured reading.** The numbers come only from modules (compiled blocks are
  exact by law, engines by construction); the bus never computes. What can be wrong is the READING: which table,
  which column, which value the question meant. That part is a model's judgement and stays statistical. The bus
  makes it checkable instead of hidden: what was read is data (a program, shown with every answer), it is validated
  against the schema before it runs, and a reading below a confidence threshold ABSTAINS (says it does not know)
  instead of answering. The promise is therefore: an answer is exact for the question as read, the reading is shown,
  and the rate of wrong readings is the kit's measured number — never "always right".
- **Efficiency — yes.** Reading is a probe on states the model computes anyway (a matrix-vector per slot,
  milliseconds); a linear adapter is one more matrix; a module's result enters the state (v2) or a few tokens (text
  tier); no generated call, no round trip, no copying of results through text. The cost is the one-time adapter.
- **What is not promised.** That every model reaches the threshold by a linear adapter (expected: coarse readings
  carry, fine ones do not; §6), that a model learns to LISTEN to writes without training (v2 trains a kind of signal
  once per model), and that a bus defined by one reference model is the best definition (§6 tests an alternative).

## 2. The levels

- **L0 — the text's side**: a tokenizer, the vocabulary in blocks by language (okay-desk specs/data.md §20), a block
  loaded when a token of it appears. Positions are addressed by CHARACTERS of the text, never by token ids
  (`Prompted`: character offsets to token positions by decoding), so a module does not depend on the tokenizer.
- **L0.5 — a language's module**: its block of the vocabulary plus a TRAINED delta (a LoRA, or an adapter of the
  first layers) that brings the language to the bus. Not carved components: §11 of specs/core.md showed that the
  nine components a single ablation called Russian's are not needed by Russian once the bus is trained back (FREE+RU
  and FREE alone both 12/12 en, 7/12 ru), so a component's language cannot be read from ablation. A language module
  is measured by the kit's cross-lingual test on the distilled model.
- **L1 — the model (the core)**: the layers that give the bus. Any size; the reference and its students are cut or
  distilled from one model; another family is brought by L2.
- **L2 — the adapter**: what maps a model's states to the bus — identity (the reference and its students), a linear
  map, relative coordinates (§6), or distillation (proven, okay-desk §21). It also maps POSITIONS: the reference
  template's reading points (§3) to the same points under the model's own template.
- **L3 — the bus**, the contract (§3).
- **L4 — the modules**: readers (linear and bilinear probes, files of float32), compiled blocks (okay-inlay),
  engines, memory; each addresses L3 only. A module file carries the bus version, and the adapter and kit result it
  was checked with.
- **L5 — the typed protocol**: what a module yields — a Query (relational or Datalog), a fact, a decision — and
  the effects the programs around the model perform (`Model`, `Disk`, okay-http's `Fetch`, okay-ui's `Ui`).

## 3. The bus v1 — the contract

- **Two tiers, one set of modules.** VECTOR tier: modules read (v1) and write (v2) the states. TEXT tier: a module's
  input is a program the model writes and its result is appended as tokens in a fixed format — any model, closed
  ones too, from a prose description; it pays what a tool call pays (the model must decide to call, writes the
  arguments as text, reads the result back). A module serves both tiers unchanged; the tier is the adapter's.
- **Width and place**: 1024; the reference is the state after layer 19 of cut19 of Qwen3-0.6B-4bit.
- **Normalisation**: a mean and a spread per dimension, clipped at ±4, scaled to a norm near 8 (as the skill file
  holds them).
- **The prompt**: Qwen3's chat template, thinking off, checked token for token (okay-desk TemplateCheck). Another
  model keeps its own template; its adapter maps the reading points.
- **The reading points**: the last token of the prompt, the positions of the schema's names, the last token after a
  forced prefix of the program. Read once per prompt (the reading reuses the prompt's states; with a KV cache, the
  states of the cached prefix are reused, never recomputed).
- **Numeric tolerance**: the reference is 4-bit weights with float32 readers; devices differ (MLX, WebGPU, WebGL2,
  CPU). A device is conformant when its kit answers equal the reference's; per-state, z-MSE to the reference's
  states is recorded, not thresholded (okay-llm's exact runs give 12/12, 9/12 on WebGPU).
- **The API**: okay-desk's effect `Read` — States(ids, at), Logits(ids), Encode, Decode, Prompt, Note — answered by
  mlx-scala on the JVM and by the page's device in a browser.

## 4. The recipe: how a model learns the bus

Fixed, run by a script, no human choices:

1. **Template**: render the kit's prompts in the model's template; map the reading points by characters (L0).
2. **Layer**: collect the model's states at every layer on the kit's training prompts; pick the layer whose linear
   map to the bus has the least held-out error.
3. **Adapter, cheapest first**: identity (same model) → ridge regression to the bus → relative coordinates (§6) →
   distillation (LoRA toward the bus, okay-desk `Distill`). Stop at the first that passes the kit.
4. **The kit** (§5); the result recorded with the adapter. Below the threshold after distillation: the model gets
   the text tier.

## 5. The kit (TCK)

- **The measure (the threshold)**: the readers' accuracy per slot on 200 held-out generated pairs
  (`Readers.measure`); compatible = within 5 points of the reference on every slot.
- **The sample (the smoke test)**: okay-desk `Sample`, 12 questions in English and 12 in Russian, gold programs, the
  exact comparison of results (`same`), the reference skill file (`readers-Qwen3-0.6B-4bit-cut19-19.skill`)
  unchanged. At 12 questions one question is 8 points, so the sample reports, it does not decide.
- **Cross-lingual**: the skill trained on one language, read on the other (LayerProbes with DESK_TRAIN_LANG,
  DESK_TEST_LANG).
- **Abstention**: the share of wrong readings above the confidence threshold — the number the exactness promise
  rests on (§1); recorded per slot.
- **A second family of tasks** before v1 is called a standard: one task family (tables and queries) would fit the
  bus to itself. Candidates: symbols of code (specs/symbols.md), the inputs of compiled blocks (a fact's position,
  a value to count).
- **The reference's numbers at v1**: cut19 244 MB 12/12, 9/12; the student 122 MB 11/12, 7/12 (okay-desk §21) —
  compatible by distillation, one question short on Russian.

## 6. First tests

- **The linear adapter** (BACKLOG 14): ridge from Qwen3-1.7B's states, then Qwen3.5-0.8B's (another family), to
  bus v1 on the desk's prompts; the reference skill file unchanged. Stated before the test: the coarse readings (the
  table, the shape) carry, the fine ones (a column's pointer, a value's start) mostly do not; the test says where
  the cheap adapter ends and distillation begins.
- **RESULT (2026-11-07, okay-desk specs/data.md §31)**: Qwen3-1.7B layer 19 → bus v1 by ridge, R² 0.906 on held-out
  reading points; the reference skill UNCHANGED: the sample 13/13 en, 13/13 ru, the held-out pairs 95.3% whole
  (cut19 itself 97.7%). A linear map carries the bus within one family. But the CONTROL — the states replaced by a
  constant — reads 91.3% and the whole sample: the exact rules decide most of the desk's measure now, and the kit
  (§5) must add questions they cannot decide (nothing named, a value only translated, a judgement) before it can
  tell a good adapter from none. The prediction "fine readings do not carry" was not tested by this set; next, that
  set, then Qwen3.5-0.8B (another tokenizer).
- **ON THE HARD SET (2026-11-08, okay-desk specs/data.md §32)**: the 388 held-out teacher pairs (people's wording in
  en, ru, uk). cut19 itself 41.8% whole; the constant-state control 29.4% (119 abstained); Qwen3-1.7B through the
  ridge map 43.0%. On people's wording the bus is worth ~12 points, and one matrix carries it within the family — the
  map loses on the table and the condition, gains on the group and the aggregate. The kit's threshold belongs on this
  set. Next: Qwen3.5-0.8B, another tokenizer.
- **Relative coordinates**, in parallel: a state described by its similarities to the states of fixed anchor texts,
  computed the same way in every model — no training, and possibly a definition of the bus that names no model. If
  it carries at least what ridge carries, v2 considers defining the bus by anchors instead of by the reference.

## 7. v2, outlined (the operator, 2026-11-06): modules the model USES through the bus

Modules that read the bus exist (readers, engines, memory, compiled blocks). v2 is modules the model WRITES TO and
reads back — the bridge's write regime — and the model must be taught to listen. The rule that makes it a
framework: the model is taught a KIND OF SIGNAL, never a module — a fact of a table (a row, a number, yes/no, an
entity) encoded one fixed way at one kind of position; taught once per model, any module of that kind plugs in with
no training, as any reader plugs into the read side today.

- **The ops**: `Write(position, vector)`, `Mask(logits)` added to `Read`; the kinds of signal with their encodings;
  the version in a module's file.
- **A module**: a handler of an effect, and for a writing one, an ENCODER of its result into a kind of signal. A
  compiled okay-inlay block computes in its own residual (one-hot fields, e.g. 73 dimensions for the count of
  OrdersCountSuite), not in the model's 1024, so its encoder is a learned projection from its output fields to the
  kind of signal — trained once per kind, shared by every block that yields that kind; its input comes the same way
  from the readers.
- **The training per kind**: pairs "the teacher saw the fact in the text, the student gets it through the bus
  alone", the loss on the teacher's states after that point (okay-desk `Distill` with the write in the data); the
  exact judge; the kit with a threshold.
- **The first experiment**: a question needing a fact not in the prompt, the fact given only as a vector on the bus
  (from okay-inlay's block over the table's row); after training, the readers read the same slot as with the fact in
  the text. Holds on the sample → the framework is real; fails → the text tier stays for writes (always works, costs
  prompt length).
