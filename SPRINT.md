# SPRINT — okay-inlay

- [~] **dsl-v0** — the DSL's core (specs/dsl.md, v0): an sbt project `core` (package `okay.inlay`), fields (`Cat`,
  `Bits`, `Flag`), ops (`Lookup`, `Rule`), programs and their checks; handlers `Interpret`, `Compile`, `Forward`; the
  law on random inputs for four programs (table lookup, previous token, induction + latest marker, rules). Gate: `sbt
  test`. The experiments stay; they migrate as the DSL covers them.

Done 2026-11-03: Wordle steps 1–3 (`experiments/Wordle.sc`, spec §4): the head's set equals the reference after 2,479
moves; the mask: 0 guesses against the hints, 0 invented words.
