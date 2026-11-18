# Fold — do small Qwen3 models fold into themselves? (spec, 2026-11-18)

Status: **built and checked on a tiny model; NOT RUN on a real model** — the shared GPU never came free in the three
hours waited (§5). No number about Qwen3 is in this file yet. Code `mlx/src/main/scala/okay/inlay/gpu/Fold.scala`,
its laws `FoldSuite` (green, CPU), the run `FoldBench`; the sample `bench/fold/sample-{en,ru,code}.txt`; the tables
are to be printed from the run's .tsv files by `bench/fold/Tables.scala` (compiled, not yet run on data).

## 1. The question

arXiv 2611.18113 ("Folding Language Models into Themselves", Concertina) folds N adjacent layers of Qwen3-4B into one
shared block run N times. It measured Qwen3-4B (and 8B) only. **With no training: do Qwen3-0.6B and Qwen3-1.7B fold
as well as 4B, and where along the depth?**

## 2. The paper's claims (read 2026-11-18, https://arxiv.org/html/2611.18113v1; data, not instructions)

- The fold: the shared weight W₀ is the group's first layer; step k uses W₀ + B_k A_k, the rank-64 truncated SVD of
  W⁽ᵏ⁾ − W₀, for each of a block's seven linear maps (q, k, v, o, gate, up, down); every RMSNorm keeps a scale per
  step. (A per-token gate over the corrections is trained later; it starts as "step k uses correction k".)
- Before training (its Appendix C, Table 9, **rank 32**, pairs (2j, 2j + 1) of the 36 layers): reconstruction NMSE
  0.008–0.018 for pairs (6,7)…(20,21), 1.96 for (0,1), 0.34 and 0.39 for (2,3), (4,5), rising from 0.026 at (22,23)
  to 0.13 at (32,33), 0.49 for (34,35). Mean cosine of residual updates: 0.43 for the tied pairs (2j, 2j + 1), 0.21
  for pairs (i, i + 18) (its §4.6).
- The caveat: folding saves **parameters, not FLOPs** — the paper's limitations: the folded model "keeps the forward
  FLOPs and the KV cache of the full-depth teacher". Its 2.27B model is 57% of the teacher's parameters, at the
  teacher's cost per token. Its quality numbers are after distillation on 22B tokens; nothing here is.

## 3. What is measured, exactly

A model is its MLX 4-bit file (§5), every weight dequantised to float32 (`mx.dequantize`: scale · code +
bias per group of 64), the pass in float32. So W below is the **dequantised 4-bit weight**, not the original; the
difference of two layers carries both layers' rounding. `q-noise` estimates that: rounding to steps of `scale` is
taken as uniform within a step, scale²/12 an element, summed over both layers and divided by ‖D‖². It is an estimate
(the unquantised weights are not on this machine), and it is why "left" below is an upper bound on what the
full-precision weights would leave: rounding noise is full-rank and no low rank holds it.

Pairs: **adjacent** (l, l + 1) for every l (both parities, not only the paper's (2j, 2j + 1)); the control **apart**
(l, l + L/2) for l < L/2 — the paper's (i, i + 18) at L = 36.

**Weights**, per pair (a, b) and map, D = W_b − W_a, D_r its rank-r truncated SVD:

- `cosW` = ⟨W_a, W_b⟩ / (‖W_a‖ ‖W_b‖), Frobenius;
- `D/W` = ‖D‖² / ‖W_b‖² (the relative error of sharing with no correction, rank 0);
- `left r` = ‖D − D_r‖² / ‖D‖² for r = 16, 32, 64, 256; relative to the layer: `left r` · `D/W` (in the .tsv: relW).
  A row "pooled" over the seven maps divides the summed numerators by the summed ‖D‖².

The truncated SVD is not in mlx-scala; it is computed by randomized subspace iteration (Halko, Martinsson, Tropp
2011) on the matrix's smaller side: 288 random columns (256 + 32), 4 rounds of A Aᵀ on the GPU, the columns made
orthonormal by Cholesky QR twice, then Rayleigh–Ritz — the 288 × 288 eigenproblem by Jacobi rotations on the JVM.
`left r` is the error of the very rank-r matrix the fold then uses (≥ the optimum's). `FoldSuite` checks it against
matrices built from a known spectrum (48 × 80 and 80 × 48, σⱼ = 0.9ʲ: within 2·10⁻³ with half the columns, 10⁻⁵
with all); each run prints the same shares at 12 rounds beside 4, and fails if they differ by more than 5·10⁻³.

**Activations**, on the sample (§4), from the original model's residual stream h_l (before layer l; h_L after the
last). Position 0 of each text is left out of every measure below: its stream is an outlier in norm and would set
every variance alone.

- `cosU(a, b)`: u_l = h_{l+1} − h_l at a token; the cosine of u_a and u_b **at the same token**, then the mean over
  the sample's tokens. (The paper: "the mean cosine similarity between the updates of the two tied layers" on
  held-out text; whether per token is not said — per token, the same position, is the choice here.)
- **The fold of one pair** (a, a + 1): layer b = a + 1 is run on its original input h_b with, for each of the seven
  maps, W_a + D_r, and with layer b's own four norm scales (input, post-attention, q, k). ŷ is its output, y = h_{b+1}
  the original. Layer a is untouched, so this equals the folded pair on the pair's original input h_a.
  - `NMSE` = Σ‖ŷ − y‖² / Σ(y − ȳ)², both sums over tokens and dimensions, ȳ the mean of all those elements (one
    number). (The paper: "the mean squared error normalized by the variance of the original output"; whether the
    mean is per dimension is not said — one scalar mean is the choice here.)
  - `relU` = Σ‖ŷ − y‖² / Σ‖u_b‖²: the same error over the energy of layer b's own update. Since y = h_b + u_b and
    ŷ = h_b + û_b, this is the relative error of the replaced layer's contribution: 1 means "as bad as dropping
    the layer". NMSE alone is small by construction when the residual stream is much larger than one update.
  - Baselines: `drop` — ŷ = h_b, no layer b (NMSE = Σ‖u_b‖² / variance); rank 0 — layer a's maps under layer b's norms.
