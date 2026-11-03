# BACKLOG — okay-inlay

1. **or-parallel search** — the branches of a choice on separate positions, the same weights: `∃x∃y∃z` in the
   steps of one branch instead of their sum. Spec §6.1.
2. **binary pointers (±1 codes)** — log₂ N axes instead of N one-hot; copies cost log N neurons; attention margin 2.
   Spec §6.2.
3. **Datalog with a neural relation** — one relation from a reader over the model's states, the rest exact. §6.3.
4. **the library** — sbt project: a DSL of step rules (guards, constants, copies, shifts) → weights; the
   interpreter; the experiments as its tests. §6.4.
5. **real weights on MLX** — the compiled matrices run by mlx-scala, to measure. §6.5.
6. **UPLC-shaped CEK** — delay/force, constants, builtins as host effects. §6.6.
7. **interaction nets** — fine-grained parallel rewriting, allocation by prefix counts. §6.7.
