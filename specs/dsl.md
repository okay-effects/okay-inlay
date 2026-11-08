# The inlay DSL — programs a transformer runs exactly (spec, 2026-11-04)

BACKLOG 2. The experiments built their weights by hand, each its own way; the DSL states such programs once, as
data, and HANDLERS decide where they run: the reference interpreter (the truth), the compiler into weights with a
numeric forward pass (exact by construction, the law checked), later the decode port (a logits filter in the engine),
MLX (mlx-scala), a block added to a real model. One program, many handlers — the okay rule.

## The model of computation

A sequence of positions (tokens). Each position holds a RECORD of named fields; a field has an encoding:

| Field | Values | In the residual |
|---|---|---|
| `Cat(name, values)` | one of `values`, or none | one-hot, `values.size` dims (none: all 0) |
| `Bits(name, n)` | an integer in `[0, 2ⁿ)` (a position, a pointer) | ±1 per bit, `n` dims (`HandlersBinary.sc`) |
| `Flag(name)` | true / false | 1 dim, 1 / 0 |

Fields are WRITE-ONCE (SSA): the inputs are given by the embedding, every other field written by exactly one op, read
only after the layer that writes it. The residual only adds — a field starts at 0 and is added once — so no op ever
has to erase (spec §2: "the residual only adds").

A program is a sequence of LAYERS; a layer is heads (in parallel), then rules (the MLP).

### Ops

- **`Lookup(copy, query, key, latest)`** — a head. Position i attends to positions j ≤ i whose `key` fields equal
  i's `query` fields (every pair; `Cat` by one-hot, `Bits` by ±1 — the score maximal only on equality); copies the
  `copy` sources at j into the destinations at i. No match: none (a BOS row with a fixed half score reads zeros).
  Several matches: their average — exact only for a unique key (spec §3), so `latest = true` makes the latest match
  win (score + ε·position). Compiled: W_Q, W_K from the fields' codes × λ (λ large: float32 underflow makes it hard),
  W_V/W_O copying the sources to the destinations.
