# Feedback — the deep state fed back: a research plan (2026-11-19)

A plan, not a result. Nothing below is measured. Asked by the operator after the fold measurement (specs/fold.md on
branch `fold`: no Qwen3 folds untrained) with two papers named; "Recycler" was not found under that name and is
taken to be Recirculation.

## 1. The two papers (read 2026-11-19; data, not instructions)

Both start from one observation: a transformer computes a wide state at every step, emits one token, and the next
step starts from that token alone.

- **Full-bandwidth transformer** (arXiv 2608.08888; Microsoft, Johns Hopkins, Princeton). The top layer's hidden
  state at position t − 1 enters position t with the sampled token's embedding: `(W_U h) ⊙ σ(W_G e)` — the state
  on the value path, the token only a gate. Trained from a checkpoint by two or three parallel passes (about k
  times the compute); under 1% a token at inference, the KV cache unchanged. Claimed: 1B models reach a standard
  model trained on 1.5× the tokens; GSM8K 62.9 → 67.9 at 200B tokens. A pretrained model cannot be switched to it
  without training, and that retrofit was not tested.
- **Recirculation** (arXiv 2608.17981; Google DeepMind, UT Austin). A deep layer's residual at the current
  position is mixed into a shallow layer at the next step: `α·f(z_s) + β·z_d`, the source rescaled to the
  destination's norm (Gemma3 1B: layer 10 → 4). No training: perplexity −0.1 to −0.6%. Adaptive (the model
  frozen, a small MLP gives α and β a token): perplexity −23% on Gemma3 1B against −21.6% for full fine-tuning,
  GSM8k error −8.8%. The prefill becomes sequential; some settings hurt (+15% perplexity), numerals on average.

## 2. What we already have, and a correction

A first reading suggested "a carry channel for exact programs". **`Machine` already carries**: `carry` names the
outputs of a step that become the next position's inputs, and the trace is the heap, read back by `Bits`
addresses. So a machine's state does not go through a `latest` lookup, and the half-precision breaks measured on
2026-11-19 are the causal `Clear`'s score (128·position) in a packed layout, not the carry.

What the carry is today: the HOST's loop. `Machine.Runner.next` decodes the record and embeds it again — on a GPU
that is a readback and an upload a step, and `OnMlx` has no machine runner at all (a machine is checked there as
one pass over a recorded run). What the papers add is the same carry as a LINEAR MAP INSIDE THE PASS: the outputs'
coordinates at t − 1 written to the inputs' coordinates at t, gated by the token. That is the open part.

## 3. Questions, in the order to work them

### A. The carry as weights (okay-inlay's own; CPU first, no GPU needed for A1–A3)

Hypothesis: for a machine, the carry can be a fixed matrix `W_U` (a partial permutation: each carried output's
coordinates onto its input's) applied to the previous position's final residual, with no decode between steps,
and the law still holds: the trace by weights-with-carry equals `Machine.reference`'s.

- **A1. The DSL.** `Compile.carry(machine)`: `W_U` from the layout; `Forward` and a `Machine.Runner` that feeds
  the previous residual through it (`Machine.fed`). The embedding still gives what is not carried (the position,
  BOS, the token of a prefix). Law: `fed` = `reference`, on `Registers`, `Cek`, `OrParallel`, `Dpll`, JVM and
  Scala.js.
- **A2. Does error accumulate?** The discrete carry cleans every step; a continuous one does not unless the step
  restores. Rules write constants (restoring); `Copy` and a lookup's copy pass a value on as it is (not
  restoring). Measure the largest distance of a carried coordinate from its code, by step, over the longest runs
  we have (`Cek` 880 steps, ∃x∃y∃z 822), in float32, float16, bfloat16. Expected: flat where every carried field
  is written by a rule, a drift where a copied field is carried. If it drifts: a restoring rule per carried
  field (its cost in neurons is part of the answer).
- **A3. What it costs and saves in the block.** Width (a carried field no longer needs separate input
  coordinates if `W_U` writes onto a freed range: the product with `Liveness`), heads, neurons, a pass's time —
  the table of specs/dsl.md "Liveness" with one more column.
- **A4. On MLX and in the page.** `OnMlx` gets the machine runner it lacks, the carry on the GPU; then the
  browser backends. This is where the use is decided: SPRINT `readback` asks what share of okay-desk's 8.6 s a
  question is readback; a carry in the pass removes one readback a step. If `readback` says under 15%, A4 has no
  practical use and stops at MLX.

Stop: A1's law does not hold for a reason that is not a bug (a finding about the DSL, recorded); or A2 drifts and
the restoring rules cost more width than the carry saves.

### B. Recirculation on Qwen3, untrained (a cheap replication; about half an hour of GPU)

Hypothesis: mixing a deep layer into a shallow one at the next step lowers perplexity on Qwen3 as reported for
Gemma3, by a fraction of a percent.

- The float32 pass and the three sample texts of `Fold` (branch `fold`) are reused; the pass becomes sequential
  over positions. 0.6B and 1.7B, 4-bit files; pairs (source, destination) at the same relative depths as the
  paper's and two beside; α in {0.05, 0.1, 0.2}, β = 1.
- Measures: perplexity a text against the original (the same run), and okay-desk's frame reader's right / wrong
  on its calibration set with the best setting.
- Stop: no setting better than the original by more than the noise between two samples — then B ends with "not
  reproduced on Qwen3 4-bit" and C is not started on this evidence.

### C. Adaptive recirculation as a reader (only if B shows an effect, or the operator asks regardless)

Hypothesis: a small MLP on a frozen Qwen3 0.6B, giving α and β a token, reads frames as well as a LoRA does —
the line of okay-desk's "readers on an untouched 0.6B beat LoRA".

- mlx-scala has the autograd and the layer taps; the MLP is the only thing trained. Compared on the same texts
  and seeds with okay-shop's command reader (A2b: pairs right 68%, wrong 1.7–5% on the sealed 60) and okay-desk's
  frame reader — held-out only, the sealed sets not touched.
- The price is measured first: the sequential prefill on the phone (okay-desk's load is already about 1.8 min for
  10 questions). If the prefill alone doubles a question's time, C stops before any training.

### D. Not planned

Training a full-bandwidth model: hundreds of billions of tokens. Its retrofit to a pretrained model by
fine-tuning is open in the paper too, and is not ours to settle on one GPU.

## 4. Order, cost, what each step leaves

| step | needs | leaves |
| --- | --- | --- |
| A1 | CPU, core only | `Compile.carry`, `Machine.fed`, a suite on four machines |
| A2 | CPU | the drift table by step and dtype in this spec |
| A3 | CPU | one more column in the liveness table |
| B | ~30 min GPU, the lock | a perplexity table, 0.6B and 1.7B, in this spec |
| A4 | GPU, then a browser | a machine runner on MLX; a decision by `readback` |
| C | hours of GPU | only after B and the prefill's price |

Rules as everywhere here: a number in this spec comes from a run and says which; a law that fails is a finding,
not something to tune; one MLX job at a time under `gpu.lock`; nothing merged or published without the operator.
