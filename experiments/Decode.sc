var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// THE DECODE PORT: a module in the sampling loop sees the text so far and, per step, masks the next token's logits
// (allowed / forbidden), forces a token when only one is allowed, never asks the model to "please". A toy model:
// it "wants" a naive output (logit 5 on the token continuing it), knows an alternative (logit 3), everything else 0.
import java.util.regex.Pattern

case class Demo(name: String, vocab: Vector[String], naive: String, alt: String,
                prefixOk: String => Boolean, fullOk: String => Boolean)
val EOS = "<eos>"

val results = scala.collection.mutable.ArrayBuffer.empty[String]
def run(d: Demo): Unit =
  println(s"\n=== ${d.name}\n  the model wants: ${d.naive}")
  def cont(target: String, text: String): Option[String] =        // the longest vocab token continuing `target`
    if !target.startsWith(text) then None
    else if text == target then Some(EOS)
    else d.vocab.filter(t => target.startsWith(text + t)).maxByOption(_.length)
  var text = ""; var steps = 0; var forced = 0; var masked = 0; var done = false
  while !done && steps < 60 do
    steps += 1
    val logits = (d.vocab :+ EOS).map { t =>
      t -> (if cont(d.naive, text).contains(t) then 5f else if cont(d.alt, text).contains(t) then 3f else 0f) }.toMap
    val allowed = (d.vocab :+ EOS).filter(t => if t == EOS then d.fullOk(text) else d.prefixOk(text + t))
    require(allowed.nonEmpty, s"dead end after '$text'")              // a module must never leave no token
    val wanted = logits.maxBy(_._2)._1
    val pick =
      if allowed.size == 1 then { forced += 1; allowed.head }        // forced: no sampling needed (jump-forward)
      else allowed.maxBy(logits)                                       // the mask: -inf on the rest, argmax of what is left
    if pick != wanted && allowed.size > 1 then
      masked += 1; if masked <= 3 then println(f"  step $steps%2d: wanted '${wanted}' — forbidden; took '${pick}' (logit ${logits(pick)}%.0f)")
    if pick == EOS then done = true else text += pick
  println(s"  generated:       ${if text.length > 50 then text.take(50) + "…" else text}")
  println(s"  steps $steps, forced (no choice) $forced, masked $masked")
  results += text

// 1. a tool call by the schema's grammar: {"name":"read|write","path":"…"} and nothing else
val call = Pattern.compile("""\{"name":"(read|write)","path":"[A-Za-z/._]+"\}""")
def prefixOk(p: Pattern)(s: String) = { val m = p.matcher(s); m.matches() || m.hitEnd() }
run(Demo("tool call by its JSON Schema",
  Vector("{", "}", "\"", "name", "path", "mode", ":", ",", "read", "raed", "write", "fast", "src", "/", "Main", ".scala"),
  naive = """{"name":"raed","path":"src/Main.scala","mode":"fast"}""",
  alt   = """{"name":"read","path":"src/Main.scala"}""",
  prefixOk(call), s => call.matcher(s).matches()))

// 2. okay-facts at L4: a template — no digits, no declared entity names outside placeholders {x}
val template = Pattern.compile("""[^0-9{}]*(\{[a-z]\}[^0-9{}]*)*""")
val names = Seq("Sukienka", "Lena")
def tplOk(s: String) = prefixOk(template)(s) && !names.exists(s.contains)
run(Demo("okay-facts template (L4): digits and names impossible",
  Vector("Sukienka", " Lena", " kosztuje", " ", "189", "1", "8", "9", ",00", " zł", ".", "{", "}", "s", "p", "{s}", "{p}"),
  naive = "Sukienka Lena kosztuje 189,00 zł.",
  alt   = "{s} kosztuje {p}.",
  tplOk, s => template.matcher(s).matches() && !names.exists(s.contains)))

// 3. a secret that cannot be printed, however it is split into tokens: no 4 characters of it in a row
val secret = "sk-abc123"
val pieces = (0 to secret.length - 4).map(i => secret.substring(i, i + 4)).toSet
def noSecret(s: String) = !pieces.exists(s.contains)
for (label, vocab) <- Seq(
    "split as sk | -ab | c123" -> Vector("key", " =", " ", "sk", "-ab", "c123", "[", "hidden", "]"),
    "split as sk- | abc1 | 23" -> Vector("key", " =", " ", "sk-", "abc1", "23", "[", "hidden", "]")) do
  run(Demo(s"a secret ($label)", vocab, naive = s"key = $secret", alt = "key = [hidden]", noSecret, noSecret))

// 3b. the right strategy for a secret: ROLL BACK and REPLACE. The module keeps a snapshot from before a possible
// secret starts (in a real runtime: the KV cache at that point — the continuation as a value); when the next token
// would print 4 characters of it, the text and the KV go back to the snapshot and "[hidden]" is forced.
def runSecret(vocab: Vector[String]): Unit =
  val naive = s"key = $secret"
  def cont(text: String) =
    if naive.startsWith(text) && text != naive then vocab.filter(t => naive.startsWith(text + t)).maxByOption(_.length)
    else None
  var text = ""; var snapshot = ""; var log = Vector.empty[String]; var done = false
  while !done do
    cont(text) match
      case Some(t) =>
        val next = text + t
        // the longest suffix of the text that is a prefix of the secret: where a secret may have started
        val started = (1 to secret.length).filter(k => next.endsWith(secret.take(k))).maxOption.getOrElse(0)
        if started == 0 then snapshot = next
        if !noSecret(next) then
          log :+= s"'$t' would print '${pieces.filter(next.contains).head}' → back to '$snapshot', forced '[hidden]'"
          text = snapshot + "[hidden]"; done = true                     // the replacement, then the model goes on
        else text = next
      case None => done = true
  log.foreach(l => println(s"  $l"))
  println(s"  generated:       $text")
  results += text
println("\n=== a secret, rolled back and replaced")
runSecret(Vector("key", " =", " ", "sk", "-ab", "c123", "[", "hidden", "]"))
runSecret(Vector("key", " =", " ", "sk-", "abc1", "23", "[", "hidden", "]"))
// ---- checks
check(results(0) == """{"name":"read","path":"src/Main.scala"}""", "the tool call follows its schema")
check(results(1) == "{s} kosztuje {p}.", "the template has no digit and no name")
check(!results(2).contains(secret) && !results(3).contains(secret), "the plain mask never prints the secret")
check(results(4) == "key = [hidden]" && results(5) == "key = [hidden]", "rolled back and replaced, both splits")
if failures > 0 then sys.exit(1) else println("all checks passed")
