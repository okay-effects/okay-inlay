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
6. **confidence-gated branching** — the model gives a value and a confidence per variable: dive where sure, fork
   where not (a beam by uncertainty); measure solutions found and nodes against plain DPLL and a lone dive. §8.1;
   first results in `Branching.sc`.
7. **weighted MaxSAT with neural weights** — soft clauses weighted by confidence. §8.3.
8. **CDCL** — learned clauses appended, backjumping by pointer. §8.5.
9. **text → constraints inside the model** — the model writes clauses into the circuits' positions. §8.4.
