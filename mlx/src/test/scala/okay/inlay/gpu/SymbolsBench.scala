package okay.inlay.gpu

import _root_.mlx.llm.{Adaptable, Chat, Lora, Model, Models, Names, Session}

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

  /** each task's behaviour check: its definition against the API itself on inputs (true: right) */
  val checks = Vector(
    """Seq("object Orders:\n  def total = 1\n  def other = 2\n", "object A:\n  def b = 1\n").forall(t =>
      Check.findTotal(t) == Equivalence.definitionIn(t, "Orders", "total"))""",
    """Seq("package a\nimport b.c\nobject X", "val x = 1").forall(t => Check.imports(t) == Equivalence.header(t))""",
    """Seq("java/io/File#", "scala/List.", "scala/Predef.println().").forall(s =>
      Check.isImpure(s) == Equivalence.impure.exists(s.startsWith))""",
    """Seq("{\"equivalent\":true}", "{\"equivalent\":false,\"input\":\"1\"}", "nothing").forall(o =>
      Check.sameBehaviour(o) == Equivalence.verdict(o).exists(_.equivalent))""",
    """Check.relationNames == CodeFacts.relations.split(", ").map(_.takeWhile(_ != '(').trim).toList""",
    """Seq("callers", "defined", "affects").forall(r => Check.needsSeed(r) == CodeFacts.seeded(r))""",
    """Check.listTmp().exists(_.code == 0)""",
    """Seq("/tmp/x/target/scala-3.9.0/meta").map(java.nio.file.Path.of(_)).forall(p =>
      Check.classesOf(p) == Tasty.classDirOf(p))""",
    """Check.generatorsSize == Equivalence.checkSource.length""",
    """Check.name == "exists"""")

  /** whether the definition behaves: its check run against symdex's classpath */
  def behaves(code: String, check: String, cp: String): Boolean =
    val dir = java.nio.file.Files.createTempDirectory("behaves")
    val body = code.linesIterator.map("  " + _).mkString("\n")
    java.nio.file.Files.writeString(dir.resolve("Check.scala"),
      s"package benchcheck\n\nimport symdex.*\n\nobject Check:\n$body\n")
    java.nio.file.Files.writeString(dir.resolve("Main.scala"), s"package benchcheck\n\nimport symdex.*\n\n" +
      s"@main def benchMain(): Unit = println(if (try { $check } catch { case _: Throwable => false }) \"PASS\" " +
      "else \"FAIL\")\n")
    val (_, out) = sh("scala-cli", "run", "--server=false", "-S", "3.9.0", "--classpath", cp, dir.toString)
    out.linesIterator.exists(_.trim == "PASS")

  def sh(cmd: String*): (Int, String) =
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

  def code(answer: String): String =
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

  /** the owner's public members as they are called (`Owner.name(…): T`, the body cut at ` =`), each with its doc
   * comment's first line: (name, the line) */
  def entries(owner: String, ms: Map[String, Set[String]]): Vector[(String, String)] =
    source(owner).fold(Vector.empty) { text =>
      val lines = text.linesIterator.toVector
      val names = ms.getOrElse(owner, Set.empty)
      val keyword = """^\s*(?:(?:final |override |lazy |inline )*""" +
        """(?:def|val|var|case class|class|enum|object|trait|type)\s+)"""
      val Decl = (keyword + """([A-Za-z_][\w$]*).*""").r
      lines.indices.flatMap { i =>
        lines(i) match
          case Decl(name) if names(name) && !lines(i).contains("private") =>
            val sig0 = lines(i).replaceAll("""\s=\s*$""", "").replaceAll(""" = .*$""", "").replaceAll(":$", "")
            val sig = sig0.replaceFirst(keyword, s"$owner.").trim
            val above = lines.take(i).reverse.takeWhile(l => l.trim.startsWith("*") || l.trim.startsWith("/**"))
            val doc = above.reverse.headOption.map(_.trim.stripPrefix("/**").stripSuffix("*/").trim).filter(_.nonEmpty)
            Some(name -> (s"  $sig" + doc.fold("")(d => s"   // $d")))
          case _ => None
      }.toVector.distinctBy(_._1)
    }

  private def words(s: String): Set[String] =
    s.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase.split("[^a-z]+").filter(_.length > 2).toSet

  /** the members most like the task (their names' and docs' words shared with it), three at most */
  def focus(task: String, es: Vector[(String, String)]): Vector[(String, String)] =
    val t = words(task)
    es.map(e => e -> (words(e._1) ++ words(e._2)).count(t)).filter(_._2 > 0).sortBy(-_._2).take(3).map(_._1)

  /** up to four real calls of the owner's members (`only`, if given) elsewhere in symdex */
  def usages(owner: String, only: Set[String] = Set.empty): String =
    val (_, out) = sh("grep", "-rhE", s"\\b$owner\\.[a-z]", s"$symdexRoot/src/main/scala/symdex",
      "--include=*.scala")
    out.linesIterator.map(_.trim)
      .filterNot(l => l.startsWith("*") || l.startsWith("//") || l.contains(s"object $owner"))
      .filter(l => only.isEmpty || only.exists(m => l.contains(s"$owner.$m")))
      .filter(_.length < 140).toVector.distinct.take(4).mkString("\n")

  /** what to import for a name the compiler did not find: symdex's own (already imported), or a common one */
  def hints(errors: String, ms: Map[String, Set[String]]): String =
    val common = Map("Path" -> "java.nio.file.Path", "Paths" -> "java.nio.file.Paths",
      "Files" -> "java.nio.file.Files",
      "Duration" -> "scala.concurrent.duration.Duration",
      "FiniteDuration" -> "scala.concurrent.duration.FiniteDuration")
    "Not found: (?:type )?([A-Z]\\w*)".r.findAllMatchIn(errors).map(_.group(1)).toVector.distinct.flatMap { n =>
      if ms.contains(n) then Some(s"Hint: $n is symdex's, already imported: call it as $n.member.")
      else common.get(n).map(q => s"Hint: import $q")
    }.mkString("\n")

  /** each task's reference solution: the API itself, called */
  val references = Vector(
    """def findTotal(text: String): Either[String, String] = Equivalence.definitionIn(text, "Orders", "total")""",
    "def imports(text: String): Vector[String] = Equivalence.header(text)",
    "def isImpure(symbol: String): Boolean = Equivalence.impure.exists(symbol.startsWith)",
    "def sameBehaviour(out: String): Boolean = Equivalence.verdict(out).exists(_.equivalent)",
    """def relationNames: List[String] = CodeFacts.relations.split(", ").map(_.takeWhile(_ != '(').trim).toList""",
    "def needsSeed(relation: String): Boolean = CodeFacts.seeded(relation)",
    """def listTmp(): Either[String, Ran] = Exec.process(List("ls"), java.nio.file.Path.of("/tmp"), 5000)""",
    "def classesOf(root: java.nio.file.Path): java.nio.file.Path = Tasty.classDirOf(root)",
    "def generatorsSize: Int = Equivalence.checkSource.length",
    """def name: String = "exists"""")

  test("the checks: the reference solutions behave, a wrong one does not"):
    assume(sys.env.contains("SYMBOLS"), "SYMBOLS=1 to run")
    val cp = java.nio.file.Files.readString(java.nio.file.Path.of(sys.env.getOrElse("SYMDEX_CP", "/tmp/symdex-cp.txt")))
      .trim
    val refs = references
    for (r, i) <- refs.zipWithIndex do assert(behaves(r, checks(i), cp), s"reference ${i + 1} fails its check")
    assert(!behaves("def isImpure(symbol: String): Boolean = Equivalence.impure.exists(symbol.endsWith)", checks(2),
      cp), "a wrong one passes")

  test("Qwen on symdex's API: knowledge in the prompt, the compiler's feedback, the mask"):
    assume(sys.env.contains("SYMBOLS"), "SYMBOLS=1 to run")
    val repo = sys.env.getOrElse("SYMBOLS_MODEL", "mlx-community/Qwen3-4B-4bit")
    val dir = Models.snapshot(repo)
    assume(dir.isDefined, s"$repo is not in the HuggingFace cache")
    val cp = java.nio.file.Files.readString(java.nio.file.Path.of(sys.env.getOrElse("SYMDEX_CP", "/tmp/symdex-cp.txt")))
      .trim
    val arms = sys.env.getOrElse("SYMBOLS_ARMS", "loop,best,best+held").split(",").toVector
    val ms = members()
    println(s"symdex: ${ms.size} owners, ${ms.values.map(_.size).sum} members; arms ${arms.mkString(" ")}")
    val chat = Chat.load(dir.get)
    val model = Model.load(dir.get, mapped = true)
    // an adapter (okay-forge's), if given: on the model for every arm
    val adapter = sys.env.get("SYMBOLS_ADAPTER").filter(_.nonEmpty)
      .map(a => Lora.attach(model.asInstanceOf[Adaptable], a))
    println(s"adapter: ${sys.env.getOrElse("SYMBOLS_ADAPTER", "none")}")
    val tok = chat.tokenizer
    val stamp = java.time.LocalDateTime.now.withNano(0).toString
    val history = java.nio.file.Path.of("../bench/symbols/history.tsv").toAbsolutePath.normalize
    if !java.nio.file.Files.exists(history) then
      java.nio.file.Files.writeString(history, "when\tmodel\tarm\ttask\trefs\tinvented\tcompiles\terror\tcode\n")
    def msg(role: String, content: String) =
      okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
        "content" -> okay.effects.json.Json.Str(content))
    var totals = Map.empty[String, (Int, Int, Int, Int, Int)]
    for (task, i) <- tasks.zipWithIndex; arm <- arms do
      val owner = "`([A-Z]\\w*)`".r.findFirstMatchIn(task).map(_.group(1)).getOrElse("")
      val es = entries(owner, ms)
      // "best": the members like the task (else all), their calls, thinking, five rounds with hints
      val best = arm.startsWith("best")
      val shown = if best then Some(focus(task, es)).filter(_.nonEmpty).getOrElse(es) else es
      val knowledge = arm match
        case "free" | "held" => ""
        case "outline" | "outline+held" => s" Its members: ${es.map(_._1).sorted.mkString(", ")}."
        case "sigs" => s"\n\n`$owner`'s public API:\n${shown.map(_._2).mkString("\n")}\n"
        case _ =>
          val calls = usages(owner, if best then shown.map(_._1).toSet else Set.empty)
          s"\n\n`$owner`'s public API${if best then " (what this task needs)" else ""}:\n" +
            s"${shown.map(_._2).mkString("\n")}\n\nHow symdex calls it:\n$calls\n"
      val held = arm.endsWith("held")
      val rounds = if best then 5 else if arm.startsWith("loop") then 3 else 1
      var messages = Vector(msg("user", "You write Scala 3 for the project symdex (package `symdex`, already " +
        "imported). " + task + knowledge + (if best then " Your definition goes in your own code, outside symdex: " +
          "call symdex's members, do not reimplement them, and declare no object or class." else "") +
        " Reply with one ```scala code block holding only the definition, no explanation."))
      // "+tests": a definition that compiles but fails its check is sent back with the check, as a failing test
      val tested = arm.contains("tests")
      var result: (String, Boolean, String) = ("", false, "")
      var passed = false
      var round = 0
      while round < rounds && !(if tested then passed else result._2) do
        round += 1
        val prompt = chat.prompt(messages, Nil, Map("enable_thinking" -> best))
        val s = Session(model, prompt, if best then 3000 else 400, chat.stops,
          port = if held then Names(tok, ms) else null)
        val out = tok.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector)
        s.close()
        val answer = out.split("</think>").last
        val c = code(answer)
        val (ok, first, errors) = compiles(c, cp)
        result = (c, ok, first)
        passed = ok && tested && behaves(c, checks(i), cp)
        if !ok then messages = messages :+ msg("assistant", answer) :+ msg("user",
          s"It does not compile:\n$errors\n${if best then hints(errors, ms) else ""}\nFix it. Reply with one " +
            "```scala code block holding only the definition.")
        else if tested && !passed then messages = messages :+ msg("assistant", answer) :+ msg("user",
          s"It compiles, but this test fails (it must be true):\n```scala\n${checks(i)}\n```\nFix it. Reply with " +
            "one ```scala code block holding only the definition.")
      val (c, ok, err) = result
      val (refs, bad) = invented(c, ms)
      val right = ok && behaves(c, checks(i), cp)
      println(s"[${i + 1} $arm, $round round(s)] refs $refs invented ${bad.mkString(", ")} compiles $ok behaves " +
        s"$right $err\n$c\n")
      val (r0, b0, k0, n0, g0) = totals.getOrElse(arm, (0, 0, 0, 0, 0))
      totals += arm -> (r0 + refs, b0 + bad.size, k0 + (if ok then 1 else 0), n0 + round, g0 + (if right then 1 else 0))
      java.nio.file.Files.writeString(history, Seq(stamp, repo, arm + adapter.fold("")(_ => "+forge"), i + 1, refs,
        bad.mkString(" "),
        if right then "right" else ok.toString, err, c.replace("\n", "\\n").replace("\t", " ")).mkString("\t") + "\n",
        java.nio.file.StandardOpenOption.APPEND)
    for arm <- arms; (r, b, k, n, g) <- totals.get(arm) do
      println(s"$arm: $r member references, $b invented, $k of ${tasks.size} compile, $g behave, $n rounds")
    model.close()
