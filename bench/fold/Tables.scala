//> using scala 3.3.8
// specs/fold.md's tables from FoldBench's .tsv files (bench/fold/results): scala-cli run bench/fold/Tables.scala
import java.nio.file.{Files, Path}

def read(p: Path): Vector[Map[String, String]] =
  val lines = Files.readAllLines(p).toArray(Array.empty[String]).toVector.filter(_.nonEmpty).map(_.split("\t", -1))
  lines.tail.map(r => lines.head.zip(r).toMap)

def f(x: Double): String =
  if x.isNaN then "–" else if x == 0 then "0" else if math.abs(x) >= 100 then f"$x%.0f"
  else if math.abs(x) >= 10 then f"$x%.1f" else if math.abs(x) >= 1 then f"$x%.2f"
  else if math.abs(x) >= 0.1 then f"$x%.3f" else if math.abs(x) >= 0.01 then f"$x%.4f" else f"$x%.2g"
def mean(xs: Seq[Double]): Double = if xs.isEmpty then Double.NaN else xs.sum / xs.size
def row(cells: Seq[String]): String = cells.mkString("| ", " | ", " |")

@main def tables(args: String*): Unit =
  val dir = Path.of(args.headOption.getOrElse("bench/fold/results"))
  val ranks = Seq(16, 32, 64, 256)
  val maps = Seq("q", "k", "v", "o", "gate", "up", "down")
  val tags = Seq("Qwen3-0.6B-4bit", "Qwen3-1.7B-4bit", "Qwen3-4B-4bit").filter(t =>
    Files.exists(dir.resolve(s"$t-weights.tsv")))
  for tag <- tags do
    val weights = read(dir.resolve(s"$tag-weights.tsv"))
    val pairs = read(dir.resolve(s"$tag-pairs.tsv"))
    val cos = read(dir.resolve(s"$tag-cos.tsv"))
    val ppl = read(dir.resolve(s"$tag-ppl.tsv"))
    def d(r: Map[String, String], k: String) = r(k).toDouble
    /** over a pair's seven maps: the share of the whole difference's energy a rank leaves out */
    def left(rows: Seq[Map[String, String]], rank: Int) =
      rows.map(r => d(r, s"relD$rank") * d(r, "d2")).sum / rows.map(d(_, "d2")).sum
    def ofAll(rows: Seq[Map[String, String]], k: String) = rows.map(d(_, k)).sum
    val adjacent = weights.filter(_("kind") == "adjacent").groupBy(r => r("a").toInt).toVector.sortBy(_._1)
    val apart = weights.filter(_("kind") == "apart").groupBy(r => r("a").toInt).toVector.sortBy(_._1)
    val layers = adjacent.size + 1

    println(s"\n### $tag ($layers layers)\n")
    println("Per adjacent pair. Weights, the seven maps pooled by energy (`cosW`: their plain mean): `cosW` the")
    println("maps' cosine,")
    println("`D/W` = ‖D‖²/‖W_b‖², `q-noise` the quantisation's expected share of ‖D‖², `left r` the share of ‖D‖² a")
    println("rank leaves out. Activations: `cosU` the updates' cosine, then the pair's output NMSE: layer b dropped,")
    println("the fold at rank 0–256; `relU 64` the rank-64 fold's error over the energy of layer b's own update.\n")
    println(row(Seq("pair", "cosW", "D/W", "q-noise", "left 16", "left 64", "left 256", "cosU", "drop", "r 0", "r 16",
      "r 32", "r 64", "r 256", "relU 64")))
    println(row(Seq.fill(15)("---")))
    for (a, rows) <- adjacent do
      val wb2 = rows.map(r => d(r, "wb2")).sum
      val cosW = mean(rows.map(d(_, "cosW")))
      val ps = pairs.filter(_("a").toInt == a).map(r => r("how") -> r).toMap
      val c = cos.find(r => r("kind") == "adjacent" && r("a").toInt == a).map(d(_, "cos")).getOrElse(Double.NaN)
      println(row(Seq(s"$a, ${a + 1}", f(cosW), f(ofAll(rows, "d2") / wb2),
        f(rows.map(r => d(r, "noiseShare") * d(r, "d2")).sum / ofAll(rows, "d2")), f(left(rows, 16)), f(left(rows, 64)),
        f(left(rows, 256)), f(c), f(d(ps("skip"), "nmse"))) ++ Seq("0", "16", "32", "64", "256").map(h =>
        f(d(ps(h), "nmse"))) :+ f(d(ps("64"), "relU"))))

    println(s"\nBy map, the mean over pairs: adjacent (l, l + 1) against apart (l, l + ${layers / 2}).\n")
    println(row(Seq("map", "small side", "cosW adj", "cosW apart", "D/W adj", "D/W apart", "q-noise adj") ++
      ranks.map(r => s"left $r adj") ++ ranks.map(r => s"left $r apart")))
    println(row(Seq.fill(7 + 2 * ranks.size)("---")))
    for m <- maps do
      val (ad, ap) = (weights.filter(r => r("kind") == "adjacent" && r("map") == m),
        weights.filter(r => r("kind") == "apart" && r("map") == m))
      println(row(Seq(m, ad.head("small"), f(mean(ad.map(d(_, "cosW")))), f(mean(ap.map(d(_, "cosW")))),
        f(mean(ad.map(d(_, "relW0")))), f(mean(ap.map(d(_, "relW0")))), f(mean(ad.map(d(_, "noiseShare"))))) ++
        ranks.map(r => f(mean(ad.map(d(_, s"relD$r"))))) ++ ranks.map(r => f(mean(ap.map(d(_, s"relD$r")))))))

    val adj = cos.filter(_("kind") == "adjacent")
    println(s"\nUpdates' cosine: adjacent, all pairs ${f(mean(adj.map(d(_, "cos"))))}; adjacent (2j, 2j + 1) " +
      s"${f(mean(adj.filter(_("a").toInt % 2 == 0).map(d(_, "cos"))))}; apart (l, l + ${layers / 2}) " +
      s"${f(mean(cos.filter(_("kind") == "apart").map(d(_, "cos"))))}; apart, pair by pair: " +
      cos.filter(_("kind") == "apart").map(r => f(d(r, "cos"))).mkString(" ") + ".")

    println("\nThe whole model folded at N = 2, perplexity on the sample (all, en, ru, code):\n")
    println(row(Seq("fold", "pairs folded", "rank", "all", "en", "ru", "code")))
    println(row(Seq.fill(7)("---")))
    for r <- ppl do
      println(row(Seq(r("fold"), r("pairs"), r("rank")) ++ Seq("all", "en", "ru", "code").map(k => f(d(r, k)))))
