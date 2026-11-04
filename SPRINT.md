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

Done 2026-11-03: Wordle steps 1–3 (`experiments/Wordle.sc`, spec §4): the head's set equals the reference after 2,479
moves; the mask: 0 guesses against the hints, 0 invented words.
