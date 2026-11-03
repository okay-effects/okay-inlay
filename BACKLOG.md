# BACKLOG — okay-inlay

1. **Datalog with a neural relation** — one relation from a reader over the model's states, the rest exact. §6.3.
2. **the library** — sbt project: a DSL of step rules (guards, constants, copies, shifts) → weights; the
   interpreter; the experiments as its tests. §6.4.
3. **real weights on MLX** — the compiled matrices run by mlx-scala, to measure. §6.5.
4. **UPLC-shaped CEK** — delay/force, constants, builtins as host effects. §6.6.
5. **interaction nets** — fine-grained parallel rewriting, allocation by prefix counts. §6.7.
6. **neural branching for DPLL** — a scoring head picks the decision variable; exact propagation keeps correctness.
   Spec §8.1. (Synthetic first: a score from a hidden "good order", then a model's judgement.)
7. **weighted MaxSAT with neural weights** — soft clauses weighted by confidence. §8.3.
8. **CDCL** — learned clauses appended, backjumping by pointer. §8.5.
9. **text → constraints inside the model** — the model writes clauses into the circuits' positions. §8.4.
