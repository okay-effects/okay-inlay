# BACKLOG — okay-inlay

0. **okay-datalog** (operator, 2026-11-03; first) — one Datalog program, many handlers: Eval (JVM + Scala.js for
   Workers), Proof (derivation semiring), Inlay (weights on MLX), Sql; semirings as handlers; laws Eval == Inlay ==
   Proof. specs/datalog.md; okay-facts §18 uses it.
1. **Datalog with a neural relation** — one relation from a reader over the model's states, the rest exact. §6.3.
2. **the library** — sbt project: a DSL of step rules (guards, constants, copies, shifts) → weights; the
   interpreter; the experiments as its tests. §6.4.
3. **real weights on MLX** — the compiled matrices run by mlx-scala, to measure. §6.5.
4. **UPLC-shaped CEK** — delay/force, constants, builtins as host effects. §6.6.
5. **interaction nets** — fine-grained parallel rewriting, allocation by prefix counts. §6.7.
6. **confidence-gated branching** — first result 2026-11-03 (`Gated.sc`: 2–4× less work than DPLL with the
   backup). Next: on a failed dive, widen from the least confident decisions first. §8.1.
7. **weighted MaxSAT with neural weights** — soft clauses weighted by confidence. §8.3.
8. **CDCL** — learned clauses appended, backjumping by pointer. §8.5.
9. **text → constraints inside the model** — the model writes clauses into the circuits' positions. §8.4.
10. **Wordle, steps 4–5** (operator, 2026-11-03; steps 1–3 done: `Wordle.sc`, §4) — the model's intuition: a toy
    one (word frequency), then a real Qwen 0.5–1B by mlx-scala; A the model alone, B the model + the CPU solver, C
    the model with the filter and the mask inside; wins, moves, guesses against the hints, invented words. Step 5
    (the one for practice): the filter and the mask inside a standard weights file, run by an unmodified engine —
    the "0 violations" kept with no outside code.

7. **okay-reason** (operator, 2026-11-03) — v0 BUILT 2026-11-04 in ~/work/my/okay-reason (its specs/reason.md: four products on one model) — exact reasoning as an MCP tool the model uses: general logic (assert,
   query, solve, why_not) + domain packs (failures first: explain → minimal explanations, how many, next check);
   benchmark A / T / E on okay-agent + Qwen. specs/reason.md.
