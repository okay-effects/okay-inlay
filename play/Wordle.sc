// WORDLE TO PLAY (not an experiment: it reads the keyboard). The same dictionary and feedback as
// experiments/Wordle.sc. Run: scala-cli run play/Wordle.sc            — you guess the computer's word
//                              scala-cli run play/Wordle.sc -- solve  — the computer guesses yours
// Commands while guessing: ?  how many words are still possible (and a few of them), !  give up.
val words: Vector[String] = scala.io.Source.fromFile("/usr/share/dict/words").getLines()
  .filter(_.matches("[a-z]{5}")).toVector.distinct.sorted
val dict = words.toSet

def feedback(guess: String, answer: String): Vector[Int] =
  val fb = Array.fill(5)(0)
  val left = Array.fill(26)(0)
  for i <- 0 until 5 do if guess(i) == answer(i) then fb(i) = 2 else left(answer(i) - 'a') += 1
  for i <- 0 until 5 if fb(i) == 0 do
    val l = guess(i) - 'a'
    if left(l) > 0 then { fb(i) = 1; left(l) -= 1 }
  fb.toVector
def show(fb: Vector[Int]) = fb.map(Vector("⬜", "🟨", "🟩")(_)).mkString
def pick(cands: Vector[String]): String =
  val pos = Array.ofDim[Int](5, 26); val has = Array.fill(26)(0)
  for w <- cands do
    for i <- 0 until 5 do pos(i)(w(i) - 'a') += 1
    for c <- w.distinct do has(c - 'a') += 1
  cands.maxBy(w => (0 until 5).map(i => pos(i)(w(i) - 'a')).sum + w.distinct.map(c => has(c - 'a')).sum)
def read(prompt: String): String =
  print(prompt); Console.flush(); Option(scala.io.StdIn.readLine()).getOrElse("!").trim.toLowerCase

if args.headOption.contains("solve") then
  println("Think of a 5-letter word. For each guess type its colours: g green, y yellow, . grey (e.g. g.y..)")
  var cands = words; var n = 0; var done = false
  while !done do
    if cands.isEmpty then { println("No word in the dictionary fits those colours."); done = true }
    else
      val g = pick(cands); n += 1
      val s = read(s"$n. $g  ${cands.size} possible — colours: ")
      if s.matches("[gy.]{5}") then
        val fb = s.map { case 'g' => 2; case 'y' => 1; case _ => 0 }.toVector
        println(s"   $g ${show(fb)}")
        if fb.forall(_ == 2) then { println(s"Got it in $n."); done = true }
        else cands = cands.filter(w => feedback(g, w) == fb)
      else if s == "!" then done = true
      else { println("   five of g y ."); n -= 1 }
else
  val answer = words(new scala.util.Random().nextInt(words.size))
  val hard = read("Hard mode (every guess must fit the colours so far)? [y/N] ") == "y"
  println(s"Guess the word: 5 letters, 6 tries. ? = how many words are still possible, ! = give up.")
  var cands = words; var moves = Vector.empty[(String, Vector[Int])]; var done = false
  while !done do
    val g = read(s"${moves.size + 1}> ")
    if g == "!" then { println(s"It was: $answer"); done = true }
    else if g == "?" then println(s"   ${cands.size} possible: ${cands.take(8).mkString(" ")}" +
      (if cands.size > 8 then " …" else ""))
    else if !g.matches("[a-z]{5}") then println("   five letters, please")
    else if !dict(g) then println("   not in the dictionary")
    else if hard && !cands.contains(g) then println("   hard mode: that word contradicts the colours so far")
    else
      val fb = feedback(g, answer)
      moves :+= (g, fb); cands = cands.filter(w => feedback(g, w) == fb)
      moves.foreach((w, f) => println(s"   $w ${show(f)}"))
      if fb.forall(_ == 2) then { println(s"Solved in ${moves.size}!"); done = true }
      else if moves.size == 6 then { println(s"Out of tries. It was: $answer"); done = true }
