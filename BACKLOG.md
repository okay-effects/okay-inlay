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

7. **okay-reason** (operator, 2026-11-03) — v0 BUILT 2026-11-04 in ~/work/my/okay-reliability (renamed; its specs/reliability.md) — exact reasoning as an MCP tool the model uses: general logic (assert,
   query, solve, why_not) + domain packs (failures first: explain → minimal explanations, how many, next check);
   benchmark A / T / E on okay-agent + Qwen. specs/reason.md.

11. **exact quotes** (operator, 2026-11-04; FIRST of the two) — a quote or a fact's reference copied from the source
    verbatim: the model opens a quote (a marker), every token after it must continue a span of the source in the
    context, until it closes. The model chooses WHAT to quote (its understanding); it cannot misquote or invent an
    identifier (order numbers, hashes, amounts, log lines). Stages: (a) the reference as a decode-port logits processor
    in okay-mlx (the allowed set = next tokens of the source positions matching what was copied so far); (b) the same
    as weights — an added block on a real model (Qwen through mlx-scala): a head "the latest marker" (copy mode on/off),
    previous-token heads making k-gram keys from ±1 token codes, an induction head to the matching positions, its read
    turned into a mask (−λ off the allowed set); law: (a) == (b) on every step; (c) the research step: the block inside a
    standard weights file run by an unmodified engine (boosts along the allowed tokens' embedding directions —
    approximate, measured). Benchmark: documents + questions that need quotes and identifiers; arms none / processor /
    weights; misquotes, invented ids, answers right, tokens/s. Users: okay-facts, okay-reason (citing logs), okay-agent.
    Gotchas: start only at a source token boundary (" order" vs "order"); several matching spans → the union allowed.
12. **code without invented APIs** (operator, 2026-11-04; after 11) — Scala written by a model that cannot name a
    method, class or import that does not exist: symdex's index (type → members, package → objects) as facts in
    attention memory; after `x.` a lookup head finds x's type, the mask allows its members only (a trie over tokens).
    Benchmark on okay-agent, real okay-* tasks, Qwen with and without: invented symbols (target 0), compiles first
    time, fix cycles, time; arms none / processor / weights.

13. **the bridge** (operator, 2026-11-04) — learned interfaces to exact machines: read / mask / write regimes, compiled
    priors, crystallization; specs/bridge.md; bridge-1..3 in SPRINT.
14. **a standard bus and a compatibility layer** (operator, 2026-11-06; SPEC specs/bus.md v1 written 2026-11-06, L0.5 the language components added, the kit, writes to the bus kept for v2; beside specs/core.md §4.3 "the bus as a
    contract" and §8's projection) — any core, any language, the same skill files: (L0) tokens and languages as
    modules (okay-desk §20 blocks, readers addressing positions by words/characters, not token ids); (L1) a core;
    (L2) a BUS ADAPTER per core — identity, a linear map, or distillation; (L3) the bus vN as a versioned spec: width,
    normalisation (per-dimension mean and spread), the prompt template, where readers read; (L4) skills (readers,
    compiled blocks) addressing L3 only; (L5) the typed protocol (Query, Datalog, effects). The bus's API is okay-desk's
    `Read` effect (States, Logits, Encode, Prompt, Note) — to be the standard, write ops added (add a vector at a
    position, a logits mask). A TCK: prompts, positions and the reference readers' answers; a core with its adapter is
    bus-vN compatible at ≥ a threshold. FIRST, an hour's test of whether a compatibility layer can be a matrix: ridge
    regression from Qwen3-1.7B, then Qwen3.5-0.8B (another layer family), to bus v1 (cut19's layer 19) on the desk's
    prompts; the teacher's skill file UNCHANGED on 12 + 12 questions (target 12/12, 9/12). Else: relative
    representations (similarities to standard anchor texts — no training), or distillation. Hard parts: writing to the
    bus (a core must be trained to listen), cross-family depth, the prompt template inside the contract.
