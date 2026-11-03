# okay-inlay — programs inlaid into transformer weights (spec, 2026-11-03)

Status: research. Eight experiments built and checked (§4); the library and the next experiments planned (§6).
Origin: the operator's design sessions of 2026-11-03, starting from Tracr (arXiv 2301.05062, compiling RASP into
transformer weights) and the "weight-space programming" post (MIT, 2026-11).

## 1. The idea

A transformer can be *built*, not only trained: attention heads and MLP neurons computed from a program, so the
model performs that program exactly. Speed is not the point — a CPU runs the same program millions of times faster.
The point is what an exact mechanism inside a model can be, and how it composes with what only the model has (its
understanding of text, its context, its internal state):

- exact data access (a table read by an attention head — Compiled Associative Memory, CAM);
- deduction (Datalog's rules as joins, all facts of a round at once);
- search (effect handlers with multi-shot continuations — choice and backtracking);
- computation (an interpreter — the program is data in the context, the weights are the machine).

The practical road (okay-agent `specs/in-generation.md`, okay-facts `specs/design.md`) uses the same concepts at the
runtime level: handlers in the decoding loop, the KV cache as a continuation, search by KV snapshots. Compiled
weights are the deepest port ("weights") of that design.

## 2. The building blocks (each one verified, §4)

- **A head is a lookup.** `W_QK` for one-hot keys is the predicate's truth table times λ; with λ large, softmax's
  losing scores underflow to exactly 0 in float32 (exp(−125) = 0), so attention is exactly hard. A row (BOS / NIL)
  with a fixed score makes "not found" read 0. Several matches average (SQL's AVG).
- **A neuron is a gate.** `ReLU(x₁ + … + xₙ − (n−1))` is AND over one-hot inputs; two ReLUs make a step (a threshold);
  a ladder of steps turns `1/(k+1)` into `k` (COUNT) and corrects small errors.
- **The residual only adds**: an assignment is "add the difference", or a fresh record per step.
- **Memory is append-only.** Immutable structures (environments, persistent stacks, monotone facts) fit it as they
  are; mutation needs "the latest write wins" (score λ·match + ε·position).
- **Exactness is kept** by one-hot (or ±1) codes, hard attention and gates: after hundreds of steps every value is
  exactly 0 or 1.
- **A transformer is a BSP machine**: positions are processors, a layer (or a loop's turn) is a superstep, the MLP is
  local computation, attention reads other processors. Sequential machines use one position; Datalog uses all.
- **Rules → neurons**: a step function written as a table of rules (guards, constants, copies of groups, shifts)
  compiles mechanically into AND-neurons (Cek.sc, Handlers.sc) — the core of the future library.

## 3. Lessons that shape the design

- A join is exact only if its key is unique (a non-unique key averages two rows into a value of neither) — the
  ontologies' `owl:FunctionalProperty`; a compiler must check it.
- A decode-level mask alone can leave the model with no good continuation (the secret demo printed garbage); roll
  back to a snapshot and replace. Points one can return to must be declared before (a hybrid model's recurrent state
  cannot be cut back).
- Functional machines (CEK's immutable environments, persistent stacks) need no trick at all in append-only memory;
  multi-shot continuations are free there (resuming twice = walking from the same pointers twice).
- For logic, Datalog (monotone, bottom-up) matches the architecture better than Prolog/WAM: facts are only added,
  rules are joins, rounds are parallel, the loop runs to a fixpoint; OWL 2 RL is Datalog. Nondeterministic search:
  effect handlers in weights, or KV snapshots in the runtime.

## 4. Experiments (experiments/*.sc; `bin/check` runs them all)

| Experiment | What it shows | Result (from its output) |
|---|---|---|
| `Lookup.sc` | one head reads an external table row by key | price 25 exactly at λ ≥ ~210; missing key 0 (null row); duplicates AVG 30; range AVG 17.5 |
| `CountJoin.sc` | COUNT by selector width + a ReLU ladder; JOIN by two heads | counts 3, 4, 0, 1 exact; at λ = 10 still 3 (the ladder corrects); JOIN o1 7, o2 25, missing 0; a non-unique key gives 21.67 |
| `Strawberry.sc` | counting a letter over characters and over BPE tokens | running counts exact; tokens: spelling columns in the embedding + "layer cake" heads (Σcᵢ = Σⱼ #{cᵢ ≥ j}) → 3 r in strawberry |
| `Looped.sc` | a register machine as a looped block (program in the context) | d_model 62, 177 neurons, 1 head; A+B on 136 inputs and A+2B on 72, the same weights, equal to the interpreter; only 0/1 after 80 steps; HALT a fixed point |
| `Decode.sc` | the decode port: mask, force, roll back | a tool call by its schema (15 of 21 tokens forced); a template with no digit and no name; a secret never printed under two tokenizations; rolled back and replaced → `key = [hidden]` |
| `Cek.sc` | the CEK machine for call-by-value λ (de Bruijn) inside a transformer | 6 programs (Church booleans) equal to an ordinary CEK interpreter in answer **and in step count**; ~40k neurons from ~10 rules |
| `Datalog.sc` | transitive closure bottom-up, constants as positions, relations as bit-rows | a 16-chain: 120 facts, linear rule 15 rounds, doubling rule 5; 5 random graphs equal to a naive engine |
| `Handlers.sc` | deep effect handlers with **multi-shot** continuations inside the CEK transformer | capture = two pointers (segment, handler); resume reinstalls the handler; `exists`/`forall` handlers resume twice; 6 programs up to `∃x∃y∃z` (822 steps) equal to a reference machine |

Neuron counts are dominated by copies of one-hot pointers (N ≈ positions); ±1 binary pointers cut them by tens of
times (§6.2).

## 5. What it could give (the case for the research)

Not faster computation. Exact composition over neural leaves: the program's control, aggregation, deduction and
search exact; its predicates the model's own judgements — counting complaints over a long thread, deducing over an
ontology with facts read from text, finding a combination that satisfies constraints, with a proof. The symbolic
part compiled into the same architecture and bound by effects, a handler moved out to the runtime where that is
cheaper without changing the program.

## 6. Plan

1. **Or-parallel search.** Each alternative of a choice runs as its own machine on its own position (the same
   weights); `∃x∃y∃z` then takes the steps of the longest branch, not their sum. Allocation of positions without
   conflicts by prefix counts (a spawner of rank r takes the r-th free slot — COUNT by selector width).
2. **Binary pointers.** ±1 codes of log₂ N axes; a pointer's copy costs log N neurons; a head's score is maximal
   (= log₂ N) only on equality, others ≤ log₂ N − 2 (margin 2, λ larger).
3. **Datalog with a neural relation.** One relation produced by a reader over a model's states (calibrated), the rest
   exact — the bridge to "the model's understanding + exact deduction".
4. **The library.** sbt, Scala 3: a DSL of step rules → weights, encodings (one-hot, ±1, numeric), layer and
   subspace allocation, the reference interpreter, verification by enumeration; the experiments become its tests.
5. **Real weights on MLX** through mlx-scala: matrices, not Scala loops; measure.
6. **A UPLC-shaped CEK**: delay/force, constr/case, constants; builtins on big integers as host effects (the decode
   port) — the bridge to okay-uplc; ExUnits as step counts.
7. **Interaction nets**: local rewriting of all active pairs per layer — the most parallel machine.

## 7. Related work

Tracr (compiling RASP); RASP and RASP-L (what transformers can learn and length-generalize); Learning Transformer
Programs (the reverse direction); looped transformers as programmable computers (Giannou et al. 2023); ALTA (loops);
InterpBench / TracrBench (models with known circuits); Memorizing Transformers, RETRO, KBLaM (memory in attention);
DeepProbLog, Scallop, Logic Tensor Networks (logic with neural predicates); Logic-LM (LLM + solver); effect handlers'
abstract machines (Hillerström & Lindley); monitor-guided decoding (static analysis constraining code LMs).
