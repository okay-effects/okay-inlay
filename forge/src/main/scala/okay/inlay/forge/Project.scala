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

  /** the project's types (classes, traits, enums, at a package's top level): simple name → fully qualified */
  lazy val types: Map[String, String] =
    symbols.collect { case (s, n, f) if s.endsWith("#") && !f.contains("/test/") &&
        s.count(_ == '/') >= 1 && !s.dropWhile(_ != '/').drop(1).stripSuffix("#").exists(c => c == '.' || c == '#') =>
      n -> s.stripSuffix("#").replace('/', '.')
    }.toMap

  /** where each project type is declared (its file), for reading its fields */
  private lazy val typeFiles: Map[String, String] =
    symbols.collect { case (s, n, f) if s.endsWith("#") && types.get(n).contains(s.stripSuffix("#").replace('/', '.')) =>
      n -> f }.toMap

  /** the project types a type's declaration names (its fields, its cases' fields): read from its block of text */
  def fieldTypes(name: String): Vector[String] =
    typeFiles.get(name).map(root.resolve(_)).filter(Files.isRegularFile(_)).toVector.flatMap { f =>
      val lines = Files.readString(f).linesIterator.toVector
      val Head = s"""\\s*(?:final |sealed |abstract |private |case )*(?:class|trait|enum)\\s+${
        java.util.regex.Pattern.quote(name)}\\b.*""".r
      lines.indexWhere(l => Head.matches(l)) match
        case -1 => Vector.empty
        case i =>
          val ind = lines(i).takeWhile(_ == ' ').length
          val block = lines(i) +: lines.drop(i + 1).takeWhile(l => l.trim.isEmpty || l.takeWhile(_ == ' ').length > ind)
          """\b[A-Z]\w*\b""".r.findAllIn(block.mkString("\n")).toVector.distinct.filter(n => n != name && types.contains(n))
    }

  /** the types `names` reach through their fields (themselves included), as far as `limit` */
  def reach(names: Vector[String], limit: Int = 40): Vector[String] =
    var seen = Vector.empty[String]
    var todo = names.filter(types.contains)
    while todo.nonEmpty && seen.size < limit do
      val n = todo.head
      todo = todo.tail
      if !seen.contains(n) then
        seen :+= n
        todo ++= fieldTypes(n).filterNot(seen.contains)
    seen

  /** the owners' members: `pkg/Owner.member` symbols → (owner, member, the owner's package, its file) */
  lazy val members: Vector[Member] =
    // main sources only: a test's fixtures are not on the compile classpath
    symbols.filterNot((s, _, f) => s.endsWith(")") || s.contains("[") || s.startsWith("local") || f.contains("/test/"))
      .flatMap { (sym, n, f) =>
      val body = sym.stripSuffix(".").stripSuffix("#").replaceAll("""\(\+?\d*\)$""", "")
      val dot = body.lastIndexOf('.')
      val slash = body.lastIndexOf('/')
      // an object's member (`pkg/Owner.member`, called Owner.member) or an instance's (`pkg/Cls#member`, called on a
      // value of Cls) — not nested deeper than one owner
      val hash = body.lastIndexOf('#')
      val instance = hash > slash && hash > dot
      val cut = if instance then hash else dot
      if cut <= slash + 1 || n.isEmpty || !(n.head.isLower || n.head == '_') then None
      else
        val owner = body.substring(slash + 1, cut)
        if owner.contains('.') || owner.contains('#') || owner.isEmpty || owner == "package" then None
        else Some(Member(body.take(slash).replace('/', '.'), owner, n, f, instance))
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
          .replaceFirst(keyword,
            java.util.regex.Matcher.quoteReplacement(s"${m.owner}${if m.instance then "#" else "."}"))
          .trim
        val above = lines.take(i).reverse.takeWhile(l => l.trim.startsWith("*") || l.trim.startsWith("/**"))
        val doc = above.reverse.headOption.map(_.trim.stripPrefix("/**").stripSuffix("*/").trim).getOrElse("")
        (sig, doc)
      }

  /** a class's (trait's, enum's) type parameters as declared — `Box[A, B]` → Vector("A", "B"); none if it has
   * none; `None` when one is not of a plain kind (`F[_]`, bounds, variances kept as written) */
  def typeParams(owner: String, file: String): Option[Vector[String]] =
    val p = root.resolve(file)
    if file.isEmpty || !Files.isRegularFile(p) then Some(Vector.empty)
    else
      val Head = (s"""(?:class|trait|enum)\\s+${java.util.regex.Pattern.quote(owner)}\\b(\\[.*)?""").r.unanchored
      Files.readString(p).linesIterator.collectFirst { case Head(rest) => Option(rest) }.flatten match
        case None => Some(Vector.empty)
        case Some(rest) =>
          // the bracket's content, to its matching close
          var depth = 0
          val inside = rest.takeWhile { c =>
            if c == '[' then depth += 1 else if c == ']' then depth -= 1
            depth > 0
          }.drop(1)
          val ps = Tasks.split(inside).map(_.trim.stripPrefix("+").stripPrefix("-"))
          if ps.forall(_.matches("[A-Za-z_]\\w*")) then Some(ps) else None

  /** whether a member is declared more than once in its file (overloads: which one a symbol names is unclear) */
  def overloaded(m: Member): Boolean =
    val p = root.resolve(m.file)
    val decl = s"""\\s*(?:(?:final |override |lazy |inline |private\\[\\w+\\] )*(?:def|val)\\s+)""" +
      java.util.regex.Pattern.quote(m.name) + "\\b.*"
    Files.isRegularFile(p) && Files.readString(p).linesIterator.count(_.matches(decl)) > 1

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
final case class Member(pkg: String, owner: String, name: String, file: String, instance: Boolean = false)
