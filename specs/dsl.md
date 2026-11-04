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

Next (v1): `Count` (selector width, CountJoin.sc), numeric fields, the allowed set as output and the `Port` handler
— then the quote program run as weights == mlx-scala's `Quote` on every step (specs/quotes.md stage b).
