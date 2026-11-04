package okay.inlay.gpu

import _root_.mlx.llm.{Chat, Model, Models, Names, Session}

/**
 * CODE WITHOUT INVENTED APIs, MEASURED (specs/symbols.md): tasks on symdex's own objects, their API not in the prompt;
 * Qwen free and under `Names` (the members from symdex's index). Per answer: the member references `Owner.x` with x
 * not a member (invented), and whether it compiles against symdex's classpath. Opt-in: SYMBOLS=1 (and SYMBOLS_MODEL,
 * Qwen3-4B by default); SYMDEX_CP: a file with symdex's classpath (`sbt "export Compile/fullClasspath"`).
 */
class SymbolsBench extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")
  private val home = sys.props("user.home")
  private val symdexRoot = s"$home/work/my/symdex"

  val tasks = Vector(
    "symdex's object `Equivalence` can find a definition's text inside a file's text. Write " +
      "`def findTotal(text: String): Either[String, String]` that finds `def total` inside `object Orders` with it.",
    "symdex's object `Equivalence` can read a file's package clauses and imports. Write " +
      "`def imports(text: String): Vector[String]` with it.",
    "symdex's object `Equivalence` lists the symbols that read or write the world. Write " +
      "`def isImpure(symbol: String): Boolean` with it (a symbol is impure if it starts with one of them).",
    "symdex's object `Equivalence` reads a comparison program's answer from its output. Write " +
      "`def sameBehaviour(out: String): Boolean` with it: true when the answer reads and says equivalent.",
    "symdex's object `CodeFacts` describes the relations the index gives, as text. Write " +
      "`def relationNames: List[String]` returning the relations' names (before each parenthesis).",
    "symdex's object `CodeFacts` knows which prelude relations take a seed as their first argument. Write " +
      "`def needsSeed(relation: String): Boolean` with it.",
    "symdex's object `Exec` can run a process directly, outside any effect. Write " +
      "`def listTmp(): Either[String, Ran]` running `ls` in /tmp with a 5 second timeout.",
    "symdex's object `Tasty` finds the class directory belonging to a SemanticDB root. Write " +
      "`def classesOf(root: java.nio.file.Path): java.nio.file.Path` with it.",
    "symdex's object `Equivalence` keeps the generators' source that is put beside every generated program. Write " +
      "`def generatorsSize: Int` returning its length in characters.",
    "symdex's object `Disk` has an operation telling whether a path exists, performed as an effect. Write a " +
      "one-line comment naming that operation, then `def name: String` returning its name as a string.")

  private def sh(cmd: String*): (Int, String) =
    val p = ProcessBuilder(cmd*).redirectErrorStream(true).start()
    val out = String(p.getInputStream.readAllBytes)
    (p.waitFor(), out)

  /** symdex's members: one Datalog query over its index */
  def members(): Map[String, Set[String]] =
    val (_, out) = sh(s"$symdexRoot/target/symdex/bin/symdex", "query", "--root", symdexRoot, "goal=named(S, N)", "limit=100000",
      "format=json")
    val j = okay.effects.json.Json.fromText(out.linesIterator.find(_.startsWith("{")).get)
    val named = j("answers").list.map(a => (a("S").string, a("N").string)).filter(_._1.startsWith("symdex/"))
    Names.fromSymbols(named.toSeq)

  private val Ref = """\b([A-Z][\w$]*)\.([a-zA-Z_][\w$]*)""".r

  /** the member references `Owner.x` (owners symdex's), and those with x not a member */
  def invented(code: String, ms: Map[String, Set[String]]): (Int, Vector[String]) =
    val refs = Ref.findAllMatchIn(code).map(m => (m.group(1), m.group(2))).filter((o, _) => ms.contains(o)).toVector
    (refs.size, refs.filterNot((o, x) => ms(o)(x)).map((o, x) => s"$o.$x"))

  private def code(answer: String): String =
    val block = "(?s)```(?:scala)?\\n(.*?)(```|$)".r.findFirstMatchIn(answer).map(_.group(1)).getOrElse(answer)
    block.linesIterator.filterNot(_.trim.startsWith("package ")).mkString("\n")

  /** whether it compiles against symdex's classpath, and the first error */
  def compiles(code: String, cp: String): (Boolean, String) =
    val dir = java.nio.file.Files.createTempDirectory("symbols")
    val body = code.linesIterator.map("  " + _).mkString("\n")
    java.nio.file.Files.writeString(dir.resolve("Check.scala"),
      s"package benchcheck\n\nimport symdex.*\n\nobject Check:\n$body\n")
    val (rc, out) = sh("scala-cli", "compile", "--server=false", "-S", "3.9.0", "--classpath", cp, dir.toString)
    (rc == 0, out.replaceAll("\u001b\\[[0-9;]*m", "").linesIterator.find(_.contains("Error")).getOrElse("")
      .take(120))

  test("Qwen free and under Names on symdex's API"):
    assume(sys.env.contains("SYMBOLS"), "SYMBOLS=1 to run")
    val repo = sys.env.getOrElse("SYMBOLS_MODEL", "mlx-community/Qwen3-4B-4bit")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val cp = java.nio.file.Files.readString(java.nio.file.Path.of(sys.env.getOrElse("SYMDEX_CP", "/tmp/symdex-cp.txt")))
      .trim
    val ms = members()
    println(s"symdex: ${ms.size} owners, ${ms.values.map(_.size).sum} members")
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true)
    val tok = chat.tokenizer
    val stamp = java.time.LocalDateTime.now.withNano(0).toString
    val history = java.nio.file.Path.of("../bench/symbols/history.tsv").toAbsolutePath.normalize
    if !java.nio.file.Files.exists(history) then
      java.nio.file.Files.writeString(history, "when\tmodel\tarm\ttask\trefs\tinvented\tcompiles\terror\tcode\n")
    var totals = Map.empty[String, (Int, Int, Int)]
    for (task, i) <- tasks.zipWithIndex; arm <- Seq("free", "held") do
      val prompt = chat.ask("You write Scala 3 for the project symdex (package `symdex`, already imported). " + task +
        " Reply with one ```scala code block holding only the definition, no explanation.",
        Map("enable_thinking" -> false))
      val port = if arm == "held" then Names(tok, ms) else null
      val s = Session(model, prompt, 300, chat.stops, port = port)
      val out = tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector)
      s.close()
      val c = code(out)
      val (refs, bad) = invented(c, ms)
      val (ok, err) = compiles(c, cp)
      println(s"[${i + 1} $arm] refs $refs invented ${bad.mkString(", ")} compiles $ok $err\n$c\n")
      val (r0, b0, k0) = totals.getOrElse(arm, (0, 0, 0))
      totals += arm -> (r0 + refs, b0 + bad.size, k0 + (if ok then 1 else 0))
      java.nio.file.Files.writeString(history, Seq(stamp, repo, arm, i + 1, refs, bad.mkString(" "), ok, err,
        c.replace("\n", "\\n").replace("\t", " ")).mkString("\t") + "\n", java.nio.file.StandardOpenOption.APPEND)
    for (arm, (r, b, k)) <- totals.toSeq.sortBy(_._1) do
      println(s"$arm: $r member references, $b invented, $k of ${tasks.size} compile")
    model.close()
