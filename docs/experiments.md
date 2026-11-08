# Experiments

`experiments/*.sc` are scala-cli scripts written before the library: each builds a transformer by hand, runs it in
float32, and checks it against an ordinary implementation. Each prints `all checks passed` or `FAILED: …` and exits
1 on a mismatch. An experiment is never weakened to pass: a mismatch is a finding, recorded in the spec.

```sh
bin/check                                   # all of them; the last line of each, or the failure
scala-cli run experiments/Cek.sc            # one
```

The numbers below are the experiments' own output, recorded in `specs/inlay.md` §4 and `specs/dsl.md`.

## Data access and counting

| Script | Shows | Result |
|---|---|---|
| `Lookup.sc` | one head reads an external table by key | exact at λ ≥ ~210 (exp(−125) = 0 in float32); a missing key reads 0 (the null row); duplicate keys average to 30; a range averages to 17.5 |
| `CountJoin.sc` | COUNT by selector width + a ReLU ladder; JOIN by two heads | counts 3, 4, 0, 1 exact; at λ = 10 still 3 (the ladder corrects); join 7, 25, missing 0; a non-unique key gives 21.67 — a value of neither row |
| `Strawberry.sc` | counting a letter over characters and over BPE tokens | running counts exact; over tokens, spelling columns in the embedding + "layer cake" heads give 3 r in strawberry |

## Machines

| Script | Shows | Result |
|---|---|---|
| `Looped.sc` | a register machine as one looped block, the program in the context | d_model 62, 177 neurons, 1 head; A+B on 136 inputs and A+2B on 72 with the same weights, equal to the interpreter; only 0/1 after 80 steps; HALT a fixed point |
| `Cek.sc` | the CEK machine for call-by-value λ-calculus (de Bruijn) | 6 programs (Church booleans) equal to a CEK interpreter in the answer **and in the step count**; ~40k neurons from ~10 rules |
| `Handlers.sc` | deep effect handlers with **multi-shot** continuations inside the CEK transformer | capture = two pointers; `exists`/`forall` resume twice; 6 programs up to ∃x∃y∃z (822 steps) equal to a reference machine |
| `HandlersBinary.sc` | the same with binary pointers (log₂ N bits, ±1 heads) | equal again with **1,931–2,112 neurons instead of 168,458–176,975** (~85×) and a residual of 145–157 instead of 8,087 (~55×) |

## Logic and search

| Script | Shows | Result |
|---|---|---|
| `Datalog.sc` | transitive closure bottom-up; constants are positions, relations bit-rows | a 16-chain: 120 facts, the linear rule in 15 rounds, the doubling rule in 5; 5 random graphs equal to a naive engine |
| `OrParallel.sc` | or-parallel search: slots as processors, `CHOOSE` forks, children allocated by prefix counts | SAT over 3 variables, 8 formulas: model counts equal brute force; 4–14 parallel turns against 15–70 sequential steps (4 variables: 13–15 against 115–133) |
| `Dpll.sc` | DPLL: clauses are positions, branches are slots; conflict and unit-propagation heads over all branches at once | 11 instances equal brute force; **n = 20, m = 85 (UNSAT): 15 branch nodes, 11 turns, 14 slots at peak, against 1,048,576 leaves** |
| `Branching.sc` | the order of decisions from a noisy score; a dive on guessed values | counts equal brute force under every order (30 runs) — a bad order costs time, never correctness; exact guesses find all 6 in 12–20 turns with 0 branches |
| `Gated.sc` | branch only where the model is unsure | planted 3-SAT n = 24: every instance solved at 16.4–37.4 nodes on average against 70.6 for plain DPLL — **2–4× less work**, every answer checked (~20 min to run) |

## Masks and games

| Script | Shows | Result |
|---|---|---|
| `Decode.sc` | the decode port on a toy model: mask, force, roll back | a tool call by its schema (15 of 21 tokens forced); a secret never printed under two tokenizations; rolled back and replaced → `key = [hidden]` |
| `Wordle.sc` | Wordle's hint filter and move mask as a transformer | 8,506 words; after 2,479 moves the head's candidate set equals the reference every time; a toy model unmasked invents 1,368 words and plays 25 against the hints, masked 0 and 0, winning 92.7% in 4.69 moves (needs `/usr/share/dict/words`) |
| `QuoteWindow.sc` | how often a fixed window of the last k tokens allows more than the whole copy | k = 32: 0.1–1.5% of steps on real texts; k = 8: 2–8% (needs a cached Qwen3.6 tokenizer and local texts) |

`play/Wordle.sc` is not an experiment but the game: `scala-cli run play/Wordle.sc` (you guess) or `-- solve` (it
guesses your word).

## What the experiments established

- Hard attention is exact in float32 at large λ; "not found" and AVG come for free; a join needs a unique key.
- Counting is attention's width plus a ladder; machines step with AND-neurons; append-only memory suits
  functional machines, where multi-shot continuations cost nothing.
- Binary pointers make neuron counts grow with log N.
- Parallel positions turn search into BSP rounds: or-parallel SAT, DPLL with all branches propagated at once, and
  intuition (an order, a guess, a confidence) that can only cost time, never correctness.
- The library (`core`) now covers lookups, counts, rules, sets, the closure and quotes, and the machines: the CEK
  machine with effect handlers (`Cek`), or-parallel search (`OrParallel`) and DPLL (`Dpll`, on the covering head) are
  programs of the DSL with the law ([effects.md](effects.md)); the scripts stay as the first record.
