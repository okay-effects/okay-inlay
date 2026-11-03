var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// WORDLE: the reference and a CPU solver (step 1), the hint filter as a transformer — one-hot letters, an MLP that
// counts, a head "allowed words" (step 2), the move mask of Decode.sc built from that head (step 3). float32.
val relu = (x: Float) => math.max(0f, x)
val lambda = 250f

// ---- 1. THE REFERENCE AND THE BENCH
// the dictionary: the 5-letter lowercase words of /usr/share/dict/words (web2), sorted
val words: Vector[String] = scala.io.Source.fromFile("/usr/share/dict/words").getLines()
  .filter(_.matches("[a-z]{5}")).toVector.distinct.sorted
val N = words.size
println(s"dictionary: $N words (/usr/share/dict/words, 5 lowercase letters)")

// the feedback as Wordle gives it: 2 green, 1 yellow, 0 grey; a repeated letter is yellow only as many times as the
// answer has it left after its greens
def feedback(guess: String, answer: String): Vector[Int] =
  val fb = Array.fill(5)(0)
  val left = Array.fill(26)(0)
  for i <- 0 until 5 do if guess(i) == answer(i) then fb(i) = 2 else left(answer(i) - 'a') += 1
  for i <- 0 until 5 if fb(i) == 0 do
    val l = guess(i) - 'a'
    if left(l) > 0 then { fb(i) = 1; left(l) -= 1 }
  fb.toVector
def show(fb: Vector[Int]) = fb.map(Vector("⬜", "🟨", "🟩")(_)).mkString
type Move = (String, Vector[Int])
// THE REFERENCE: w is a candidate iff every move's guess would have given w the same feedback
def consistent(w: String, moves: Seq[Move]) = moves.forall((g, fb) => feedback(g, w) == fb)

// variant B, a CPU solver in hard mode: the candidate with the most common letters, by place and by presence
def solverPick(cands: Vector[String]): String =
  val pos = Array.ofDim[Int](5, 26); val has = Array.fill(26)(0)
  for w <- cands do
    for i <- 0 until 5 do pos(i)(w(i) - 'a') += 1
    for c <- w.distinct do has(c - 'a') += 1
  cands.maxBy(w => (0 until 5).map(i => pos(i)(w(i) - 'a')).sum + w.distinct.map(c => has(c - 'a')).sum)

val rnd = new scala.util.Random(2026)
val answers = Vector.fill(300)(words(rnd.nextInt(N)))

// ---- 2. THE HINT FILTER AS A TRANSFORMER
// a word is a position; its embedding: one-hot letters by place, 5 x 26 = 130 axes
def onehot(w: String): Array[Float] =
  val x = Array.fill(130)(0f); for i <- 0 until 5 do x(i * 26 + w(i) - 'a') = 1f
  x
// MLP layer 1, linear: the count of each letter, c_L = sum over places of x[i, L] (a 130 x 26 matrix of 0/1)
val wCount = Array.tabulate(130, 26)((r, l) => if r % 26 == l then 1f else 0f)
// MLP layer 2, the ladder: ge_j(L) = relu(c - j + 1) - relu(c - j), exactly 1 when c >= j for an integer c
val maxJ = 5
// the features: [x: 130 | ge_1..ge_5 of each letter: 130 | one: 1]
val D = 261
def geAt(j: Int, l: Int) = 130 + (j - 1) * 26 + l
val ONE = 260
def features(w: String): Array[Float] =
  val x = onehot(w)
  val c = Array.tabulate(26)(l => (0 until 130).map(r => x(r) * wCount(r)(l)).sum)
  val f = Array.fill(D)(0f)
  System.arraycopy(x, 0, f, 0, 130)
  for j <- 1 to maxJ; l <- 0 until 26 do f(geAt(j, l)) = relu(c(l) - j + 1) - relu(c(l) - j)
  f(ONE) = 1f
  f
val F: Array[Array[Float]] = words.map(features).toArray

