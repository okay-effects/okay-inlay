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
  private val Sig = """(\w+)\.(\w+)(?:\((.*)\))?: (.+)""".r

  def from(p: Project): Vector[Task] =
    p.members.flatMap { m =>
      p.declaration(m).toVector.flatMap { (sig, doc) =>
        sig match
          case Sig(o, n, params0, ret0) if o == m.owner && n == m.name && pure(p, m) =>
            val params = Option(params0).getOrElse("")
            val ret = ret0.trim
            val ps = split(params)
            val ok = !params.contains("using") && !params.contains("=>") && !ret.contains("=>") && !ret.contains("!") &&
              ps.size <= 3 && ps.forall(x => x.contains(":") && !x.contains("=") && !x.contains("*")) &&
              ret.nonEmpty && !ret.contains("Unit") && !TypeParam.findFirstIn(params + " " + ret).isDefined
            if !ok then Vector.empty
            else
              val args = ps.map(_.takeWhile(_ != ':').trim).mkString(", ")
              val call = if params0 == null then s"${m.owner}.${m.name}" else s"${m.owner}.${m.name}($args)"
              val what = if doc.nonEmpty then s" — $doc" else ""
              val cap = s"${n.head.toUpper}${n.tail}"
              def task(kind: String, name: String, r: String, text: String, expr: String) =
                val head = s"def $name${if params.isEmpty then "" else s"($params)"}: $r"
                Task(s"${m.pkg}.${m.owner}.${m.name}:$kind", m, kind, name, params, r, text, s"$head = $expr")
              val base = task("call", s"do$cap", ret,
                s"`${m.owner}.${m.name}`$what. Write the definition with it, returning what it returns.", call)
              val col = """(List|Vector|Seq|Set|Map|IndexedSeq)\[.*""".r
              val more = ret match
                case col(_) => Vector(
                  task("size", s"${n}Count", "Int", s"`${m.owner}.${m.name}`$what. Write the definition returning " +
                    "the number of elements it returns.", s"$call.size"),
                  task("empty", s"${n}Empty", "Boolean", s"`${m.owner}.${m.name}`$what. Write the definition " +
                    "returning whether what it returns is empty.", s"$call.isEmpty"))
                case r if r.startsWith("Option[") => Vector(
                  task("defined", s"${n}Defined", "Boolean", s"`${m.owner}.${m.name}`$what. Write the definition " +
                    "returning whether it gives a value.", s"$call.isDefined"))
                case r if r.startsWith("Either[") => Vector(
                  task("right", s"${n}Ok", "Boolean", s"`${m.owner}.${m.name}`$what. Write the definition " +
                    "returning whether it succeeds (a Right).", s"$call.isRight"))
                case "String" => Vector(
                  task("length", s"${n}Length", "Int", s"`${m.owner}.${m.name}`$what. Write the definition " +
                    "returning the length of the text it returns.", s"$call.length"))
                case "Boolean" => Vector(
                  task("not", s"not$cap", "Boolean", s"`${m.owner}.${m.name}`$what. Write the definition " +
                    "returning the opposite of what it returns.", s"!$call"))
                case _ => Vector.empty
              base +: more
          case _ => Vector.empty
      }
    }.distinctBy(_.id)
