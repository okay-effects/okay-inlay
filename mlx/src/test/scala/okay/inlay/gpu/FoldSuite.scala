package okay.inlay.gpu

import _root_.mlx.{Arr, Mlx, Scope}
import _root_.mlx.Mlx.*
import _root_.mlx.llm.Qwen3
import java.nio.file.{Files, Path}

/** THE FOLD'S MEASURES ON KNOWN ANSWERS (specs/fold.md §4): the eigen-solver and the truncated SVD against matrices
 * built from their spectrum; the float32 pass against mlx-scala's `Qwen3` on a tiny random 4-bit model; the fold at
 * full rank equal to the original, a pair of identical layers folding at rank 0. On the CPU: no GPU job */
class FoldSuite extends munit.FunSuite:
  private val rnd = java.util.Random(11)
  private def gauss(n: Int, scale: Double = 1.0) = Array.fill(n)(rnd.nextGaussian() * scale)

  /** p orthonormal columns of length n (Gram–Schmidt twice, in doubles), row-major [n, p] */
  private def orthonormal(n: Int, p: Int): Array[Double] =
    val q = gauss(n * p)
    for j <- 0 until p; _ <- 0 until 2 do
      for i <- 0 until j do
        val d = (0 until n).map(r => q(r * p + i) * q(r * p + j)).sum
        for r <- 0 until n do q(r * p + j) -= d * q(r * p + i)
      val norm = math.sqrt((0 until n).map(r => q(r * p + j) * q(r * p + j)).sum)
      for r <- 0 until n do q(r * p + j) /= norm
    q

  test("Jacobi: a symmetric matrix's eigenvalues and vectors"):
    val (two, _) = Spectrum.eigen(Array(2.0, 1.0, 1.0, 2.0), 2)
    assert(math.abs(two(0) - 3) < 1e-12 && math.abs(two(1) - 1) < 1e-12, two.toSeq)
    val k = 40
    val (u, known) = (orthonormal(k, k), Array.tabulate(k)(j => 5.0 * math.pow(0.8, j)))
    val a = Array.tabulate(k * k)(x => (0 until k).map(j => u((x / k) * k + j) * known(j) * u((x % k) * k + j)).sum)
    val (lam, v) = Spectrum.eigen(a, k)
    for j <- 0 until k do assert(math.abs(lam(j) - known(j)) < 1e-10, s"$j: ${lam(j)} against ${known(j)}")
    // A v = λ v, column by column
    for j <- 0 until k; r <- 0 until k do
      val av = (0 until k).map(c => a(r * k + c) * v(c * k + j)).sum
      assert(math.abs(av - lam(j) * v(r * k + j)) < 1e-9)

  test("the truncated SVD: the share a rank leaves out, against a known spectrum"):
    Mlx.init()
    Mlx.onCpu {
      val s = new Scope
      try
        given Scope = s
        for (m, n) <- Seq((48, 80), (80, 48)) do
          val p = math.min(m, n)
          val sv = Array.tabulate(p)(j => math.pow(0.9, j))
          val (u, v) = (orthonormal(m, p), orthonormal(n, p))
          val d = floats(Array.tabulate(m * n)(x =>
            (0 until p).map(j => u((x / n) * p + j) * sv(j) * v((x % n) * p + j)).sum.toFloat), m, n)
          val all = sv.map(x => x * x).sum
          def left(r: Int) = sv.drop(r).map(x => x * x).sum / all
          // k = 24 of 48: an approximation; k = 48: exact
          for (over, tol) <- Seq((8, 2e-3), (32, 1e-5)) do
            val b = Fold.basis(d, rmax = 16, over, iters = 6, seed = 3)
            assert(b.defect < 1e-4, s"not orthonormal: ${b.defect}")
            assertEquals(b.rows, m <= n)
            assert(math.abs(b.total - all) < 1e-4 * all)
            for r <- Seq(1, 4, 16) do
              assert(math.abs(b.rel(r) - left(r)) < tol, s"$m x $n over $over rank $r: ${b.rel(r)} against ${left(r)}")
              // the rank-r part itself: its error is the share left out
              val e = subtract(d, Fold.lowRank(d, b, r))
              assert(math.abs(Fold.total(e) / all - left(r)) < tol, s"the rank-$r part of $m x $n")
            assert(Fold.total(Fold.lowRank(d, b, 0)) == 0.0)
      finally s.close()
    }

  // A TINY QWEN3: 6 layers, hidden 64, 4 heads on 2 key heads, 4-bit — layer 3 a copy of layer 2
  private val (hidden, heads, kvHeads, headDim, mlp, layers, vocab) = (64, 4, 2, 16, 128, 6, 96)
  private def tiny(dir: Path): Unit =
    val s = new Scope
    try
      given Scope = s
      def q(name: String, out: Int, in: Int, scale: Double): Seq[(String, Arr)] =
        val (w, sc, b) = quantize(floats(gauss(out * in, scale / math.sqrt(in)).map(_.toFloat), out, in), 64, 4)
        Seq(s"$name.weight" -> w, s"$name.scales" -> sc, s"$name.biases" -> b)
      def norm(name: String, n: Int) = s"$name.weight" -> floats(Array.fill(n)(1f + rnd.nextFloat() * 0.5f), n)
      def layer(l: Int): Seq[(String, Arr)] =
        val p = s"model.layers.$l"
        q(s"$p.self_attn.q_proj", heads * headDim, hidden, 1) ++ q(s"$p.self_attn.k_proj", kvHeads * headDim, hidden, 1)
          ++ q(s"$p.self_attn.v_proj", kvHeads * headDim, hidden, 1) ++ q(s"$p.self_attn.o_proj", hidden,
            heads * headDim, 0.5) ++ q(s"$p.mlp.gate_proj", mlp, hidden, 1) ++ q(s"$p.mlp.up_proj", mlp, hidden, 1)
          ++ q(s"$p.mlp.down_proj", hidden, mlp, 0.5) ++ Seq(norm(s"$p.input_layernorm", hidden),
            norm(s"$p.post_attention_layernorm", hidden), norm(s"$p.self_attn.q_norm", headDim),
            norm(s"$p.self_attn.k_norm", headDim))
      val ls = Vector.tabulate(layers)(layer)
      val copy = ls(2).map((name, a) => name.replace("layers.2.", "layers.3.") -> a)
      val all = ls.patch(3, Seq(copy), 1).flatten ++ q("model.embed_tokens", vocab, hidden, 8) :+
        norm("model.norm", hidden)
      saveSafetensors(dir.resolve("model.safetensors").toString, all)
      Files.writeString(dir.resolve("config.json"), s"""{"model_type": "qwen3", "hidden_size": $hidden,
        "num_hidden_layers": $layers, "num_attention_heads": $heads, "num_key_value_heads": $kvHeads,
        "head_dim": $headDim, "rope_theta": 1000000, "rms_norm_eps": 1e-06, "eos_token_id": 1,
        "quantization": {"group_size": 64, "bits": 4}}""")
    finally s.close()

  test("a tiny random Qwen3: the pass, the fold at full rank, identical layers"):
    Mlx.init()
    Mlx.onCpu {
      val dir = Files.createTempDirectory("fold-tiny")
      tiny(dir)
      val texts = Vector.fill(2)(Array.fill(24)(rnd.nextInt(vocab)))
      val f = Fold.load(dir)
      val ms = FoldMeasures(f, texts)
      val s = new Scope
      try
        given Scope = s
        // the float32 pass against mlx-scala's Qwen3 over the same file
        val q3 = Qwen3.load(dir, mapped = false)
        for (ids, t) <- texts.zipWithIndex do
          val st = q3.newState()
          val theirs = Fold.nllOf(q3.logitsAll(ints(ids, 1, ids.length), st), ids)
          st.close()
          val ours = f.nll(ms.hs(t).last, ids)
          for i <- ours.indices do assert(math.abs(ours(i) - theirs(i)) < 2e-3, s"position $i: ${ours(i)} ${theirs(i)}")
        q3.close()

        val ranks = Seq(0, 4, 16, 64)
        val pairs = (0 until layers - 1).map(a => a -> f.pair(a, a + 1, ranks.tail, over = 8, iters = 4))
        for (a, rows) <- pairs; (row, _) <- rows do
          assert(row.defect < 1e-3, s"$a ${row.map}: not orthonormal, ${row.defect}")
          assert(row.rel.sliding(2).forall(x => x(1) <= x(0) + 1e-6), s"not monotone: ${row.rel}")
          if a == 2 then
            // layers 2 and 3 are one layer: no difference, cosine 1
            assertEquals(row.d2, 0.0)
            assert(math.abs(row.cos - 1) < 1e-5)
          else
            // every rank of the smaller side: nothing left out; unrelated random layers: cosine near 0
            assert(row.rel.last < 1e-4, s"$a ${row.map} at full rank leaves ${row.rel.last}")
            assert(row.rel.head > 0.3 && math.abs(row.cos) < 0.2, s"$a ${row.map}: ${row.rel} ${row.cos}")
            assert(row.noise > 0 && row.noise < row.d2)

        assert(math.abs(ms.cosine(1, 1) - 1) < 1e-5)
        assert(math.abs(ms.cosine(1, 4)) < 0.9)
        val bases = pairs.map((a, rows) => a -> rows.map((row, b) => row.map -> b).toMap).toMap
        for a <- 0 until layers - 1 do
          val rows = ms.pairFold(a, a + 1, bases(a), ranks)
          assertEquals(rows.map(_.how), Vector("skip", "0", "4", "16", "64"))
          assertEquals(rows.head.relU, 1.0)
          // at full rank the folded pair is the pair
          assert(rows.last.nmse < 1e-7, s"pair $a at full rank: ${rows.last.nmse}")
          if a == 2 then assert(rows(1).nmse < 1e-9, s"identical layers at rank 0: ${rows(1).nmse}")
          else assert(rows(1).nmse > 1e-3 && rows(1).relU > 0.05, s"pair $a at rank 0: ${rows(1)}")

        // the whole model folded: at full rank the original's perplexity, at rank 0 another
        val even = bases.filter(_._1 % 2 == 0)
        val (plain, _) = ms.perplexity()
        val (full, _) = ms.perplexity(ms.foldedSource(even, 64))
        val (zero, _) = ms.perplexity(ms.foldedSource(even, 0))
        assert(math.abs(full / plain - 1) < 1e-3, s"$full against $plain")
        assert(math.abs(zero / plain - 1) > 1e-3, s"rank 0 changed nothing: $zero $plain")
        // only layer 3 folded onto layer 2, its copy: nothing changes at rank 0
        val (same, _) = ms.perplexity(ms.foldedSource(even.filter(_._1 == 2), 0))
        assert(math.abs(same / plain - 1) < 1e-5, s"$same against $plain")
      finally
        s.close(); ms.close(); f.close()
    }