// a hint compiles into literals: a query q over the features, V(w) = q . f(w) = the number of literals w breaks
//   green at i:          1 - x[i, L]
//   yellow or grey at i: x[i, L]                       (the letter is not here)
//   the letter's count:  1 - ge_n           when no grey of L was seen (at least n = its greens and yellows)
//                        (1 - ge_n) + ge_{n+1}  when one was (exactly n; ge_0 = 1, so n = 0 is just ge_1)
def compile(g: String, fb: Vector[Int]): Map[Int, Float] =
  val q = scala.collection.mutable.Map.empty[Int, Float].withDefaultValue(0f)
  def add(k: Int, v: Float) = q(k) = q(k) + v
  for i <- 0 until 5 do
    val at = i * 26 + g(i) - 'a'
    if fb(i) == 2 then { add(ONE, 1f); add(at, -1f) } else add(at, 1f)
  for c <- g.distinct do
    val idx = (0 until 5).filter(g(_) == c)
    val n = idx.count(fb(_) > 0)
    val grey = idx.exists(fb(_) == 0)
    val l = c - 'a'
    if n > 0 then { add(ONE, 1f); add(geAt(n, l), -1f) }
    if grey then add(geAt(n + 1, l), 1f)
  q.filter(_._2 != 0f).toMap
def plus(a: Map[Int, Float], b: Map[Int, Float]) = (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0f) +
  b.getOrElse(k, 0f))).toMap

// the head "allowed words": the query is the sum of the hints' literals; key w scores -lambda * V(w); the softmax is
// uniform on the words that break nothing and exactly 0 elsewhere. (The product skips q's zeros: the same sum.)
def violations(q: Map[Int, Float]): Array[Float] =
  val v = Array.fill(N)(0f)
  for (k, c) <- q; w <- 0 until N do v(w) += c * F(w)(k)
  v
def head(q: Map[Int, Float]): Array[Float] =
  val s = violations(q).map(-lambda * _)
  val mx = s.max
  val e = s.map(x => math.exp((x - mx).toDouble).toFloat)
  val z = e.sum
  e.map(_ / z)
// the gate, AND of the literals and NOT of a violation: relu(1 - V), 1 on an allowed word, 0 on any other
def gate(q: Map[Int, Float]): Array[Float] = violations(q).map(v => relu(1f - v))

// ---- 3. THE MOVE MASK (Decode.sc): a guess written letter by letter; the letters chosen so far are green literals
// of the same head, its value the one-hot of the next letter, the mask is the head's output > 0
def greens(prefix: String): Map[Int, Float] = prefix.indices.foldLeft(Map.empty[Int, Float]) { (q, i) =>
  plus(q, Map(ONE -> 1f, (i * 26 + prefix(i) - 'a') -> -1f)) }
def mask(q: Map[Int, Float], prefix: String): Vector[Boolean] =
  val w = head(plus(q, greens(prefix)))
  val k = prefix.length
  val out = Array.fill(26)(0f)
  for i <- 0 until N if w(i) > 0f do for l <- 0 until 26 do out(l) += w(i) * F(i)(k * 26 + l)
  out.toVector.map(_ > 0f)
// a toy model: it wants the letters common at each place in the whole dictionary, the hints unseen, plus noise
val posFreq = Array.tabulate(5, 26)((i, l) => words.count(_(i) - 'a' == l).toFloat)
def logits(r: scala.util.Random)(k: Int): Vector[Float] =
  Vector.tabulate(26)(l => math.log(posFreq(k)(l) + 1).toFloat + 0.5f * r.nextFloat())
def decode(lg: Int => Vector[Float], allowed: String => Vector[Boolean]): String =
  (0 until 5).foldLeft("") { (p, k) =>
    val a = allowed(p); val z = lg(k)
    require(a.exists(identity), s"dead end after '$p'") // a mask must never leave no letter
    p + ('a' + (0 until 26).filter(a).maxBy(z)).toChar
  }

// ---- the games
var steps = 0; var mismatch = 0; var gateMismatch = 0; var notUniform = 0
def compare(q: Map[Int, Float], ref: Vector[String], what: String): Unit =
  steps += 1
  val w = head(q)
  val got = (0 until N).filter(w(_) > 0f).map(words).toVector
  if got != ref then { mismatch += 1; if mismatch <= 3 then println(s"  MISMATCH $what: ${got.size} vs ${ref.size}") }
  val on = (0 until N).filter(w(_) > 0f)
  if on.nonEmpty && on.exists(i => w(i) != w(on.head)) then notUniform += 1
  val g = gate(q)
  if (0 until N).filter(g(_) == 1f).map(words).toVector != ref || g.exists(x => x != 0f && x != 1f) then
    gateMismatch += 1

