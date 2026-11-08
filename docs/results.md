# Results

Every number here comes from an experiment, a test or a bench; the source is named. Specs hold the full context.

## Compiled programs (the library)

| Program | Size | Law / result | Source |
|---|---|---|---|
| table lookup, previous token, induction + latest, rules | — | weights == interpreter on 200–300 random inputs each | `LawSuite` |
| `Count`, cap 100 | maxLen 128 | exact on 120 lists, n = 5…100 | `CountLawSuite` |
| "how many orders are unpaid?" over 40 rows | 1 layer, 73 dims, 29,492 parameters | 14, equal to the reference | `OrdersCountSuite` |
| quote window, k = 6 | 4 layers, 13 heads, 140 dims, 22 neurons | equal to QuoteK | `QuoteSuite`, `specs/dsl.md` |
| quote, cheap form, k = 8 / 16 / 32 | 6 / 7 / 8 layers; 7 / 8 / 9 heads; 152 / 246 / 428 dims | equal to QuoteK | `QuoteSuite` |
| quote, tokens as bits, 300 words, k = 16 | 8 heads, 1,312 dims | equal to QuoteK | `BitsQuoteSuite` |
| the CEK machine with effect handlers as a step program | 1 layer, 2 heads, 3,909 neurons, residual 467 | the six programs: the weights' trace == the reference's, the answers == the ordinary machine; ∃x∃y∃z 822 steps | `CekSuite` |
| the bridge as a program of effects | — | search == the reference == the weights (CPU, browser, GPU) on 30 texts | `BridgeSuite`, `BridgeOnMlxSuite` |
| or-parallel SAT as a wide machine (12 slots, two phases a turn) | 5 layers, 5 heads, 1,065 neurons, residual 396 | 8 formulas: the weights' trace == the reference's, solutions == brute force, 4–14 turns | `OrParallelSuite` |
| the covering head (DPLL's conflicts and units) | — | the law and the meaning on 300 random clause sets and assignments | `CoverSuite` |
| DPLL as a wide machine | 8 layers; n = 20: residual 2,972, 2,144 neurons | 7 formulas: the weights' trace == the reference's, #SAT == brute force; n = 20, m = 85 UNSAT: 15 branch nodes, 11 turns, 14 branches against 1,048,576 leaves | `DpllSuite`, `DpllBigSuite` |
| guided DPLL: the decision's handler is advice (value, sure) | 9 layers | to the first model on 4 planted 3-SAT (n = 12): plain 69 branch nodes, exact advice 4, noisy advice 24; weights == reference, every model checked | `GuidedSuite` |
| the bridge read by Qwen3.5-4B (ModelJudge: both), deduced on the GPU | — | 100% of sentences; the whole answer equal to the gold's on 10 of 10 texts (ask alone 6, the probe 2) | `ModelBridgeBench` |
| the bridge as an MCP tool | — | the tool's answer == the bridge's, over a scripted link and stdio | `ToolSuite` (mcp) |
| transitive closure, 12 names, 40 positions | 12 layers, 921 dims | exact on 40 random graphs (yes, sentences used, cycles) | `ClosureSuite`, `specs/bridge.md` §8 |
| the same, 16 names, 128 positions, 2⁵ hops | 14 layers, 2,930 dims | — | `specs/bridge.md` §8 |
| quotes on Apple's GPU beside Qwen3-4B | 81 words, k = 16, 8 heads | 160 steps, 93 inside a quote, equal at every step; 2.8 s | `QwenQuoteSuite` |
| half precision | — | float32 805/805; float16 768/880; bfloat16 705/832 | `HalfSuite` |

## Experiments

See [experiments.md](experiments.md) for all; the headline numbers: a CEK machine equal to an interpreter in answer
and step count (~40k neurons from ~10 rules); multi-shot effect handlers up to ∃x∃y∃z (822 steps); binary pointers
85× fewer neurons; DPLL refuting n = 20, m = 85 in 15 branch nodes against 1,048,576 leaves; confidence-gated
branching 2–4× less work than plain DPLL; Wordle's filter exact after 2,479 moves.

## The bridge (Qwen3.5-4B)

| Measure | Result | Source |
|---|---|---|
| bridge-1: a count with a probe-read predicate, layer 12 | per item 99.2–100%; whole count exact 5–6/6 at n = 5…80, where the model alone is 0–5/6 and thinking 0/6 at n = 40, 80 | `BridgeBench`, `specs/bridge.md` §6b |
| bridge-4: closure over sentences, BOTH readers | 100.0% of 1,230 sentences; closure 6/6 at n = 15…100 (alone 3–5/6); cycles 8/8 | `ClosureBench`, `specs/bridge.md` §8b |
| bridge-2: LoRA under the `Names` mask | inconclusive (the mask does not hold on Qwen3.5's tokenizer) | `MaskBench`, `specs/bridge.md` §12 |

## The core and the bus (okay-desk, Qwen3-0.6B)

| Model | Size | Sample en / ru (12-question set) | Source |
|---|---|---|---|
| Qwen3-0.6B, all 28 layers | 320 MB | 12, 11 | okay-desk `specs/data.md` §14 |
| cut19 (the reference, bus v1) | 244 MB | 12, 9 | §14 |
| the student, 12 layers, merged 4-bit | 122 MB | 11, 7 | §21 |
| the core (FREE + Russian's components removed, distilled) | 146 MB on the page | 12, 6 | §22b |

With the exact rules of §27–§29 (13-question set): cut19 and the core 13/13, 13/13 on the JVM; the core 13/13,
13/13 in the browser on WebGPU; held-out whole 97.7% (cut19) and 99.0% (the core).

| The bus adapter (Qwen3-1.7B → bus v1, the skill unchanged) | R² | Sample | Held-out | Source |
|---|---|---|---|---|
| ridge map, layer 19 | 0.906 | 13/13, 13/13 | 95.3% | okay-desk `BusAdapter`, `specs/data.md` §31 |
| control: states a constant | — | 13/13, 13/13 | 91.3% | same |

## Domains

`Units` (conversions and calendar arithmetic, 886 points): a keyword reader 88% whole and 0 wrong (`UnitsSuite`);
on cut19's bus (`DomainBench`): keywords 94%, probes 91% with 7.3% wrong, a margin of 0.5 52% with 3.0% wrong, a
hybrid 90% with 1.7% wrong.

<a id="forge"></a>
## forge (Qwen3-4B)

| Measure | Result | Source |
|---|---|---|
| steering directions, layers 9 / 18 / 27 | no gain (base 6 compile / 5 behave) | `SteerBench`, `specs/forge.md` |
| LoRA in Scala on 25 delegation tasks (rank 8, ~4.4M parameters, 59 s) | SymbolsBench 6/5 → 8/7 | `ForgeBench` |
| transfer: LoRA on 319 verified tasks from 9 other projects | symdex 55 tasks: 47 → 55 behave; general 9/20 → 10/20 | `TransferBench` |
| meaning tasks (40, four projects) | 19 → 27 behave | `MeaningBench` |
| code without invented APIs, the best arm | 9 compile / 7 behave of 10; the `Names` mask made small models worse | `SymbolsBench`, `specs/symbols.md` |
