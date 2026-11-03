# okay-inlay — programs inlaid into transformer weights (spec, 2026-11-03)

Status: research. Thirteen experiments built and checked (§4); the library and the next experiments planned (§6).
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

- **`Lookup.sc`** — one head reads an external table row by key. Price 25 exactly at λ ≥ ~210; a missing key reads
  0 (the null row); duplicates AVG 30; a range AVG 17.5.
- **`CountJoin.sc`** — COUNT by selector width + a ReLU ladder; JOIN by two heads. Counts 3, 4, 0, 1 exact; at
  λ = 10 still 3 (the ladder corrects); JOIN o1 7, o2 25, missing 0; a non-unique key gives 21.67.
- **`Strawberry.sc`** — counting a letter over characters and over BPE tokens. Running counts exact; tokens: spelling
  columns in the embedding + "layer cake" heads (Σcᵢ = Σⱼ #{cᵢ ≥ j}) → 3 r in strawberry.
- **`Looped.sc`** — a register machine as a looped block, the program in the context. d_model 62, 177 neurons, 1
  head; A+B on 136 inputs and A+2B on 72 with the same weights, equal to the interpreter; only 0/1 after 80 steps;
  HALT a fixed point.
- **`Decode.sc`** — the decode port: mask, force, roll back. A tool call by its schema (15 of 21 tokens forced); a
  template with no digit and no name; a secret never printed under two tokenizations; rolled back and replaced →
  `key = [hidden]`.
- **`Cek.sc`** — the CEK machine for call-by-value λ (de Bruijn) inside a transformer. 6 programs (Church booleans)
  equal to an ordinary CEK interpreter in answer **and in step count**; ~40k neurons from ~10 rules.
- **`Datalog.sc`** — transitive closure bottom-up, constants as positions, relations as bit-rows. A 16-chain: 120
  facts, linear rule 15 rounds, doubling rule 5; 5 random graphs equal to a naive engine.
- **`Handlers.sc`** — deep effect handlers with **multi-shot** continuations inside the CEK transformer. Capture = two
  pointers (segment, handler); resume reinstalls the handler; `exists` / `forall` handlers resume twice; 6 programs
  up to `∃x∃y∃z` (822 steps) equal to a reference machine.
- **`HandlersBinary.sc`** — the same machine and rules with **binary pointers**: a pointer is log₂ N bits (0/1); a
  copy is B neurons; `SELF + 1` an increment circuit (bit' = bit + carry − 2·(bit ∧ carry)); a guard "pointer =
  NIL" one neuron with weight −1 on each bit; heads compare ±1 codes (q·k = B on the address, ≤ B − 2 elsewhere —
  margin 2λ, still exactly hard). The 6 programs equal to the reference again, with **1,931–2,112 neurons instead of
  168,458–176,975** (~85× fewer) and a **residual of 145–157 instead of 8,087** (~55×). Neurons now grow with log N,
  not N.
- **`OrParallel.sc`** — or-parallel search: a pool of slots (positions) as processors running one program; `CHOOSE`
  forks (the slot takes true, a free slot becomes its child with false), children allocated without conflicts by
  prefix counts (a requester of rank k takes the free slot of rank k), `REJECT` recycles a slot, a COUNT head reads
  the number of solutions at the end. SAT over 3 variables, 8 formulas: model counts equal to brute force; 4–14
  parallel turns against 15–70 sequential steps (all branches in turn); 4 variables (run with NV = 4, P = 40):
  13–15 turns against 115–133 — the parallel count grows with the program's length, the sequential one doubles with
  every variable.
- **`Dpll.sc`** — DPLL inside a transformer: clauses are positions (POS / NEG masks), branches are slots (TRUE /
  FALSE rows). Per turn, for all branches at once: a conflict head (score λ·(#false − |C| − |C|·#true): 0 only on a
  falsified clause) and a units head (λ·(#false − (|C|−1) − |C|·#true): 0 only on a unit clause; the mean of the unit
  clauses' masks, masked by "unassigned", is exactly the set of implied literals — unit propagation over every clause
  in parallel); the MLP rejects on a conflict (or a variable implied both ways — a bug the first run caught: counts
  too high), propagates, accepts a full assignment, or decides the first unassigned variable by forking (children by
  prefix counts). The accepted leaves are the models (#SAT). 11 instances, counts equal to brute force: random 3-SAT
  n = 10, m = 42: 7–14 branch nodes against 1,024 leaves; n = 16, m = 68: 23–41 against 65,536; **n = 20, m = 85
  (UNSAT): 15 branch nodes, 11 turns, 14 slots at peak, against 1,048,576 leaves**.
- **`Branching.sc`** — intuition in DPLL (spec §8.1). (a) **Counting, the order of decisions** from a noisy "which
  variable is a key" score, on instances with k hidden key variables (the rest defined by XOR chains): the model
  counts equal brute force under **every** order (30 runs — a bad ranking costs branches, never correctness); keys
  first bounds the tree by 2ᵏ (16 / 32 / 64 nodes, no blow-up like 116 or 60), but is not the smallest on average
  (index 40, ε = 0.5 21, ε = 0 32 nodes): small trees come from orders that conflict early, not from structural
  knowledge — what the right intuition has to be about. (b) **Finding one solution: a dive** that follows guessed
  values without forking (planted 3-SAT, n = 20, m = 85): with exact guesses found in all 6 in 12–20 turns and zero
  branches (full search 26–95 nodes), each answer checked independently; with noisy guesses a lone dive is brittle
  (p = 0.1: 2 of 6), the full search the backup. Next: branch only where the model is unsure (§8.1).
- **`Gated.sc`** — branch where unsure (spec §8.1): a calibrated model gives a value and a confidence per variable
  (on a share q it "knows", 0.95; elsewhere 0.55; a guess right with the probability of its confidence); confident
  decisions dive (no fork), unsure ones fork (the guess first); plain DPLL as the backup when nothing is found.
  Planted 3-SAT, n = 24, m = 102, 8 instances per q; plain DPLL to the first solution: 70.6 branch nodes, 43.8 slots
  at peak. Branching where unsure finds 4–8 of 8 with **1.0–36.4 nodes**; with the backup, every instance solved at
  **16.4–37.4 nodes on average — 2–4× less work than plain DPLL**, every answer checked. q = 0.5: 6 of 8 found with
  7.9 nodes and 5 slots; q = 0.25 (mostly unsure): 8 of 8, 36.4 nodes, 22.8 slots. The failures come from wrong
  confident guesses (0.95 over 24 variables: one wrong is likely) — next: on a failed dive, widen from the least
  confident decisions first. Slow to run (~20 min: 120 transformer runs simulated in Scala loops).
- **`Wordle.sc`** — Wordle's hint filter and its move mask inside a transformer (operator, 2026-11-03; steps 1–3).
  Dictionary: the 8,506 five-letter lowercase words of /usr/share/dict/words (web2; many rare ones); 300 answers by
  a seed. The reference: w is a candidate iff each guess would give w the same feedback (repeated letters as Wordle
  counts them). Variant B, a CPU solver in hard mode (most common letters by place and presence): **96.0% won within
  6, 4.23 moves on average**. The filter: a word is a position, one-hot letters 5 × 26; an MLP counts letters (a
  ReLU ladder: ≥ j copies of L, j ≤ 5); a hint compiles to literals — green: the letter here; yellow / grey: not
  here; the count ≥ n, or = n once a grey of the letter was seen — a linear violation count V(w); the head "allowed
  words" scores −λ·V. **After 2,479 moves (the solver's games, 200 random six-guess games, repeated letters by hand)
  the head's set equals the reference every time**, its weights uniform on it and exactly 0 elsewhere; the gate
  relu(1 − V) is 0/1 and equal too. The move mask (as `Decode.sc`): the letters written so far are green literals of
  the same head, its value the next letter's one-hot, the mask its output > 0. A toy model wanting the dictionary's
  common letters, the hints unseen, over 1,406 states: unmasked **1,368 invented words and 25 guesses against the
  hints**; masked **0 and 0**, never a dead end, and it wins 92.7% in 4.69 moves. ~9 s to run. Next: steps 4–5
  (BACKLOG).

Neuron counts of the one-hot versions are dominated by copies of one-hot pointers (N ≈ positions); binary pointers
cut them ~85× (`HandlersBinary.sc`).

## 5. What it could give (the case for the research)

Not faster computation. Exact composition over neural leaves: the program's control, aggregation, deduction and
search exact; its predicates the model's own judgements — counting complaints over a long thread, deducing over an
ontology with facts read from text, finding a combination that satisfies constraints, with a proof. The symbolic
part compiled into the same architecture and bound by effects, a handler moved out to the runtime where that is
cheaper without changing the program.

## 6. Plan

1. **Or-parallel search** — done 2026-11-03 (`OrParallel.sc`, §4). Each alternative of a choice runs as its own
   machine on its own position (the same
   weights); `∃x∃y∃z` then takes the steps of the longest branch, not their sum. Allocation of positions without
   conflicts by prefix counts (a spawner of rank r takes the r-th free slot — COUNT by selector width).
2. **Binary pointers** — done 2026-11-03 (`HandlersBinary.sc`, §4). ±1 codes of log₂ N axes; a pointer's copy
   costs log N neurons; a head's score is maximal (= log₂ N) only on equality, others ≤ log₂ N − 2 (margin 2).
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

## 8. The goal: brute force and understanding in one model (operator, 2026-11-03)

"We integrate the brute force of Datalog and a SAT solver with the semantic understanding inside the model itself."
Exact, exhaustive, parallel mechanisms (Datalog's rounds, DPLL's propagation and branching, lookups) compiled into
the model; the model's own understanding supplies what they cannot: facts read from text, constraints written from
a request, soft preferences with weights, and the intuition of where to branch. The meeting points:

1. **Neural branching** — first results 2026-11-03 (`Branching.sc`, §4); next: confidence-gated branching (dive where
   the model is sure, fork where it is not). DPLL's "first unassigned variable" replaced by a score from the model
   (which variable, which value first); propagation and checks stay exact, so a bad hunch costs time, never
   correctness. Measure branch nodes against the plain rule.
2. **Neural facts.** Datalog with one relation produced by a reader over the model's states (calibrated by
   activations, then trained if needed); every derived fact carries whether it rests on a neural fact.
3. **Soft constraints with neural weights** — weighted MaxSAT: hard clauses exact, soft ones weighted by the model's
   confidence (a client "probably prefers mornings").
4. **Text → constraints.** The model writes the clauses / rules (a semantic parse) into the positions the compiled
   circuits read — the whole loop inside one forward computation, no tool call.
5. **Clause learning (CDCL).** A conflict's learned clause appended as a new position (append-only memory fits);
   backjumping by restoring a pointer.
6. **Capacity and overflow.** What fits (clauses, facts, branches) runs inside; beyond it the same program's
   handler moves to the runtime (a CPU solver as an effect) — one program, two handlers.