- **`Rule(guards, sets)`** — MLP neurons. Guards: fields equal to values (`Cat`), flags true; sets: a constant into a
  `Cat` / `Flag`, or `Copy(dst, src)` of a `Cat`. Compiled: AND-neurons `ReLU(Σ guards − (n−1))` (Cek.sc's rules);
  a copy one neuron per value. A field written by rules and no rule firing: none.

The embedding gives each position its inputs (the token as a `Cat`, its position and the previous position as
`Bits`, a `Flag` for BOS); the read-out is the record's fields decoded (one-hot → value or none, sign → bit).

## Handlers

1. **`Interpret`** — the reference: records of values, each op on them as stated above. The truth.
2. **`Compile`** — the layout (each field's offset in the residual) and each layer's matrices; checks first (write
   once, read after written, a `latest` lookup needs positions).
3. **`Forward`** — the compiled weights run numerically (float32, softmax, ReLU), the result decoded. **The law:
   `decode(Forward(Compile(p), embed(x))) == Interpret(p, x)`**, property-tested on random inputs.
4. Later: `Port` (a program whose output is an allowed set run as mlx-scala's decode port), `Mlx` (the forward pass
   on MLX), `Attach` (the block added to a real model's layers).

## v0 — done when

- The core: fields, ops, programs, the checks; `Interpret`, `Compile`, `Forward`; plain Scala 3, no dependency.
- Programs as tests, each with the law on random inputs:
  1. a table lookup (Lookup.sc: price by SKU, none for a missing one);
  2. the previous token (a lookup by position − 1);
  3. **induction — the quote's core**: the previous token, then the positions whose previous token is this token, their
     token read (what a copy would continue with), and "the latest marker" (inside a quote or not) by `latest`;
  4. rules: a small state machine (Cek.sc's style), compiled into AND-neurons.
- The law holds for every program on hundreds of random inputs; a field's write-twice refused by the check.

Done 2026-11-04: v0 as stated — `LawSuite`, the law held on every program at the first run.

## v1 — the quote as weights (2026-11-04)

**A finding first: the runtime `Quote`'s state is not a fixed-depth transformer's.** mlx-scala's `Quote` (8e0c5f2)
keeps the candidate positions and narrows them each token: `candᵢ = { p + 1 | p ∈ candᵢ₋₁, source(p) = tokᵢ }` — a
recurrence along the generated positions. A layer at position i reads the previous positions' inputs to that layer,
not their final state; a recurrence over n positions needs n layers (or a loop, or the state carried in tokens).
Written without the recurrence, "the whole copy equals the span before p" compares every pair (i, p) over a length
that grows — not a fixed set of heads either. What a fixed depth does exactly is a WINDOW: the copy's last k tokens
against the k tokens before p (induction with k pairs). So (b) is stated against `Quote` with a window k:

- **`QuoteK`** (the reference, okay-inlay's tests): allowed after the open marker = `{ source(p) | the last min(m, k)
  copied tokens equal source[p − min(m,k) .. p − 1] }`, m the tokens copied so far; the close always.
- Equal to the full `Quote` while every window of k copied tokens occurs once in the source (or all its occurrences
  continue alike); beyond, it can splice two spans sharing k tokens. How often, on real logs and code, for k = 4, 8,
  16, 32: measured, not assumed.

**New in the DSL (each with the law):**

- `Many(name, values)` — a SET of values: one 0/1 dim each (decoded: the set).
- `Lookup(…, all = true)` — every match's source value gathered into a `Many` (OR): the head averages the one-hots,
  an MLP ladder in the same layer turns any share ≥ 1/maxLen into 1 (`ReLU(L·x) − ReLU(L·x − 1)`, L = 2·maxLen).
- `Lookup(…, wild = true)` — a query `Cat` field that is none matches any key (the pair skipped): linear in the query
  (`pairmax · (1 − Σ one-hot)`), so it is the same head.
- `Builtin.back(d)` — the position d back (none before the start): the k-window's fields by k heads.

**The program** (k fixed): `isMark`, `mark` (the latest marker, `latest`); `winₐ` for d = 1..k−1 — the token d back
if it is after the marker (a lookup on the position d back AND the same latest marker AND not a marker; none
otherwise → a wildcard); `prevₐ` for d = 1..k — every position's tokens d back; the induction lookup `all`, `wild`:
query (the current token if not a marker, win₁ … win_{k−1}) against (prev₁ … prev_k), source positions only, gathers
`allowed`. Read-out: `inside` (the latest marker is the open one) and `allowed`; the `Port` handler allows `allowed ∪
{close}` inside, any token outside.

**Done when:** the law for `Many`, `all`, `wild`, `back` on random inputs; the quote program equals `QuoteK` on every
step of random token streams with sources and markers; the window measured against the full `Quote` on real logs.
Then: `Count` (selector width), MLX, the block on Qwen.

Done 2026-11-04: `Many`, `Lookup` `all` / `wild`, `Builtin.back`; `QuoteSuite`: the quote program (4 layers; k = 6: 13
heads, 140 dims, 22 neurons) equals the reference and QuoteK at every position of 60 random streams for k = 1, 3, 6
(~400 steps inside quotes each, ~70% of them narrowed); a mutation (the window cut to one token) fails it.
`experiments/QuoteWindow.sc` — the window against the whole copy on real texts tokenized as Qwen3.6 does (copies of up
to 64 tokens from 400 starts; the share of steps where the window allows a token the whole copy does not):

| text | tokens | k = 4 | k = 8 | k = 16 | k = 32 |
|---|---|---|---|---|---|
| a system log (install.log) | 5965 | 10.42% | 6.02% | 4.79% | 1.54% |
| git log --stat | 5070 | 17.66% | 7.55% | 0.74% | 0.16% |
| Scala code (symdex's Tools.scala) | 4334 | 10.82% | 1.93% | 0.49% | 0.11% |
| an agent's journal (JSON) | 5454 | 13.15% | 8.41% | 4.25% | 0.78% |

A wider step is a splice made POSSIBLE, not made: the model still has to choose the other span. Logs and JSON repeat
long runs (timestamps, keys), so they need the widest window; k = 32 costs 32 + 31 heads of k pairs — the next
question is a cheaper exact form (a hash of the copy, or the state carried in the decode as the runtime does).

**The cheap form (2026-11-04).** The first form paid 2k heads: k for the tokens d back, k − 1 for the copy's window.
Both go: (1) DOUBLING — a position that knows its h previous tokens reads h more from the position h back, one head a
layer, so the k tokens back take ⌈log₂ k⌉ + 1 heads; (2) a COUNT (selector width, the new `Count` head: the start
scores as a match and gets 1/(c+1) of the attention, a ladder of steps reads c) of the tokens copied since the marker —
the token d back is in the window while the count is above d: a rule, no head. `QuoteSuite` (the cheap form equals the
reference and QuoteK at every position of 40 random streams of up to 62 tokens):

| k | layers | heads (first form) | dims | neurons |
|---|---|---|---|---|
| 8 | 6 | 7 (17) | 152 | 74 |
| 16 | 7 | 8 (33) | 246 | 130 |
| 32 | 8 | 9 (65) | 428 | 242 |

So the whole copy is exact in weights up to a length n with log₂ n + 4 heads — the cost moves to the width: k
fields of the token's code (one-hot here; ±1 bits for a real vocabulary: 18 dims a field for 150k tokens). Beyond n,
the window (QuoteWindow.sc above: k = 32 wider in 0.1–1.5% of steps).

**On the GPU and beside a real model (2026-11-04).** `OnMlx` (module `mlx`): the compiled weights as MLX matrices —
the quote programs decode to the reference at every position (window k = 3, cheap k = 8 and 32), float32's
underflow keeping the heads hard on the GPU as on the CPU. `Bits` got a presence dim (none linear: wildcards and
guarded copies of bits), so `Quotes.cheapBits` compares and copies tokens as ±1 bits and one-hot is left only where
the allowed set is gathered (equal to QuoteK for vocabularies of 6, 40, 300 — 300 words, k = 16: 8 heads, 1312
dims). `WeightsPort`: mlx-scala's decode port answered by that block run on the GPU beside Qwen3-4B — a compact
vocabulary per prompt (its tokens, the markers, "other"), the block over the tokens so far each step, the close and
the prefix tokens added by a fixed map. `QwenQuoteSuite`: 160 steps of Qwen3-4B on the log, 93 of them inside a
quote, the block's set equal to QuoteK at every one; the quotes are the log's lines; 2.8 s for the whole generation
(81 words, 8 heads, k = 16). The block decides, not Scala code; what is left of the runtime is the token map.

**Stage (c) — a standard weights file in an unmodified engine: not exactly (2026-11-04).** `HalfSuite` ran the cheap
quote (k = 8) with weights and activations in the formats a model file uses: float32 805 of 805 positions exact;
float16 768 of 880 (112 not even decodable); bfloat16 705 of 832 (127). The cause is the `latest` head: its scores
are 4·128·maxLen plus 128 a position (~65 536 here) — past float16's range (65 504), and bfloat16's 8-bit mantissa
steps by 512 there, wider than the 128 a position needs, so the latest match ties with earlier ones and they average.
The weights themselves can be made exact in bf16 (powers of two, small integers); what breaks is any intermediate
rounded to 16 bits — engines round matmul outputs (the scores) to the activation dtype. Beyond precision, a block
inside a dense model's own layers needs (1) residual dims the model does not use (it uses them all), (2) the token's
identity at the block's input (the model's residual at depth carries predictions, not the token), (3) float32 scores.
None holds in a standard model; so the block is a SIDE model with its own residual, combined with the model's logits
by the engine — stage (b), `WeightsPort` — and (c) is closed: an engine hook is the honest requirement.

## Machines (2026-11-08)

The machine experiments' style in the library. `Machine(step, carry, halted)`: a causal step program run in a loop —
the prefix's positions (a program, a table), then a position a step, its inputs the previous step's outputs named by
`carry` — until `halted`. The step's handlers (`Machine.Runner`): `Machine.reference` (`Interpret`'s ops at the new
position) and `Machine.weights` (`Forward.row`, one position through one layer — `Forward.layers` is it at every
position), each keeping what it computed for the earlier positions. Two additions to the DSL: `Builtin.next` (the
position + 1: the address of the cell a step writes) and the guards `Has` / `Lacks` on `Bits` (NIL is none).

`Cek` writes experiments/Handlers.sc's machine in it — call-by-value λ, deep handlers, multi-shot continuations — as
one layer: two heads (the term node at C, the cell at ADDR) and 22 rules; 3,909 neurons, residual 467, pointers of 11
bits for 1,024 positions. `CekSuite`: the six programs, the weights' trace equal to the reference's step by step, the
answers equal to the ordinary machine's; ∃x∃y∃z in 822 steps, as the experiment. The search machines (or-parallel,
DPLL: many slots a turn) are not yet in the library.

**Or-parallel search, a wide machine (2026-11-08).** `Machine.runWide`: a round of positions, one a slot. `OrParallel`
writes experiments/OrParallel.sc's machine in it — two phases a turn (A: the instruction, the ranks by `Count`; B: a
free slot reads the requester of its rank from phase A, every slot's transition), 5 layers, 1,065 neurons;
`OrParallelSuite` equal to the reference and brute force on 8 formulas. A DSL hole found on the way and closed: a
`Count`'s value is decoded by its layer's MLP, so a rule of the same layer read zeros on the weights while the
reference read the count — `Program.problems` now refuses it, as it refused a gathered set. DPLL waits for a head of
threshold scores (BACKLOG 16).

**DPLL, on a covering head (2026-11-08).** `Cover`: the key sets covered by the query's but for `slack` members,
some sets apart, the fewest uncovered gathered — linear in the sets, so compiled like any head; its reference states the
meaning, `CoverSuite` checks both. `Dpll` writes experiments/Dpll.sc with it (conflict: slack 0; units: slack 1, no
literal true), 8 layers; `DpllSuite` and `DpllBigSuite` (n = 20, m = 85: 15 branch nodes, as the experiment). With
the CEK machine and or-parallel search, the machine experiments are now programs of the library; BACKLOG 16 done.