// the solver's games (variant B), each move checked against the head
var wins = 0; var movesSum = 0
for (a, n) <- answers.zipWithIndex do
  var cands = words; var q = Map.empty[Int, Float]; var moves = 0; var solved = false
  while !solved && moves < 12 do
    val g = solverPick(cands); moves += 1
    val fb = feedback(g, a)
    if n == 0 then println(s"  $g ${show(fb)}")
    cands = cands.filter(w => feedback(g, w) == fb) // the reference, move by move
    q = plus(q, compile(g, fb))
    compare(q, cands, s"solver, answer $a, move $moves")
    solved = fb.forall(_ == 2)
  if solved && moves <= 6 then wins += 1
  movesSum += moves
println(f"variant B (CPU solver, hard mode): ${wins * 100.0 / answers.size}%.1f%% won within 6, " +
  f"${movesSum.toDouble / answers.size}%.2f moves on average, ${answers.size} answers")

// random games: six dictionary words guessed at random, hints or not — greys and repeated letters of every kind
for a <- answers.take(200) do
  var cands = words; var q = Map.empty[Int, Float]
  for m <- 1 to 6 do
    val g = words(rnd.nextInt(N)); val fb = feedback(g, a)
    cands = cands.filter(w => feedback(g, w) == fb)
    q = plus(q, compile(g, fb))
    compare(q, cands, s"random, answer $a, move $m")
// the hard cases by hand: repeated letters in the guess, in the answer, in both
for (a, gs) <- Seq("abbey" -> Seq("babes", "ebbed", "kebab"), "eerie" -> Seq("geese", "elder", "emcee"),
    "llama" -> Seq("hallo", "lilac", "alley")) do
  var cands = words; var q = Map.empty[Int, Float]
  for g <- gs do
    val fb = feedback(g, a)
    cands = cands.filter(w => feedback(g, w) == fb); q = plus(q, compile(g, fb))
    compare(q, cands, s"by hand, answer $a, $g ${show(fb)}")
println(s"step 2: the head's set vs the reference after $steps moves: $mismatch mismatches; the gate: $gateMismatch; " +
  s"weights not uniform: $notUniform")

// step 3: the toy model plays, masked; at every state the same model unmasked is asked too
var states = 0; var aViol = 0; var aInvented = 0; var cViol = 0; var cInvented = 0; var cWins = 0; var cMoves = 0
val dict = words.toSet
for (a, n) <- answers.zipWithIndex do
  val r = new scala.util.Random(n)
  var moves = Vector.empty[Move]; var q = Map.empty[Int, Float]; var solved = false
  while !solved && moves.size < 12 do
    val lg = logits(r); val fixed = (0 until 5).map(lg).toVector // one set of logits for both
    states += 1
    val free = decode(fixed, _ => Vector.fill(26)(true))
    if !dict(free) then aInvented += 1 else if !consistent(free, moves) then aViol += 1
    val g = decode(fixed, p => mask(q, p))
    if !dict(g) then cInvented += 1 else if !consistent(g, moves) then cViol += 1
    val fb = feedback(g, a)
    if n == 0 then println(s"  masked: $g ${show(fb)}   (unmasked it wanted: $free)")
    moves :+= (g, fb); q = plus(q, compile(g, fb)); solved = fb.forall(_ == 2)
  if solved && moves.size <= 6 then cWins += 1
  cMoves += moves.size
println(f"step 3, $states states: unmasked — $aInvented invented words, $aViol against the hints; masked — " +
  f"$cInvented invented, $cViol against the hints; the masked toy won ${cWins * 100.0 / answers.size}%.1f%%, " +
  f"${cMoves.toDouble / answers.size}%.2f moves")

// ---- checks
check(N > 5000, "the dictionary is there")
check(feedback("babes", "abbey") == Vector(1, 1, 2, 2, 0), "feedback: repeated letters")
check(feedback("geese", "eerie") == Vector(0, 2, 1, 0, 2), "feedback: an extra e is grey")
check(mismatch == 0, "the head's allowed words equal the reference after every move")
check(gateMismatch == 0, "the gate relu(1 - V) is 0/1 and equals the reference")
check(notUniform == 0, "the head's weights are uniform on the allowed words")
check(cViol == 0 && cInvented == 0, "masked: 0 moves against the hints, 0 invented words")
check(aInvented + aViol > 0, "unmasked, the toy model does break the hints (the mask has work to do)")
if failures > 0 then sys.exit(1) else println("all checks passed")
