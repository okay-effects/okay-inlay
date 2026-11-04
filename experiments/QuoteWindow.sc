//> using scala 3.7.3
//> using repository ivy2Local
//> using repository https://okay-effects.github.io/okay-simple/maven
//> using dep io.github.sergey-scherbina::mlx-scala-llm:0.1.0
// THE QUOTE'S WINDOW (specs/dsl.md v1): weights of a fixed depth narrow a quote by its last k copied tokens (QuoteK);
// the runtime Quote by the whole copy. On real texts, tokenized as Qwen does, how often does the window allow a token
// the whole copy does not (a splice of two spans sharing k tokens)? Copies of up to 64 tokens from 400 starts each.
var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }

val home = sys.props("user.home")
val tokJson = java.nio.file.Files.list(java.nio.file.Path.of(home,
  ".cache/huggingface/hub/models--mlx-community--Qwen3.6-35B-A3B-4bit/snapshots")).findFirst.get.resolve("tokenizer.json")
val tok = mlx.llm.Tokenizer.fromJson(okay.effects.json.Json.fromText(java.nio.file.Files.readString(tokJson)))

def sh(cmd: String*): String = String(ProcessBuilder(cmd*).redirectErrorStream(true).start().getInputStream.readAllBytes)
val texts = Vector(
  "a system log" -> sh("sh", "-c", "cat /var/log/install.log 2>/dev/null | tail -c 20000"),
  "git log --stat" -> sh("git", "-C", s"$home/work/my/symdex", "log", "--stat", "-n", "40"),
  "Scala code" -> java.nio.file.Files.readString(java.nio.file.Path.of(s"$home/work/my/symdex/src/main/scala/symdex/Tools.scala")),
  "an agent's journal (JSON)" -> sh("sh", "-c", s"ls -t $home/.okay-agent/journals/*.jsonl | head -1 | xargs cat")
).map((n, t) => n -> t.take(16000)).filter(_._2.length > 2000)

val ks = Seq(4, 8, 16, 32)
val r = scala.util.Random(5)
println(f"${"text"}%-26s ${"tokens"}%7s " + ks.map(k => f"k=$k%-5s").mkString(" ") + "  (steps where the window allows more)")
for (name, text) <- texts do
  val s = tok.encode(text)
  val n = s.length
  // the positions after each window of w tokens: grams(w)(window) = positions p with s[p-w..p-1] == window
  val grams = (1 to ks.max).map(w => w -> (w until n).groupBy(p => s.slice(p - w, p).toSeq)).toMap
  val wider = scala.collection.mutable.Map.from(ks.map(_ -> 0))
  var steps = 0
  for _ <- 1 to 400 do
    val start = r.nextInt(n - 1)
    var cand = (0 until n).toVector // the whole copy's candidates: positions whose token the copy may take next
    var m = 0
    while m < 64 && start + m < n do
      val t = s(start + m)
      cand = cand.filter(p => p < n && s(p) == t).map(_ + 1).filter(_ < n)
      m += 1
      val full = cand.map(s(_)).toSet
      steps += 1
      for k <- ks do
        val w = math.min(m, k)
        val window = grams(w).getOrElse(s.slice(start + m - w, start + m).toSeq, Vector.empty).map(s(_)).toSet
        check(full.subsetOf(window), s"$name k=$k: the window must allow all the whole copy does")
        if window.size > full.size then wider(k) += 1
  println(f"$name%-26s $n%7d " + ks.map(k => f"${100.0 * wider(k) / steps}%5.2f%%").mkString(" "))
if failures > 0 then sys.exit(1) else println("all checks passed")