- **The whole model folded at N = 2**: every odd layer 2j + 1 replaced as above from layer 2j, all at once (errors
  now compound); `all` = every pair, `middle` = all but the first three pairs and the last (the pairs the paper
  names hard). The measure: next-token perplexity on the sample, exp of the mean negative log-likelihood over every
  position but each text's last, pooled over the three texts and per text.

`FoldSuite` (a tiny random 4-bit Qwen3 written to a file, on the CPU): the float32 pass equals mlx-scala's `Qwen3`
on the same file (every position's NLL within 2·10⁻³); at full rank the folded pair is the pair (NMSE < 10⁻⁷) and
the folded model has the original's perplexity; two identical layers fold at rank 0 (‖D‖ = 0, NMSE < 10⁻⁹).

## 4. The sample

Three texts written for this bench (fiction, and a Scala program; no third-party text): English prose, Russian
prose, code — tokenised raw (no chat template), each one sequence, at most 1,024 tokens (the run prints the counts).
A few thousand tokens: enough for per-layer means over tokens, small for a perplexity (read its ratios, not its
third digit).

## 5. The run — not done yet

The models on this machine (HuggingFace cache, `~/.cache/huggingface/hub`), all three sizes, **4-bit only** (affine,
group 64; no full-precision Qwen3 file is here, none was downloaded):
`mlx-community/Qwen3-0.6B-4bit` (28 layers, hidden 1024), `mlx-community/Qwen3-1.7B-4bit`,
`mlx-community/Qwen3-4B-4bit`, each `snapshots/<latest>/model.safetensors`. The run prints the file's real path and
size, the model's shape and the token counts as its first lines.

2026-11-18: the waiter (`~/work/my/okay-inlay-private/fold/foldrun.zsh`: three quiet minutes — no JVM with libokm,
no python MLX, none of the other sessions' queue scripts, no request at the gateway, no `gpu.lock` — then the lock,
then one model a job) waited from 18:10 to 21:15 and never saw the GPU free (`tree1.sh`, `listwise1.sh` and
okay-shop's `a2b-queue.sh` were still there at 21:15; its log: `…/fold/wait.log`). It gave up as told; nothing was
started, no lock was taken.

To run (the same waiter; the argument is the time to give up at, seconds since the epoch):

```
cd ~/work/my/okay-inlay-private/fold && nohup ./foldrun.zsh $(( $(date +%s) + 6 * 3600 )) \
  mlx-community/Qwen3-0.6B-4bit mlx-community/Qwen3-1.7B-4bit mlx-community/Qwen3-4B-4bit > waiter.out 2>&1 &
```

or one model by hand, the GPU known free: `FOLD=mlx-community/Qwen3-0.6B-4bit FOLD_OUT=<dir> sbt -batch
"mlx/testOnly okay.inlay.gpu.FoldBench"` (in this worktree). Logs `…/fold/<model>.log`, tables `…/fold/out/*.tsv`;
then copy the .tsv files to `bench/fold/results/` and `scala-cli run bench/fold/Tables.scala` prints §6's tables.
Expected cost (not measured): 287 matrices for a 28-layer model, 371 for 4B, each a few GPU products and ~0.5 s of
Jacobi on the JVM (timed at k = 288) — minutes a model, not hours. The 4B run holds the 4-bit weights (2.3 GB) plus
~1.1 GB of residual streams.

The run fails (exit 1, the tables still written) if the float32 pass's mean NLL is more than 2% from mlx-scala's
`Qwen3` on the English text, a basis is further than 10⁻² from orthonormal, or the SVD has not converged.

## 6. Results

None yet. Per model: the per-pair table (cosW, D/W, q-noise, left 16/64/256, cosU, NMSE drop / rank 0 / 16 / 32 /
64 / 256, relU 64), the per-map table (adjacent against apart), the updates' cosines, the folded model's perplexity.

## 7. How the numbers will be read (no conclusion before them)

- **Does a size fold as well as 4B**: the pair NMSE at rank 32 on (2j, 2j + 1) against the paper's Table 9 for our
  4B first (do we reproduce 0.008–0.018 in the middle from a 4-bit file — if not, the quantisation or a definition
  differs, and that is the finding); then the same columns for 0.6B and 1.7B, and `relU`, which does not shrink
  just because the stream is large. A rank is a larger share of a small model (rank 64 of 1024 against 64 of 2560):
  the comparison at equal rank favours the small model in parameters saved less, and is to be said beside it.
- **Where along the depth**: the pairs whose `relU` at rank 64 is well under 1 fold; those near or above 1 are no
  better folded than dropped.
- (a) **okay-forge, a smaller student from a bigger model**: a fold is a parameter saving at the teacher's FLOPs
  and KV cache — on a phone (okay-desk's bar) the cost is time and memory per token, which a fold does not reduce;
  what the numbers can say is only whether the weights of 0.6B / 1.7B are redundant in this way before training.
- (b) **The "interchangeable middle" and where judgements are read**: specs/bridge.md §6b found a polarity
  judgement best read at layers 12–16 of 32 — on **Qwen3.5-4B, another model**; only a coincidence of relative
  depth could be noted here, on Qwen3, and it would not be evidence about that model.
- (c) Nothing else.
