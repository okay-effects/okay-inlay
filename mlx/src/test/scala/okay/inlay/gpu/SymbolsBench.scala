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
    val (_, out) = sh(s"$symdexRoot/target/symdex/bin/symdex", "query", "--root", symdexRoot, "goal=named(S, N)",
      "limit=100000",
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

  /** whether it compiles against symdex's classpath, the first error, and the errors (for the model to read) */
  def compiles(code: String, cp: String): (Boolean, String, String) =
    val dir = java.nio.file.Files.createTempDirectory("symbols")
    val body = code.linesIterator.map("  " + _).mkString("\n")
    java.nio.file.Files.writeString(dir.resolve("Check.scala"),
      s"package benchcheck\n\nimport symdex.*\n\nobject Check:\n$body\n")
    val (rc, out) = sh("scala-cli", "compile", "--server=false", "-S", "3.9.0", "--classpath", cp, dir.toString)
    val clean = out.replaceAll("\u001b\\[[0-9;]*m", "").linesIterator
      .filterNot(l => l.startsWith("Compiling") || l.startsWith("Compiled") || l.contains("WARNING"))
      .map(_.replaceAll("/[^ ]*/Check.scala", "Check.scala")).take(25).mkString("\n")
    (rc == 0, clean.linesIterator.find(_.contains("Error")).getOrElse("").take(120), clean)

  private def source(owner: String): Option[String] =
    val p = java.nio.file.Path.of(s"$symdexRoot/src/main/scala/symdex/$owner.scala")
    if java.nio.file.Files.exists(p) then Some(java.nio.file.Files.readString(p)) else None

  /** the owner's public members as signatures (the body cut at ` =`), each with its doc comment's first line */
  def signatures(owner: String, ms: Map[String, Set[String]]): String =
    source(owner).fold("") { text =>
      val lines = text.linesIterator.toVector
      val names = ms.getOrElse(owner, Set.empty)
      val Decl = ("""^\s*((?:final |override |lazy |inline )*""" +
        """(?:def|val|var|case class|class|enum|object|trait|type)""" +
        """\s+([A-Za-z_][\w$]*)).*""").r
      lines.indices.flatMap { i =>
        lines(i) match
          case Decl(_, name) if names(name) && !lines(i).contains("private") =>
            val sig0 = lines(i).trim.replaceAll("""\s=\s*$""", "").replaceAll(""" = .*$""", "").replaceAll(":$", "")
            // as it is called: `Owner.name(…)`, the keywords dropped
            val keyword = """^(?:(?:final |override |lazy |inline )*""" +
              """(?:def|val|var|case class|class|enum|object|trait|type)\s+)"""
            val sig = sig0.replaceFirst(keyword, s"$owner.")
            // the doc comment just above: its first line of text
            val above = lines.take(i).reverse.takeWhile(l => l.trim.startsWith("*") || l.trim.startsWith("/**"))
            val doc = above.reverse.headOption.map(_.trim.stripPrefix("/**").stripSuffix("*/").trim).filter(_.nonEmpty)
            Some(s"  $sig" + doc.fold("")(d => s"   // $d"))
          case _ => None
      }.distinct.mkString("\n")
    }

  /** up to four real calls of the owner's members elsewhere in symdex */
  def usages(owner: String): String =
    val (_, out) = sh("grep", "-rhE", s"\\b$owner\\.[a-z]", s"$symdexRoot/src/main/scala/symdex",
      "--include=*.scala")
    out.linesIterator.map(_.trim)
      .filterNot(l => l.startsWith("*") || l.startsWith("//") || l.contains(s"object $owner"))
      .filter(_.length < 140).toVector.distinct.take(4).mkString("\n")

  test("Qwen on symdex's API: knowledge in the prompt, the compiler's feedback, the mask"):
    assume(sys.env.contains("SYMBOLS"), "SYMBOLS=1 to run")
    val repo = sys.env.getOrElse("SYMBOLS_MODEL", "mlx-community/Qwen3-4B-4bit")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val cp = java.nio.file.Files.readString(java.nio.file.Path.of(sys.env.getOrElse("SYMDEX_CP", "/tmp/symdex-cp.txt")))
      .trim
    val arms = sys.env.getOrElse("SYMBOLS_ARMS", "sigs,usage,loop,loop+held").split(",").toVector
    val ms = members()
    println(s"Equivalence's API as given:\n${signatures("Equivalence", ms)}\nits calls:\n${usages("Equivalence")}")
    println(s"symdex: ${ms.size} owners, ${ms.values.map(_.size).sum} members; arms ${arms.mkString(" ")}")
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true)
    val tok = chat.tokenizer
    val stamp = java.time.LocalDateTime.now.withNano(0).toString
    val history = java.nio.file.Path.of("../bench/symbols/history.tsv").toAbsolutePath.normalize
    if !java.nio.file.Files.exists(history) then
      java.nio.file.Files.writeString(history, "when\tmodel\tarm\ttask\trefs\tinvented\tcompiles\terror\tcode\n")
    def msg(role: String, content: String) =
      okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
        "content" -> okay.effects.json.Json.Str(content))
    var totals = Map.empty[String, (Int, Int, Int, Int)]
    for (task, i) <- tasks.zipWithIndex; arm <- arms do
      val owner = "`([A-Z]\\w*)`".r.findFirstMatchIn(task).map(_.group(1)).getOrElse("")
      val knowledge = arm match
        case "free" | "held" => ""
        case "outline" | "outline+held" =>
          s" Its members: ${ms.getOrElse(owner, Set.empty).toSeq.sorted.mkString(", ")}."
        case "sigs" => s"\n\n`$owner`'s public API:\n${signatures(owner, ms)}\n"
        case _ => s"\n\n`$owner`'s public API:\n${signatures(owner, ms)}\n\nHow symdex calls it:\n${usages(owner)}\n"
      val held = arm.endsWith("held")
      val rounds = if arm.startsWith("loop") then 3 else 1
      var messages = Vector(msg("user", "You write Scala 3 for the project symdex (package `symdex`, already " +
        "imported). " + task + knowledge + " Reply with one ```scala code block holding only the definition, no " +
        "explanation."))
      var result: (String, Boolean, String) = ("", false, "")
      var round = 0
      while round < rounds && !result._2 do
        round += 1
        val prompt = chat.prompt(messages, Nil, Map("enable_thinking" -> false))
        val s = Session(model, prompt, 400, chat.stops, port = if held then Names(tok, ms) else null)
        val out = tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector)
        s.close()
        val c = code(out)
        val (ok, first, errors) = compiles(c, cp)
        result = (c, ok, first)
        if !ok then messages = messages :+ msg("assistant", out) :+ msg("user",
          s"It does not compile:\n$errors\nFix it. Reply with one ```scala code block holding only the definition.")
      val (c, ok, err) = result
      val (refs, bad) = invented(c, ms)
      println(s"[${i + 1} $arm, $round round(s)] refs $refs invented ${bad.mkString(", ")} compiles $ok $err\n$c\n")
      val (r0, b0, k0, n0) = totals.getOrElse(arm, (0, 0, 0, 0))
      totals += arm -> (r0 + refs, b0 + bad.size, k0 + (if ok then 1 else 0), n0 + round)
      java.nio.file.Files.writeString(history, Seq(stamp, repo, arm, i + 1, refs, bad.mkString(" "), ok, err,
        c.replace("\n", "\\n").replace("\t", " ")).mkString("\t") + "\n", java.nio.file.StandardOpenOption.APPEND)
    for arm <- arms; (r, b, k, n) <- totals.get(arm) do
      println(s"$arm: $r member references, $b invented, $k of ${tasks.size} compile, $n rounds")
    model.close()
