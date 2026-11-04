package okay.inlay.forge

import java.nio.file.{Files, Path}

/**
 * A PROJECT AS SYMDEX KNOWS IT: its root (indexed: SemanticDB), its compile classpath (a file, `sbt "export
 * Compile/fullClasspath"`), symdex's binary. What the tool asks: the owners and their members (`named(S, N)`), each
 * member's declaration as it is called (from its source file, by `defined_in`), the real calls of a member elsewhere.
 */
final class Project(val root: Path, val classpath: String, symdex: String):
  private def sh(cmd: String*): String =
    val p = ProcessBuilder(cmd*).redirectErrorStream(true).start()
    val out = String(p.getInputStream.readAllBytes)
    p.waitFor()
    out

  private def query(goal: String): Vector[Map[String, String]] =
    val out = sh(symdex, "query", "--root", root.toString, s"goal=$goal", "limit=1000000", "format=json")
    val j = okay.effects.json.Json.fromText(out.linesIterator.find(_.startsWith("{")).getOrElse("{}"))
    j.field("answers").map(_.list.map(a => a.record.map((k, v) => k -> v.string).toMap).toVector)
      .getOrElse(Vector.empty)

  /** each symbol's name, and the file it is defined in (relative to the root) */
  lazy val symbols: Vector[(String, String, String)] =
    val files = query("defined_in(S, F)").map(a => a("S") -> a("F")).toMap
    query("named(S, N)").flatMap(a => files.get(a("S")).map(f => (a("S"), a("N"), f)))

  /** the owners' members: `pkg/Owner.member` symbols → (owner, member, the owner's package, its file) */
  lazy val members: Vector[Member] =
    // main sources only: a test's fixtures are not on the compile classpath
    symbols.filterNot((s, _, f) => s.endsWith(")") || s.contains("[") || s.startsWith("local") || f.contains("/test/"))
      .flatMap { (sym, n, f) =>
      val body = sym.stripSuffix(".").stripSuffix("#").replaceAll("""\(\+?\d*\)$""", "")
      val dot = body.lastIndexOf('.')
      val slash = body.lastIndexOf('/')
      // members of OBJECTS only (`pkg/Owner.member`): callable as Owner.member
      if dot <= slash + 1 || body.lastIndexOf('#') > slash || n.isEmpty || !(n.head.isLower || n.head == '_') then None
      else
        val owner = body.substring(slash + 1, dot)
        if owner.contains('.') || owner.isEmpty || owner == "package" then None
        else Some(Member(body.take(slash).replace('/', '.'), owner, n, f))
    }.distinct

  /** a member's declaration as it is called, `Owner.name(a: A): R`, and its doc comment's first line */
  def declaration(m: Member): Option[(String, String)] =
    val p = root.resolve(m.file)
    if !Files.exists(p) then None
    else
      val lines = Files.readString(p).linesIterator.toVector
      val keyword = """^\s*(?:(?:final |override |lazy |inline )*(?:def|val)\s+)"""
      val Decl = (keyword + s"""(${java.util.regex.Pattern.quote(m.name)})\\b.*""").r
      lines.indices.collectFirst { case i if Decl.matches(lines(i)) && !lines(i).contains("private") =>
        // the signature to its ` =` (a multi-line one joined while its parentheses are open)
        var j = i
        var text = lines(i)
        while text.count(_ == '(') > text.count(_ == ')') && j + 1 < lines.length do
          j += 1
          text += " " + lines(j).trim
        val sig = text.replaceAll("""\s=\s.*$""", "").replaceAll("""\s=\s*$""", "").replaceAll(":$", "")
          .replaceFirst(keyword, java.util.regex.Matcher.quoteReplacement(s"${m.owner}.")).trim
        val above = lines.take(i).reverse.takeWhile(l => l.trim.startsWith("*") || l.trim.startsWith("/**"))
        val doc = above.reverse.headOption.map(_.trim.stripPrefix("/**").stripSuffix("*/").trim).getOrElse("")
        (sig, doc)
      }

  /** a file's imports, as a program beside it needs them */
  def imports(file: String): Vector[String] =
    val p = root.resolve(file)
    if file.isEmpty || !Files.isRegularFile(p) then Vector.empty
    else Files.readString(p).linesIterator.filter(_.startsWith("import ")).map(_.trim).toVector // the top level alone

  /** up to `n` real calls of `Owner.member` in the project's sources (but its own declaration) */
  def calls(m: Member, n: Int = 3): Vector[String] =
    val out = sh("grep", "-rhE", s"\\b${m.owner}\\.${m.name}\\b", root.toString, "--include=*.scala")
    out.linesIterator.map(_.trim).filterNot(l => l.startsWith("*") || l.startsWith("//") || l.contains("def " + m.name))
      .filter(_.length < 140).toVector.distinct.take(n)

/** a member of an object */
final case class Member(pkg: String, owner: String, name: String, file: String)
