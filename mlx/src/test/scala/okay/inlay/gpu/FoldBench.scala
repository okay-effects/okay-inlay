package okay.inlay.gpu

import _root_.mlx.{Mlx, Scope}
import _root_.mlx.Mlx.*
import _root_.mlx.llm.{Models, Qwen3, Tokenizer}
import java.nio.file.{Files, Path}

/**
 * DO SMALL QWEN3 MODELS FOLD (specs/fold.md): the untrained fold of arXiv 2611.18113 measured on a model from the
 * HuggingFace cache — weights (every adjacent pair, and pairs half the depth apart), activations on bench/fold's
 * sample (residual updates' cosine, each pair folded), the whole model folded at N = 2.
 *
 * Opt-in: FOLD=mlx-community/Qwen3-0.6B-4bit FOLD_OUT=<dir> — one GPU job; tables as .tsv in FOLD_OUT.
 */
class FoldBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "min")
  private val ranks = Seq(16, 32, 64, 256)
  private val (over, iters) = (32, 4)
  private def stamp = java.time.LocalTime.now().withNano(0).toString
  private def say(s: String): Unit = { println(s"$stamp $s"); Console.flush() }
  private def e(x: Double) = f"$x%.5g"

  test("the untrained fold of a Qwen3"):
    assume(sys.env.contains("FOLD"), "FOLD=<repo> to run")
    val repo = sys.env("FOLD")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val out = Path.of(sys.env.getOrElse("FOLD_OUT", "target/fold"))
    Files.createDirectories(out)
    val tag = repo.split('/').last
    def table(name: String, header: String, rows: Seq[String]): Unit =
      Files.writeString(out.resolve(s"$tag-$name.tsv"), (header +: rows).mkString("", "\n", "\n"))
    val limit = sys.env.get("FOLD_TOKENS").map(_.toInt).getOrElse(1024)
    val tok = Tokenizer.fromFile(dir.get.resolve("tokenizer.json"))
    val names = Vector("en", "ru", "code")
    val texts = names.map(n => tok.encode(Files.readString(Path.of(s"../bench/fold/sample-$n.txt"))).take(limit))
    val file = dir.get.resolve("model.safetensors").toRealPath()
    say(s"$repo: ${file} ${Files.size(file)} bytes; tokens ${names.zip(texts.map(_.length)).mkString(" ")}; " +
      s"memory free ${Mlx.memoryFreePercent}%")
    var failed = Vector.empty[String]

    // the float32 pass against mlx-scala's own (4-bit products, the file's float type) on the first text
    val theirs =
      val q3 = Qwen3.load(dir.get, mapped = false)
      val (s, st) = (new Scope, q3.newState())
      try Fold.nllOf(q3.logitsAll(ints(texts(0), 1, texts(0).length)(using s), st)(using s), texts(0))(using s)
      finally { s.close(); st.close(); q3.close() }

    val f = Fold.load(dir.get)
    val c = f.config
    val layers = c.layers
    say(s"layers $layers hidden ${c.hidden} heads ${c.heads}/${c.kvHeads} x ${c.headDim} bits ${c.bits} " +
      s"group ${c.groupSize}")
    val ms = FoldMeasures(f, texts)
    val (ppl, per) = ms.perplexity()
    val ours =
      val s = new Scope
      try f.nll(ms.hs(0).last, texts(0))(using s) finally s.close()
    val (mine, lib) = (ours.sum / ours.length, theirs.sum / theirs.length)
    say(s"CHECK the pass: mean NLL on ${names(0)} ${e(mine)} here, ${e(lib)} by mlx-scala's Qwen3")
    if math.abs(mine - lib) > 0.02 * lib then failed :+= s"the pass: $mine against $lib"
    say(s"perplexity, original: ${e(ppl)} (${names.zip(per.map(e)).mkString(" ")})")

    // residual updates: adjacent layers, and layers half the depth apart
    val half = layers / 2
    val adjacent = (0 until layers - 1).map(a => (a, a + 1))
    val apart = (0 until half).map(a => (a, a + half))
    val cosAdj = adjacent.map((a, b) => ms.cosine(a, b))
    val cosApart = apart.map((a, b) => ms.cosine(a, b))
    table("cos", "kind\ta\tb\tcos", adjacent.zip(cosAdj).map((p, x) => s"adjacent\t${p._1}\t${p._2}\t${e(x)}") ++
      apart.zip(cosApart).map((p, x) => s"apart\t${p._1}\t${p._2}\t${e(x)}"))
    val evens = adjacent.zip(cosAdj).filter(_._1._1 % 2 == 0).map(_._2)
    say(s"update cosine: adjacent, all ${e(cosAdj.sum / cosAdj.size)}; adjacent (2j, 2j+1) " +
      s"${e(evens.sum / evens.size)}; (l, l + $half) ${e(cosApart.sum / cosApart.size)}")

    // every pair: the weights, and for adjacent pairs the fold of the pair
    val kept = new Scope
    var bases = Map.empty[Int, Map[String, Fold.Basis]]
    val (weights, folds) = (Vector.newBuilder[String], Vector.newBuilder[String])
    var defect = 0.0
    for (kind, pairs) <- Seq("adjacent" -> adjacent, "apart" -> apart); (a, b) <- pairs do
      val s = new Scope
      try
        val rows = f.pair(a, b, ranks, over, iters)(using s)
        for (r, _) <- rows do
          defect = math.max(defect, r.defect)
          weights += (Seq(kind, a, b, r.map, r.small, e(r.d2), e(r.wb2), e(r.cos), e(r.noise / r.d2),
            e(r.d2 / r.wb2)) ++ r.rel.map(e) ++ r.rel.map(x => e(x * r.d2 / r.wb2))).mkString("\t")
        if kind == "adjacent" then
          val bs = rows.map((r, basis) => r.map -> basis).toMap
          val fold = ms.pairFold(a, b, bs, 0 +: ranks)
          for p <- fold do folds += s"$a\t$b\t${p.how}\t${e(p.nmse)}\t${e(p.relU)}"
          say(s"pair ($a, $b): relD64 ${rows.map((r, _) => s"${r.map} ${e(r.rel(2))}").mkString(" ")}; " +
            s"NMSE ${fold.map(p => s"${p.how} ${e(p.nmse)}").mkString(" ")}")
          if a % 2 == 0 then
            bs.values.foreach(_.u.moveTo(kept))
            bases += a -> bs
        else say(s"apart ($a, $b): relD64 ${rows.map((r, _) => s"${r.map} ${e(r.rel(2))}").mkString(" ")}")
      finally s.close()
    table("weights", "kind\ta\tb\tmap\tsmall\td2\twb2\tcosW\tnoiseShare\trelW0\t" +
      ranks.map(r => s"relD$r").mkString("\t") + "\t" + ranks.map(r => s"relW$r").mkString("\t"), weights.result())
    table("pairs", "a\tb\thow\tnmse\trelU", folds.result())
    say(s"CHECK the bases: the largest distance from orthonormal ${e(defect)}")
    if defect > 1e-2 then failed :+= s"a basis not orthonormal: $defect"

    // the truncated SVD converged: the same shares with three times the rounds
    val s = new Scope
    try
      given Scope = s
      var worst = 0.0
      for a <- Seq(0, half, layers - 2); m <- Seq("q", "down") do
        val d = subtract(f.weight(a + 1, m), f.weight(a, m))
        val (b1, b3) = (Fold.basis(d, ranks.max, over, iters, 5), Fold.basis(d, ranks.max, over, 3 * iters, 6))
        val gap = ranks.map(r => math.abs(b1.rel(r) - b3.rel(r))).max
        worst = math.max(worst, gap)
        say(s"CHECK the SVD ($a, ${a + 1}) $m: relD ${ranks.map(r => e(b1.rel(r))).mkString(" ")} at $iters rounds, " +
          s"${ranks.map(r => e(b3.rel(r))).mkString(" ")} at ${3 * iters}")
      if worst > 5e-3 then failed :+= s"the SVD not converged: $worst"
    finally s.close()

    // the whole model folded at N = 2: every pair (2j, 2j + 1), and all but the first three pairs and the last
    val middle = bases.filter((a, _) => a >= 6 && a < layers - 2)
    val runs = for (name, bs) <- Seq("all" -> bases, "middle" -> middle); r <- 0 +: ranks yield
      val (p, each) = ms.perplexity(ms.foldedSource(bs, r))
      say(s"perplexity, folded $name (${bs.size} pairs) rank $r: ${e(p)} (${names.zip(each.map(e)).mkString(" ")})")
      s"$name\t${bs.size}\t$r\t${e(p)}\t${each.map(e).mkString("\t")}"
    table("ppl", "fold\tpairs\trank\tall\t" + names.mkString("\t"),
      s"original\t0\t-\t${e(ppl)}\t${per.map(e).mkString("\t")}" +: runs)
    kept.close(); ms.close(); f.close()
    say(s"done; peak memory ${Mlx.peakMemory >> 20} MB")
    assert(failed.isEmpty, failed.mkString("; "))
