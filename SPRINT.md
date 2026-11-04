# SPRINT — okay-inlay

- [x] **dsl-v0** — the DSL's core (specs/dsl.md, v0), 2026-11-04: sbt project `core` (package `okay.inlay`); fields
  `Cat`, `Bits`, `Flag`; ops `Lookup` (pairs of query/key fields, `latest`), `Rule` (positive and negative guards,
  constants, flags, copies); write-once checks; handlers `Interpret`, `Compile`, `Forward`. `LawSuite`: the law
  (weights decode to the reference's records) on 200–300 random inputs each for a table lookup, the previous token,
  induction + the latest marker (the quote's core), rules — all held at first run. Scores: λ = 256 for a plain lookup
  (a match beats the start by 128, the start beats a mismatch by 128: exp(−128) = 0 in float32); `latest` adds
  128·position with λ = 4·128·maxLen. Next: v1 (spec) — `Count`, the allowed set as output, the `Port` handler, the
  quote program as weights == mlx-scala's `Quote` (8e0c5f2) on every step.

- [x] **dsl-v1** — the quote as weights (specs/dsl.md v1), 2026-11-04: Many, Lookup all/wild, Builtin.back; the quote
  program == QuoteK at every step (k = 1, 3, 6; a mutation fails it); the window against the whole copy on real texts
  (QuoteWindow.sc): k = 32 allows more in 0.1–1.5% of steps, k = 8 in 2–8%. Next: the program on MLX and as a block
  on Qwen; a cheaper exact form for long windows.

- [x] **dsl-mlx** (done 2026-11-04: OnMlx exact on the GPU; Quotes.cheapBits; WeightsPort beside Qwen3-4B — 160 steps,
  93 inside quotes, each equal to QuoteK; 2.8 s) — (1a) a handler `Mlx`: compiled weights run on MLX through mlx-scala (module `mlx`, JVM); the law MLX ==
  Forward on the LawSuite and QuoteSuite programs. (1b) a structured compile for a real vocabulary (tokens as ±1
  bits, heads with narrow projections, the allowed set scattered from the attention) and a `WeightsPort` in
  mlx-scala: the block run beside Qwen on each token, its `allowed` masking the logits; the law on Qwen3-4B
  generations: the block's allowed == QuoteK at every step.

- [x] **symbols-a** (done 2026-11-04: free 3 of 3 symdex members invented, held 0, compiles 5/10 both; the mask
  derails — `impure Symbols`, `Equivalence generators`; next in specs/symbols.md) — code without invented APIs, stage (a) (specs/symbols.md): `Names` port in mlx-scala (members
  from symdex `named(S, N)`), unit laws; the measure on Qwen3-4B: invented members free vs held, overrides, compiles.

- [x] **forge-1** (2026-11-04: steering no gain; training in Scala works; LoRA on 25 generated tasks lifts
  SymbolsBench 5 → 7 behave without thinking — specs/forge.md) — steering (specs/forge.md stage 1): a hook for hidden states in mlx-scala's forward pass, the
  direction "calls the API" − "reimplements / wraps in an object" from contrasting pairs, added while decoding; the
  measure on SymbolsBench (base: best arm 7/10 behave).

- [~] **forge-tool** — okay-forge (specs/forge.md "The tool"): tasks with checks from a symdex index (delegations +
  templates, split by object), batched eval, attempts kept by the verifier, LoRA training with the adapter saved,
  a regression set, serving. Measure each step on Qwen3-4B and Qwen3.5-4B.

Done 2026-11-03: Wordle steps 1–3 (`experiments/Wordle.sc`, spec §4): the head's set equals the reference after 2,479
moves; the mask: 0 guesses against the hints, 0 invented words.

- [x] **bridge-1** (2026-11-04, Qwen3.5-4B-MLX-4bit; specs/bridge.md §6, §6b) — the codes crossed: a probe on layer 12
  (chosen on validate phrases; per-layer hook added to Qwen3.5 in mlx-scala) reads the judgement at 99.2–100% from
  n = 5 to 80; C exact (gold 6/6 everywhere, count == probe hits always); the bridge exact 5–6/6 at n = 40 and 80
  where the model alone and with thinking are 0/6. Next: bridge-4 (§7).

- [x] **bridge-4** (2026-11-04, specs/bridge.md §8, §8b): the DSL grew causal=false, set-vs-one-hot membership, Union,
  In guards; `Closure` exact at first run; on Qwen3.5-4B the two-argument reader BOTH (probe on three positions +
  ask in both orders, scores summed over their spreads) reads 1,230 unseen-phrasing sentences at 100%, the closure
  exact 6/6 at n = 15..100 where the model alone is 3–5/6; cycles 8/8, attribution 5/5. Fork (i) of §10 passed.

- [ ] **browser-smoke** — the machines on Scala.js (specs/bridge.md §11): `Forward` with `Closure` in a page, equal
  to the JVM on the same inputs; then the WebGPU handler and the LLM runtime choice (WebLLM first). — Datalog with a neural relation in weights (specs/bridge.md §7; BACKLOG 1): sentences stating
  relations (50–200, 5–10 hops), entities by exact lookup on names, the relation read by Probe and by Ask (yes/no
  logits, no training), the closure by `Datalog.sc`'s machine in the DSL, every derived fact attributed to its
  readings; arms alone / thinking / bridge / gold; contradictions with the two sentences to blame. — count with a neural predicate (specs/bridge.md §4): a probe E on Qwen3-4B's hidden states (the
  item's last token, `Qwen3.Hook`) → `Flag`, `Count` in weights → the digits through the decode port; E trained at
  n ≤ 10, measured at n = 5..80 against M alone and the gold ceiling; the count error at n = 80 explained by the
  probe's per-item error. Then bridge-2 (LoRA under the `Names` mask on SymbolsBench), bridge-3 (compiled priors).
