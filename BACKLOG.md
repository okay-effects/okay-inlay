# BACKLOG — okay-inlay

17. **Common Horn inference** (2026-11-08; BUILT, specs/horn.md): bounded recurrent compiled block, fixed point,
    fired-rule proof trace; `HornSuite` on JVM and Scala.js. Used by okay-desk's common sourced reasoning language.

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
14. **a standard bus and a compatibility layer** (operator, 2026-11-06; SPEC specs/bus.md, rewritten 2026-11-06
    around the goal: any model learns the bus by a fixed recipe from the kit; exact computation, measured reading;
    a text tier for closed models; writes in v2; beside specs/core.md §4.3 "the bus as a contract" and §8's
    projection) — any core, any language, the same skill files: (L0) tokens and languages as
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
15. **domains: a framework for reading meanings** (operator, 2026-11-07; SPEC specs/domains.md) — a domain declares
    its space (schema, point, enumerate, random, exact run, phrases, slots, parts/assemble) and gets pairs, reading
    with abstention and the measure by constructor from `Kit`; tables (okay-desk's Space) the first instance, units and
    the calendar the second; the test: linear probes on bus v1's states for the second family (bus.md §5's kit).
16. **DPLL in the DSL** (2026-11-08; DONE the same day: the head `Cover`, `Dpll`, `DpllSuite`) — experiments/Dpll.sc as a wide machine; its heads score a weighted count
    (false literals against a clause's length: λ·(#false − |C| − |C|·#true)), not equality: the DSL needs a head of
    threshold scores (a score linear in a set's members, its interpreter's meaning stated) — then conflicts and unit
    propagation over all clauses and branches at once, the law against brute force as for `OrParallel`.

## okay-desk's frame training into forge (later; 2026-11-15)

- okay-desk trains its frame reader itself (`bench/FrameTrain.scala`: LoRA rank 16 on the released Qwen3 0.6B, seeds,
  `Everyday` as the measure — specs/frames.md there). When the phone line (okay-desk specs/phone.md) has its result,
  forge absorbs it as core-joint §7 planned: one pipeline (sources, verifiers, objectives, measures, the phone's
  format) with FrameTrain's numbers unchanged. Not before — the line is moving fast and owns its tools.

## Ground truth for interpretability (2026-11-17; the operator's question "what is okay-inlay good for")

- **B0 DONE — what exists**: Tracr (RASP → weights, made as "a laboratory for interpretability",
  https://arxiv.org/abs/2301.05062); TracrBench (121 RASP programs with weights, https://arxiv.org/abs/2409.13714);
  **InterpBench** (NeurIPS 2024, https://arxiv.org/abs/2407.14494): transformers TRAINED to keep a Tracr circuit
  (SIIT), so their weights look like a trained model's; later thousands of models; circuit-discovery methods
  benchmarked on it; a 2026 follow-up asks agents to explain its circuits. Its authors' objection to plain
  compiled weights holds for ours too: mostly zeros, no superposition, unlike a trained network.
  So "a benchmark of compiled programs with known circuits" is DONE by others; do not repeat it.
- **What is still ours** (each to be checked against that literature before work): (1) programs RASP benchmarks
  do not have — machines that run many steps (CEK, effect handlers with multi-shot continuations, Datalog, DPLL,
  `Looped`); (2) an exact block BESIDE a real language model (`WeightsPort` on Qwen): a known circuit in a real
  model's stream; (3) the law `weights == interpreter` as a test, kept after any change of the weights.
- **If taken up** (small, in this order): B1 the map of a compiled program — which head and neuron does which
  operation, which coordinates hold which field — written by `Compile` and tested by ablation (switching the named
  head off breaks the named operation only); B4 probes and activation patching on the joint stream of Qwen + the
  quote block, scored against the map. Dropped: our own rotated/noised variants (SIIT does it better) and a broad
  model zoo.

## Narrower exact blocks (2026-11-17, from the operator's "can the sparse weights be compressed, differently")

What the literature says (checked 2026-11-17): Tracr §5 compresses compiled models by a LEARNED projection and
gets superposition, sometimes with another encoding of a variable (https://arxiv.org/abs/2301.05062); a theory of
computing in superposition with error bounds and error-correcting layers (https://arxiv.org/abs/2408.05451), its
lower bounds (https://arxiv.org/abs/2409.15318); Tracr models compressed again in 2025: algorithmic tasks RESIST
superposition — many features active at once (https://arxiv.org/abs/2512.13568); Tracr-Injection distils a
compiled algorithm into a pretrained model (https://arxiv.org/abs/2505.10719). So expect a few times, not orders.
Not found: liveness/register allocation in a compiler to weights (Tracr names the lack of reuse itself); a
compiler ALTA exists and is unread. Ideas 3–7 below are NOT checked against the literature yet.

1. **Liveness** — SPRINT `liveness`.
2. **The product with the encoding**: fewer fields alive (liveness) × fewer coordinates a field (binary codes,
   `HandlersBinary.sc`: 85× fewer neurons). Both exact; measure the product on the same programs.
3. **A ledger of the margin in the compiler**: half precision, a projection, quantised weights all spend the one
   margin between a match and a mismatch (128 in the scores now). Each pass declares what it spends; `Compile`
   proves the sum is under the margin, by the worst case over the finite fields. The fp16 break of `latest`
   (found by experiment) becomes a compile error.
4. **Spilling to tokens**: three places for a field — the stream's width, the depth (a looped layer), the sequence
   (a scratch token written and read back by attention). Given a width budget (a phone), the compiler keeps what
   fits and spills the rest. "Compile for a device."
5. **The program as data**: one fixed interpreter block (the CEK machine is in weights already), programs given as
   tokens; a new program needs no new weights; the small model writes it, the block runs it in the same pass.
   Prior art: looped transformers as programmable computers — to read first.
6. **Numbers as angles**: a counter as phases on circles of coprime periods (3, 5, 7 hold 105 values in 15
   coordinates), addition a rotation; what trained networks find for modular arithmetic. Order comparisons are
   hard in it.
7. **Codes, a superoptimiser** (weaker): fields on code words of a known minimum distance instead of random
   directions (the interference bounded by construction); a learned compression as the candidate with an
   exhaustive check over a finite domain as the judge.
8. **Training through the exact part needs a soft λ**: a hard attention (λ = 256) has almost no gradient, so the
   block is differentiable in form only. Soften λ while training, restore it, re-check the law. Possibly what
   bridge-2 lacks — unchecked.

Uses, honestly: a CPU runs any of these programs better. What a narrow block buys: a mask computed in the same
pass with no readback in the browser (SPRINT `readback` measures whether that matters); a skill as a file of a
few kilobytes beside an unchanged base, loaded by the model's own engine; a training signal through the block
(item 8). Not promised: the block INSIDE the model as an ordinary adapter (an adapter changes the model's heads,
the block needs its own).
