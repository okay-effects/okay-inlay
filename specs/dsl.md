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

## Liveness: register allocation for the residual stream (2026-11-17; branch `liveness`)

SPRINT `liveness`; BACKLOG "Narrower exact blocks", idea 1. The plain layout (`Layout.of`, Tracr's too) gives every
field its own coordinates in every layer. A field nobody reads after layer L can give its coordinates to a later
field; the width is then the most dims alive at once, not their total. Built whole: the analysis, the `Clear` head
with its meaning in the reference, the compiler on a layout by liveness, and the law re-checked on every program.
The plain layout stays the default (`Compile(p)`); the packed one is `Liveness(p, keep)` →
`Compile(packed.program, packed.layout)`.

**The `Clear` head.** `Clear(fields)`: each position attends to itself alone and writes minus what `fields` hold
there. In the reference (`Interpret`) the fields are simply absent from the record afterwards. Compiled: a causal
program's latest visible position is itself, so the score is ε·position (`qk[One][PosNum] = Eps`: the neighbour
128 below, exp(−128) = 0 in float32); a program that is not causal compares its `pos` bits with themselves (a
mismatch a whole λ below) and the start, which has no position, finds itself by its own dim (`qk[Start][Start] =
λ`). `ov` is −1 on the fields' coordinates. The attention weight is exactly 1 and `x + (−x)` is exactly 0 in
floating point whatever x was (a gathered average, a share), so a cleared coordinate is 0 exactly — at every
position, the start too (`LivenessSuite` asserts the zeros).

**What write-once means now.** A NAME is still written once, and never again after it is cleared — a record is
still SSA. A COORDINATE is written once per life: it must be exactly 0 when a field is added to it, which is why a
field is cleared before its coordinates are handed on. A field is read only between its writing and its clearing.
The checks (`Program.problems`):
- the heads of the layer that clears a field still read it — heads read the records as the layer found them —
  but that layer's rules and everything later do not: "reads x after it is cleared";
- a field is cleared once, and only if written in an earlier layer: a head's write lands in the layer's output, so
  the same layer's clear would subtract nothing ("cleared before it is written");
- a cleared name is not written again ("written twice");
- a clear may name an earlier layer's scratch (`x#raw`, `x#share`), which no record ever holds;
- a clear in a program that is not causal needs the input `pos`, which is then never cleared.
`Interpret.run`, `Forward.run` and the machines' runners return `p.outputs` — every field but the cleared.

