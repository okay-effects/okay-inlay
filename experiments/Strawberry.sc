var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// counting a letter over the text itself: characters, then BPE tokens; causal attention + BOS + ladder MLPs, float32
val lambda = 250f
val relu = (x: Float) => math.max(0f, x)

// one causal head at position p: BOS (position 0) always scores lambda; key i scores lambda * sel(i)
// value: 1 on BOS, 0 elsewhere -> the output is 1/(k+1), k = number of selected keys in 1..p
def width(p: Int, sel: Int => Boolean): Float =
  val scores = (0 to p).map(i => if i == 0 then lambda else if sel(i) then lambda else 0f)
  val mx = scores.max
  val e = scores.map(s => math.exp((s - mx).toDouble).toFloat)
  e(0) / e.sum

// the ladder MLP from before: 1/(k+1) -> k, exact for k < maxK
val maxK = 16
val ts = (0 until maxK).map(k => ((1.0 / (k + 1) + 1.0 / (k + 2)) / 2).toFloat)
val steep = 4f * (maxK + 2) * (maxK + 2)
def ladder(y: Float): Int = math.round(ts.map(t => 1f - (relu(steep * (y - t)) - relu(steep * (y - t) - 1))).sum)

// ---- 1. characters: one position per letter
val text = "strawberry"
val chars = '^' +: text.toVector                      // position 0 = BOS
val isR = chars.map(_ == 'r')                         // MLP layer: elementwise
val running = (1 until chars.size).map(p => ladder(width(p, isR)))
println("characters: " + text.zip(running).map((c, n) => s"$c:$n").mkString(" "))

// ---- 2. BPE tokens: the letter count of a token is a column of the embedding (built from the vocabulary)
val tokens = Vector("^", "How", " many", " r", " in", " str", "aw", "berry", "?")
def countIn(t: String, c: Char) = t.count(_ == c)
// embedding: for letter L and j = 1..3, a 0/1 dim "the token has >= j copies of L"; and "starts a word"
def atLeast(i: Int, c: Char, j: Int) = countIn(tokens(i), c) >= j
val wordStart = tokens.map(t => t.startsWith(" ") || t == "How")
// layer 1: word id = number of word starts so far (a width head + a ladder)
val wordId = tokens.indices.map(p => if p == 0 then 0 else ladder(width(p, wordStart)))
// layer 2: for j = 1..3 a head selecting keys of the same word with >= j copies of the letter (the query asks for 'r')
// sum over tokens of c_i = sum over j of #{i : c_i >= j}  (layer cake), three heads in parallel, three ladders
def wordCount(p: Int, c: Char) =
  (1 to 3).map(j => ladder(width(p, i => wordId(i) == wordId(p) && atLeast(i, c, j)))).sum
println("tokens:     " + tokens.indices.drop(1).map(p =>
  s"'${tokens(p)}' word=${wordId(p)} r=${wordCount(p, 'r')}").mkString(" | "))
val perJ = (1 to 3).map(j => (5 to 7).count(i => atLeast(i, 'r', j)))
println(s"at 'berry': #{c>=1}, #{c>=2}, #{c>=3} = ${perJ.mkString(", ")} -> ${perJ.sum}")
// ---- checks
check(running.toList == List(0, 0, 1, 1, 1, 1, 1, 2, 3, 3), "characters: the running count of r")
check(wordCount(tokens.indexOf("berry"), 'r') == 3, "BPE tokens: 3 r in strawberry")
if failures > 0 then sys.exit(1) else println("all checks passed")
