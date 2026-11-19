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

## 5. The run (done 2026-11-19, §6)

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

Run 2026-11-19, 06:02–06:16, the three 4-bit files, one after another under the lock. The .tsv files and every
table (per pair, per map, the updates' cosines, the folded model's perplexity) are in `bench/fold/results/`
(`tables.md` is `Tables.scala`'s output). Each run ended with exit 1 on one check only: the SVD's shares at 12
rounds differ from those at 4 by 0.0077, 0.0090 and 0.0087, over the 0.005 allowed. So every `left r` below is
known to about ±0.01; nothing said here rests on less than ten times that. The pass and the bases passed.

**No size folds untrained. The fold at its start is worse than removing the layer, on all three.**

| model | cosW, adjacent | D/W | left 64 (range over pairs) | left 256 | relU 64: min / median / max | r 32 beats drop |
| --- | --- | --- | --- | --- | --- | --- |
| 0.6B, 28 layers | ≤ 0.0025 | 1.90–2.08 | 0.75–0.80 | 0.39–0.47 | 1.91 / 2.51 / 7.32 | 0 of 27 pairs |
| 1.7B, 28 layers | ≤ 0.0015 | 1.94–2.05 | 0.85–0.89 | 0.61–0.67 | 1.74 / 2.55 / 20.1 | 0 of 27 pairs |
| 4B, 36 layers | ≤ 0.0040 | 1.77–2.39 | 0.84–0.91 | 0.62–0.74 | 1.01 / 2.29 / 7.87 | 0 of 35 pairs |

- **The weights of adjacent layers are unrelated as matrices.** `cosW` is zero to three places and `D/W` is 2 —
  what two independent matrices of equal norm give (‖W_b − W_a‖² = ‖W_a‖² + ‖W_b‖²). Adjacent pairs are no closer
  than pairs half the depth apart (per-map tables: `left r` adjacent and apart differ by 0.01–0.03).
- **The difference is not low-rank.** Rank 64 keeps 10–25% of ‖D‖², rank 256 keeps 26–61%. Quantisation is not the
  reason: its expected share of ‖D‖² is 0.8–0.9%.
- **The updates do not agree either.** The mean cosine of two adjacent layers' updates at a token is 0.05 (0.6B),
  0.08 (1.7B), 0.03 (4B); the paper reports that this cosine becomes high for tied layers after training.
- **One pair.** `relU 64` is above 1 for every pair of every model: the folded layer's error is 1.7–20 times the
  energy of the update it replaces. The output NMSE with the layer dropped is lower than with the fold at rank 32
  for every pair, and at rank 256 for all but one pair of 4B.
- **The whole model at N = 2.** Perplexity goes from 17.9 / 11.9 / 9.40 to 10⁵–10⁸ at every rank, with all pairs
  folded and with the middle pairs only.
- **By size.** A rank is a larger share of a small matrix, so 0.6B leaves less at equal rank (left 256: 0.39–0.47
  against 0.62–0.74 for 4B); that is the share of the rank, not redundancy — `relU` is the same on all three.

**The paper's Table 9 is not reproduced.** For 4B at rank 32 on the pairs (6,7)…(20,21) it gives NMSE
0.008–0.018; here 0.49, 0.57, 0.43, 0.42, 0.75, 0.11, 0.10, 0.12, and the layer dropped gives 0.21, 0.24, 0.19,
0.17, 0.14, 0.026, 0.042, 0.047. The construction is the paper's as it describes it (read again 2026-11-19: the
shared weight is the first layer's, the correction the truncated SVD of the difference, the pair's input the
teacher's hidden state, no fitting). What differs or is unknown: the paper does not say what the variance is
taken over (here position 0 of each text is left out because its norm would set the variance alone — with it in,
every NMSE falls by a large factor, and that alone could be the gap; NOT measured), its text, and its weights
(full precision there, a 4-bit file here). `relU`, which no choice of variance changes, is the number to trust.

**Read by §7.** (a) okay-forge: the weights of 0.6B and 1.7B are not redundant in this way; a folded student
would be made by the distillation alone (the paper: 22 billion tokens), with nothing to start from, and would
still cost the teacher's time and memory per token. (b) No "interchangeable middle" is visible before training:
no stretch of depth has `relU` under 1. Not done: a full-precision control, and the variance with position 0 in.

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