**When coordinates are free** (`Liveness.ranges`). A layer is two stages, the heads (in parallel, each reading the
layer's input) and then the MLP; a clear acts at the head stage. So a field last read by a HEAD of layer b is
cleared in layer b itself; one last read by the RULES of layer b (or by its MLP's ladder: a head's scratch) in
layer b + 1; one nobody reads and nobody keeps, in the layer after its writing. Coordinates cleared in layer c
serve any field written in layer c or later — also by a head of layer c itself: the heads add in parallel, minus
the old plus the new. (Exact for the 0 / ±1 codes; for an average of several matches the sum `(new − old) + old`
can differ from `new` in the last bit, far inside the ladders' and the decoder's tolerance, as an average of
thirds already is.) What is read out at the end — `keep`: a suite's read-out, a machine's carried outputs — is
never cleared.

**The colouring** (`Liveness.apply`). A field of d dims is d unit intervals: its coordinates need not be
neighbours (`Layout.scattered`, `Layout.ix(field, i)`), so this is interval colouring, not storage allocation
with fragmentation, and the sweep — fields by their first layer, each dim on the lowest free coordinate — is
optimal: the width is 3 + `Liveness.peak`, the most dims alive at once (`Packing` requires the equality, and that
no two fields sharing a coordinate are alive together). A `Clear` head is added only in a layer where coordinates
change hands, naming only the fields whose coordinates are taken again. A program that is not causal and has no
`pos` gets one (the embedding fills it), counted in its width.

**The law, before and after.** Every suite runs its program a second time, packed, on the inputs it already ran
(`Packing`, one or two lines a suite): the packed program's reference keeps the plain reference's values on every
field it still has; the packed weights, run, decode to the packed reference. For the machines the packed runners'
traces equal the plain reference's trace (restricted to the fields left), step by step, and the answers and step
counts are the same. `LivenessSuite`: the `Clear` head alone (causal and not), its checks, the ranges of a small
program by hand, and a mutation — the packed layout without its clears is not the program. `sbt coreJVM/test` and
`sbt coreJS/test`: 58 tests, 57 passed, 1 skipped (`DpllBigSuite`, opt-in), with the plain assertions unchanged.

**Measured** (the rows are printed by the suites: `sbt coreJVM/test 2>&1 | grep '^liveness'`). Layers; fields
(+ the heads' scratch); heads (+ the clears added); the width (residual dims) plain and packed, and its fall; the
width if the inputs too had to stay readable to the end (analysis only: not compiled); `Weights.params` (dense:
2d² a head, 2d a neuron) plain → packed. What is kept: a quote's `inside` and `allowed`; the closure's `yes`,
`on`, `cycle`; a count's count; a machine's carried outputs (DPLL: also what its `run` reads of a trace — `REQ`,
`P_HAS`, `PHASE`, `FREE`).

| program | layers | fields | heads | width | packed | fall | inputs kept | params |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| a table lookup (`LawSuite`) | 1 | 4 + 0 | 1 + 1 | 21 | 17 | 19.0% | 21 | 882 → 1,156 |
| the previous token (`LawSuite`) | 1 | 4 + 0 | 1 + 1 | 27 | 21 | 22.2% | 27 | 1,458 → 1,764 |
| induction, the latest marker (`LawSuite`) | 4 | 9 + 0 | 3 + 2 | 47 | 21 | 55.3% | 39 | 14,100 → 4,788 |
| rules only (`LawSuite`) | 1 | 5 + 0 | 0 + 0 | 14 | 14 | 0.0% | 14 | 168 → 168 |
| `Count`, cap 100 | 1 | 3 + 1 | 1 + 1 | 107 | 105 | 1.9% | 107 | 65,912 → 86,310 |
| `Count`, cap 64 (the orders) | 1 | 3 + 1 | 1 + 1 | 73 | 69 | 5.5% | 73 | 29,492 → 36,846 |
| `Cover` (conflict, units) | 1 | 9 + 3 | 2 + 1 | 55 | 29 | 47.3% | 55 | 14,960 → 6,554 |
| `Quotes.window`, k = 1 | 4 | 14 + 1 | 3 + 4 | 63 | 34 | 46.0% | 48 | 26,586 → 17,680 |
| `Quotes.window`, k = 3 | 4 | 20 + 1 | 7 + 4 | 97 | 67 | 30.9% | 74 | 135,994 → 101,706 |
| `Quotes.window`, k = 6 | 4 | 29 + 1 | 13 + 4 | 148 | 103 | 30.4% | 125 | 576,016 → 365,238 |
| `Quotes.cheap`, k = 1 | 5 | 15 + 2 | 4 + 5 | 66 | 36 | 45.5% | 50 | 38,148 → 25,128 |
| `Quotes.cheap`, k = 3 | 5 | 20 + 2 | 6 + 4 | 95 | 55 | 42.1% | 66 | 115,710 → 64,790 |
| `Quotes.cheap`, k = 6 | 6 | 27 + 2 | 7 + 4 | 135 | 78 | 42.2% | 106 | 271,350 → 143,208 |
| `Quotes.cheap`, k = 8 | 6 | 31 + 2 | 7 + 4 | 157 | 100 | 36.3% | 128 | 368,322 → 234,800 |
| `Quotes.cheap`, k = 16 | 7 | 48 + 2 | 8 + 5 | 252 | 188 | 25.4% | 223 | 1,081,584 → 967,824 |
| `Quotes.cheap`, k = 32 | 8 | 81 + 2 | 9 + 6 | 435 | 364 | 16.3% | 406 | 3,616,590 → 4,151,056 |
| `Quotes.cheapBits`, 6 words, k = 4 | 5 | 24 + 2 | 6 + 4 | 110 | 56 | 49.1% | 76 | 158,840 → 69,664 |
| `Quotes.cheapBits`, 40 words, k = 8 | 6 | 33 + 2 | 7 + 4 | 306 | 169 | 44.8% | 204 | 1,441,872 → 700,674 |
| `cheapBits`, 300 words, k = 16 | 7 | 50 + 2 | 8 + 5 | 1312 | 645 | 50.8% | 956 | 30,034,304 → 12,042,150 |
| `Closure`, 12 names, 4 rounds | 12 | 31 + 10 | 5 + 11 | 921 | 199 | 78.4% | 263 | 10,637,550 → 1,732,892 |
| `Registers` | 2 | 15 + 0 | 1 + 1 | 142 | 110 | 22.5% | 142 | 320,636 → 265,540 |
| `Cek`, 1,024 positions | 1 | 48 + 0 | 2 + 1 | 467 | 352 | 24.6% | 467 | 4,523,362 → 3,495,360 |
| `OrParallel`, 3 variables, 12 slots | 5 | 46 + 3 | 5 + 4 | 396 | 219 | 44.7% | 316 | 2,411,640 → 1,329,768 |
| `Dpll`, n = 3, 20 slots | 9 | 72 + 6 | 7 + 8 | 367 | 232 | 36.8% | 245 | 2,323,110 → 1,891,264 |
| `Dpll`, n = 6, 20 slots | 9 | 102 + 6 | 7 + 8 | 574 | 344 | 40.1% | 387 | 5,593,056 → 4,137,632 |
| `Dpll`, n = 8, 20 slots | 9 | 122 + 6 | 7 + 8 | 772 | 520 | 32.6% | 569 | 10,036,000 → 9,251,840 |
| `Dpll`, n = 10, 20 slots | 9 | 142 + 6 | 7 + 8 | 1018 | 744 | 26.9% | 799 | 17,346,720 → 18,680,352 |
| `Dpll`, n = 12, 24 slots (guided) | 9 | 162 + 6 | 7 + 8 | 1344 | 1032 | 23.2% | 1097 | 30,148,608 → 35,682,432 |
| `Dpll`, n = 20, 24 slots | 9 | 242 + 6 | 7 + 8 | 3000 | 2600 | 13.3% | 2689 | 148,704,000 → 222,476,800 |

(The last row: `DPLL_BIG=1 sbt -J-Xmx6g "coreJVM/testOnly okay.inlay.DpllBigSuite"` — n = 20, m = 85, 0 models, 15
branch nodes, the packed traces equal to the plain reference's; with sbt's default heap the suite runs out of
memory, plain weights and packed together.)

(`Horn` is not on `master`; it is not measured.)

**What the numbers say.**

1. *The idea is not closed, and it is not uniform.* The SPRINT's bar was a fall under about 20% everywhere. It
   is not under 20% everywhere: 78% on `Closure`, 45–51% on the quotes over bits, 30–46% on the quotes over
   one-hot tokens up to k = 8, 45% on `OrParallel`, 22–25% on `Registers` and `Cek`, 23–40% on `Dpll` up to
   n = 12. It IS under 20% on a bare `Count` (2–6%), a one-head table lookup (19%), rules alone (0%), the widest
   quote (k = 32: 16%) and DPLL at the experiment's size (n = 20: 13%). The expectation written before the
   measurement — modest — holds for those and was wrong for the deep programs of moderate size.
2. *Where the fall comes from.* (a) Rounds: `Closure` doubles for four rounds, and a round's sets die once the
   next round has united them, so only neighbouring rounds' sets are alive together, not all. (b) The inputs: a field
   read only by heads — a builtin position, a table's column, a machine's state consumed by the lookup — is free
   in the layer that reads it, and that layer's own results sit where it was. (c) The heads' scratch, alive for
   one layer. (d) Coordinates that need not be neighbours, so nothing is lost to fragmentation.
3. *One-step machines gain only from (b)* — as the SPRINT expected, for the reason it gave. With the inputs kept,
   `Cek`, `Registers`, `Cover` and the one-layer programs fall by 0: a one-layer step's state lives through the
   whole step. Their 22–47% is entirely the inputs handed over to the outputs, which is sound for a machine (the
   next step's inputs are embedded again from the carried outputs) but is a statement about the read-out: whoever
   wants to read a step's inputs from its last layer must keep them. The deep programs keep most of their fall
   with the inputs kept (`Closure` 921 → 263, `Dpll` n = 10 1018 → 799, `OrParallel` 396 → 316).
4. *Where it is weak, and why.* A quote's k previous tokens are the KEYS of the last layer's lookup, so they live
   to the end; they are most of the width, more so as k grows (k = 32: 16%). A count's width is its own answer's
   one-hot. DPLL's state is 2n one-hot fields of n values, carried from step to step and so alive throughout: it
   grows as n² while what dies grows as n, and the fall shrinks with n (n = 6: 40%, 10: 27%, 12: 23%, 20: 13%).
   All three are the encoding's width, not the layout's — BACKLOG's idea 2 (binary codes), not this one.
5. *The price: a head in nearly every layer, and dense parameters can RISE.* A clear is one more head (d × d
   twice in a dense file, though it holds one number in `qk` and a −1 a cleared dim in `ov`). `Dpll` goes from 7
   heads to 15 and, from n = 10 on, has MORE dense parameters packed than plain (17.3 M → 18.7 M; n = 12: 30.1 M →
   35.7 M; n = 20: 148.7 M → 222.5 M); so do `Quotes.cheap` k = 32 and the bare counts. Narrower is not smaller
   unless the heads are stored sparsely or the clears are fewer.

**Not done, not verified.**
- The packed weights were run by `Forward` on the CPU only (JVM and Scala.js). On MLX and in half precision the
  check is WRITTEN AND NOT RUN (2026-11-18, branch `packed-mlx`; "Liveness on MLX" below): the GPU, shared with
  other sessions, was never free. Not beside a model.
  A clear's score is ε·position, the same size as a `latest` lookup's position term — the one that broke float16.
- Fewer clears were not tried: a clear is placed in the first layer where a field is dead; placing it in the layer
  where its coordinates are first needed and merging would cut heads at no cost in width. A clear by the MLP (two
  neurons a dim, no head) would also free a rule-read field one stage earlier. Neither is built.
- The product with binary codes (BACKLOG "Narrower exact blocks", idea 2) is not measured.
- Nothing else uses the packed layout yet: `Compile(p)`, the bridge, the web page and the MLX benches are as they
  were.

### Liveness on MLX (2026-11-18, branch `packed-mlx`): written, NOT RUN

The operator's question: the law of a packed layout was checked by `Forward` on the CPU only — check it on the GPU,
in float32 and in the halves. The check is written and compiled; it has not run. Nothing below is a result on the
GPU, and no number of this section comes from MLX.

**What `OnMlx` needed (found by reading it; the fix compiled, not yet run on the GPU).** A scattered layout and a
`Clear` head need nothing in the matrices: `OnMlx` puts each head's dense `qk` and `ov` on the GPU whatever wrote
them, and embeds and decodes through `Forward`, which goes by `Layout.ix`. One thing was wrong for a packed
program: `records` and `last` decoded `Program.fields`, and a packed program's fields include the cleared ones,
whose coordinates hold a later field by the end — a wrong record or an `Inexact`. They now decode
`Program.outputs`, the same list when nothing is cleared, so the plain path computes what it did. Added:
`OnMlx.tapped`, `run` that also returns, for the layers asked, the residual as the layer found it and as its heads
left it (`run` is `tapped` with no layers: the same operations in the same order).

**The suites** (`mlx/src/test/scala/okay/inlay/gpu/`, cases in `Packed.scala`): 14 programs — `Quotes.window`
k = 3, `Quotes.cheap` k = 8 and 32 (`OnMlxSuite`'s), `Quotes.cheapBits` (6 words k = 4, 40 words k = 8, 300 words
k = 16), `Closure` (12 names, 4 rounds: the bridge's block), and the machines `Registers`, `Cek` (1,024 positions),
`OrParallel` (3 variables, 12 slots), `Dpll` (n = 3, 6, 8, 10; 20 slots). `OnMlx` has no machine runner; a machine
is checked as one pass over a whole run — the prefix and every state the plain reference fed the step program —
which a causal program makes the same records as the machine's trace (required of every sequence when it is built).
- `PackedOnMlxSuite`, float32, asserted: the packed weights on MLX decode to the packed reference at every
  position; the packed reference equals the plain one on the fields left; every coordinate a clear frees is exactly
  0 after its layer's heads, at every position (through `tapped`).
- `PackedHalfSuite`: plain and packed side by side in float32 (asserted), float16 and bfloat16 (measured) —
  positions exact, wrong, not decodable, the earliest position not exact with its sequence's length, and the
  cells not 0 after the clears with the largest magnitude left.
- `PackedTimeSuite`: milliseconds a pass, plain against packed, `Closure` and `Dpll` n = 8; five rounds of 20
  passes each after 5 unmeasured, a round's median. One run of it will still be one measurement.

**What a CPU run did show** (a temporary main over `Packed.cases` with the tests unforked, so no MLX was loaded; it
is not committed; its output is `~/work/my/okay-inlay-private/packed-mlx/dry.log`): the 14 cases build with the
widths of the table above; on every sequence the packed reference equals the plain one on the fields left; one
pass over each machine run is its trace; `Forward` on each case's shortest sequence equals the packed reference.

**What the zero check does not see** (the same log). Only coordinates freed and not written by a head of the same
layer can be read after the heads' stage; where a head takes them at once, the clear's zero and the new value are
one sum, and the decode is the only check. That is most layers of the quotes — `Quotes.cheap` k = 8 and k = 32
leave 1 coordinate to read (their last clearing layer), `Quotes.window` k = 3 leaves 5 — against 280 for `Closure`
(25 in layer 1, 51 in each of five more), 24 for `Cek`, 110 for `Dpll` n = 3.

**Expected, to be confirmed or refuted by the run (arithmetic, not measurement).** A causal clear's score is
128·position. In float16 that passes the largest finite value (65,504) after position 511, so a run longer than
that — `Cek`'s longest sequence here is 880 positions — should break by the clears alone, where its plain form may
not. In bfloat16 the integers above 256 are not all representable (an 8-bit mantissa), so the position itself, and
with it the score, ties between neighbours from there on: the machines' sequences of 100–350 positions are at risk
past 256. Under 64 positions (the quotes) a clear's scores are exact in both halves, so the packed quotes should
break where the plain ones do (`latest`) and not earlier. `Closure` is not causal: its clears compare `pos` bits,
scores of a few hundred — no reason seen for it to break in either half.

**Why it did not run.** One GPU, shared; the rule is three quiet minutes with no other MLX work, then a lock. The
waiter (`~/work/my/okay-inlay-private/packed-mlx/packedrun.zsh`, its log `wait.log` beside it) waited from 18:25:46
to 21:25:41 on 2026-11-18 and saw the GPU busy the whole time (another project's `tree1.sh`, `listwise1.sh`,
`base5.sh` and their JVMs; okay-shop's queue and the fold measurement waiting ahead of it); it gave up, started
nothing, took no lock. To run it, from `~/work/my/okay-inlay-private/packed-mlx/` (one sbt run, a few minutes; the
plain suites with it, `HalfSuite` among them — its plain numbers are stage (c)'s above):

```
S=okay.inlay.gpu; ./packedrun.zsh $(( $(date +%s) + 10800 )) run1 "mlx/testOnly $S.OnMlxSuite $S.HalfSuite \
$S.BridgeOnMlxSuite $S.PackedOnMlxSuite $S.PackedHalfSuite $S.PackedTimeSuite"
```

The rows are the lines with `packed-mlx` in `run1.log`. A mismatch in float32 is a finding about `Clear` or the
layout, not something to tune away.
