package okay.inlay.forge

/** A TASK: write `def <name>(<params>): <ret>` doing <text> with a project's member; its reference definition */
final case class Task(id: String, member: Member, kind: String, name: String, params: String, ret: String,
    text: String, reference: String):
  def arity: Int = if params.isEmpty then 0 else Tasks.split(params).size
  def signature: String = s"def $name${if params.isEmpty then "" else s"($params)"}: $ret"

/**
 * TASKS FROM A PROJECT: each pure member of an object whose declaration reads (`Owner.m(a: A, …): R`, at most three
 * parameters, no using, no type parameters) — a delegation, and compositions from templates by R (the number of
 * elements, emptiness, a value present, a Right, a length, the opposite). Impure members (files, processes, the
 * network, time, threads: by the words of their definitions) are left out — their checks would RUN them.
 */
object Tasks:
  /** the top-level parts of a comma-separated list (brackets and parentheses respected) */
  def split(s: String): Vector[String] =
    val out = Vector.newBuilder[String]
    var depth = 0
    var from = 0
    for i <- s.indices do s(i) match
      case '(' | '[' | '{' => depth += 1
      case ')' | ']' | '}' => depth -= 1
      case ',' if depth == 0 => out += s.substring(from, i).trim; from = i + 1
      case _ => ()
    val last = s.substring(from).trim
    if last.nonEmpty then out += last
    out.result()

  private val impureWords = Seq("java.io", "java.nio", "Files.", "ProcessBuilder", "Process(", "sys.process",
    "Runtime", "System.", "Socket", "URL", "HttpClient", "Thread", "Random", "Instant", "LocalDate", "Clock",
    "println", "delete", "readString", "writeString", "createTemp", "Await", "Future")

  /** the member's definition text: its declaration line and what follows, to the next declaration at its indent */
  private def body(p: Project, m: Member): String =
    val f = p.root.resolve(m.file)
    if !java.nio.file.Files.exists(f) then ""
    else
      val lines = java.nio.file.Files.readString(f).linesIterator.toVector
      val i = lines.indexWhere(l => l.matches(s"""\\s*(?:(?:final |override |lazy |inline )*(?:def|val)\\s+)""" +
        java.util.regex.Pattern.quote(m.name) + """\b.*"""))
      if i < 0 then ""
      else
        val ind = lines(i).takeWhile(_ == ' ').length
        (lines(i) +: lines.drop(i + 1).takeWhile(l => l.trim.isEmpty || l.takeWhile(_ == ' ').length > ind))
          .mkString("\n")

  def pure(p: Project, m: Member): Boolean =
    val b = body(p, m)
    b.nonEmpty && !impureWords.exists(b.contains)

  // a lone capital (`A`, `F[_]`): a type parameter of an enclosing scope — not callable from outside
  private val TypeParam = """\b[A-Z]\b""".r
  /** the bracketed (or parenthesized) part at the start of `s`, and what follows it */
  private def group(s: String, open: Char, close: Char): Option[(String, String)] =
    if !s.startsWith(open.toString) then None
    else
      var depth = 0
      val end = s.indices.find { i =>
        if s(i) == open then depth += 1 else if s(i) == close then depth -= 1
        depth == 0
      }
      end.map(e => (s.substring(1, e), s.substring(e + 1)))

  /** a declaration as rendered (`Owner.name[T](a: A): R`, `Owner#name…` for an instance's): its type parameters,
   * its one parameter list (if any), its result type — none for what is not of that shape (curried, no type) */
  def parse(sig: String, m: Member): Option[(Vector[String], Option[String], String)] =
    val head = s"${m.owner}${if m.instance then "#" else "."}${m.name}"
    if !sig.startsWith(head) then None
    else
      val afterName = sig.drop(head.length)
      val (tps, rest1) = group(afterName, '[', ']').map((t, r) => (split(t), r)).getOrElse((Vector.empty, afterName))
      val (ps, rest2) = group(rest1, '(', ')').map((p, r) => (Some(p), r)).getOrElse((None, rest1))
      if rest2.startsWith("(") || !rest2.startsWith(":") then None
      else Some((tps, ps, rest2.drop(1).trim))

  /** concrete types for type parameters of a plain kind: the first Int, then String, Boolean */
  private val concrete = Vector("Int", "String", "Boolean")
  // a plain type parameter, a context bound allowed (`A`, `A: Ordering`); not `F[_]`, not bounded
  private def plain(tp: String) = tp.matches("[A-Za-z_]\\w*(\\s*:\\s*[A-Za-z_][\\w.]*)?")
  private def substitute(text: String, names: Vector[String]): String =
    names.zipWithIndex.foldLeft(text) { case (t, (n, i)) =>
      t.replaceAll(s"\\b${java.util.regex.Pattern.quote(n)}\\b", concrete(i))
    }

  def from(p: Project): Vector[Task] =
    p.members.flatMap { m =>
      p.declaration(m).toVector.flatMap { (sig, doc) =>
        val classTps = if m.instance then p.typeParams(m.owner, m.file) else Some(Vector.empty)
        (parse(sig, m), classTps) match
          case (Some((mtps0, params0, ret0)), Some(ctps)) if pure(p, m) && !p.overloaded(m) && mtps0.forall(plain) &&
              ctps.size + mtps0.size <= concrete.size =>
            val mtps = mtps0.map(_.takeWhile(c => c != ':' && c != ' '))
            val names = ctps ++ mtps
            val own = split(params0.getOrElse("")).map(x => substitute(x, names))
            val receiver =
              if !m.instance then Vector.empty
              else Vector(s"self: ${m.owner}" + (if ctps.isEmpty then "" else concrete.take(ctps.size)
                .mkString("[", ", ", "]")))
            val ps = receiver ++ own
            val params = ps.mkString(", ")
            val ret = substitute(ret0, names)
            val ok = !params.contains("using") && !params.contains("=>") && !ret.contains("=>") && !ret.contains("!") &&
              ps.size <= 3 && ps.forall(x => x.contains(":") && !x.contains("=") && !x.contains("*")) &&
              ret.nonEmpty && !ret.contains("Unit") && !TypeParam.findFirstIn(params + " " + ret).isDefined
            if !ok then Vector.empty
            else
              val n = m.name
              val args = own.map(_.takeWhile(_ != ':').trim).mkString(", ")
              val targs = if mtps.isEmpty then "" else concrete.slice(ctps.size, names.size).mkString("[", ", ", "]")
              val target = if m.instance then s"self.$n" else s"${m.owner}.$n"
              val call = s"$target$targs${if params0.isEmpty then "" else s"($args)"}"
              val shown = s"${m.owner}${if m.instance then "#" else "."}$n"
              val what = (if doc.nonEmpty then s" — $doc" else "") +
                (if m.instance then s" (called on a ${m.owner}: take it as `self`)" else "")
              val cap = s"${n.head.toUpper}${n.tail}"
              def task(kind: String, name: String, r: String, text: String, expr: String) =
                val head = s"def $name${if params.isEmpty then "" else s"($params)"}: $r"
                Task(s"${m.pkg}.$shown:$kind", m, kind, name, params, r, text, s"$head = $expr")
              val base = task("call", s"do$cap", ret,
                s"`$shown`$what. Write the definition with it, returning what it returns.", call)
              val col = """(List|Vector|Seq|Set|Map|IndexedSeq)\[.*""".r
              val more = ret match
                case col(_) => Vector(
                  task("size", s"${n}Count", "Int", s"`$shown`$what. Write the definition returning " +
                    "the number of elements it returns.", s"$call.size"),
                  task("empty", s"${n}Empty", "Boolean", s"`$shown`$what. Write the definition " +
                    "returning whether what it returns is empty.", s"$call.isEmpty"))
                case r if r.startsWith("Option[") => Vector(
                  task("defined", s"${n}Defined", "Boolean", s"`$shown`$what. Write the definition " +
                    "returning whether it gives a value.", s"$call.isDefined"))
                case r if r.startsWith("Either[") => Vector(
                  task("right", s"${n}Ok", "Boolean", s"`$shown`$what. Write the definition " +
                    "returning whether it succeeds (a Right).", s"$call.isRight"))
                case "String" => Vector(
                  task("length", s"${n}Length", "Int", s"`$shown`$what. Write the definition " +
                    "returning the length of the text it returns.", s"$call.length"))
                case "Boolean" => Vector(
                  task("not", s"not$cap", "Boolean", s"`$shown`$what. Write the definition " +
                    "returning the opposite of what it returns.", s"!$call"))
                case _ => Vector.empty
              base +: more
          case _ => Vector.empty
      }
    }.distinctBy(_.id)
