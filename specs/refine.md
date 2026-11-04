# Refining a small model (spec, 2026-11-04)

The operator's goal (2026-11-04): not a bigger model — a small one (Qwen3-4B) made good at the work, by changing the
MODEL, with inlay as the platform. The measured gap (specs/symbols.md): on symdex's API, Qwen3-4B with the best
prompt (the members it needs, their real calls, thinking, five rounds with the compiler) compiles 9 of 10 and behaves
7 of 10; the misses are habits and meaning — it wraps its answer in its own `object Equivalence { … }` though told
not to, reimplements what it should call, writes `split("(")` (a regex). A mask cannot fix a habit (it goes around
it); knowledge in the prompt fixed most names. What is left is in the weights.

## Four levers, cheapest first, all in Scala, all measured

1. **Steering** (no training): the habit as a direction in the residual stream — the mean difference of hidden states
   between contrasting answers (calls the API / reimplements it; no wrapper / `object X { … }`), added at a layer
   while decoding. A hook in mlx-scala's forward pass. Tells cheaply whether the failure is a direction at all.
2. **Training in Scala**: mlx-c's `mlx_value_and_grad` bound through okm (a C trampoline calling back into Scala: an
   FFM upcall on the JVM, a `CFuncPtr` natively); LoRA adapters on Qwen3's projections; AdamW; cross-entropy on the
   answer's tokens. The adapter a safetensors file beside the model, loaded by mlx-scala, served by okay-mlx.
3. **Data from verifiers, not people**: tasks generated from symdex's index for any project — every public member:
   "write f using X.m", its check f against X.m on generated inputs (`equivalent`'s generators). The model's own
   attempts kept when the compiler and the check accept (rejection sampling); its repairs (error → fix) kept as
   trajectories; a bigger model (Qwen3.6-35B through the gateway) as the teacher where the 4B fails. Train on part of
   one project's members, evaluate on held-out members and on OTHER projects (okay-datalog, okay-guard): a habit
   learned, not an API memorised.
4. **Compiled modules** (inlay proper), once 2 works: the symbol table as a compiled lookup block (the DSL), its
   output entering the model's residual through a trained adapter — the knowledge exact, its use learned.

## The measure

SymbolsBench's behaviour checks (the 4B base: 7 of 10 at best), more tasks generated per (3) with a held-out split,
other projects for transfer; and no regression elsewhere (a fixed set of general Scala tasks, before and after).

## Stages

1. Steering: hidden states at a layer (a hook), the direction from contrasting pairs, decoding with it; SymbolsBench.
2. Autograd: `value_and_grad` in mlx-scala (JVM, then Native); a toy (a linear layer learns a line) and one step on
   Qwen3-4B that lowers the loss.
3. LoRA on Qwen3 + AdamW + the adapter's save and load.
4. The task generator and the verified data (rejection sampling, repairs, the teacher).
5. Train, measure (held-out, transfer, regression).
6. The compiled module with a trained adapter.
