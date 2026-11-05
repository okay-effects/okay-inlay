package okay.inlay.forge

/** a task that asks for MEANING: several members, or logic around one, edges stated; the members it needs shown */
final case class MeaningTask(project: String, uses: Vector[(String, String)], task: Task)

/**
 * THE EVALUATION OF MEANING (specs/forge.md): tasks written by hand over the members the generators cover — a
 * composition, a filter, an edge handled, a failure turned into a value — each with its reference, checked by the
 * verifier like any task. What the generated delegations do not teach; what an adapter must not only seem to learn.
 */
object Meaning:
  private def t(project: String, pkg: String, uses: Vector[(String, String)], name: String, params: String,
      ret: String, text: String, body: String): MeaningTask =
    val (owner, member) = uses.head
    val m = Member(pkg, owner, member, "")
    MeaningTask(project, uses, Task(s"meaning.$project.$name", m, "meaning", name, params, ret, text,
      s"def $name${if params.isEmpty then "" else s"($params)"}: $ret = $body"))

  private def symdex(uses: Vector[(String, String)], name: String, params: String, ret: String, text: String,
      body: String) = t("symdex", "symdex", uses, name, params, ret, text, body)
  private def store(uses: Vector[(String, String)], name: String, params: String, ret: String, text: String,
      body: String) = t("okay-store", "okay.store", uses, name, params, ret, text, body)
  private def proof(uses: Vector[(String, String)], name: String, params: String, ret: String, text: String,
      body: String) = t("okay-proof", "okay.proof", uses, name, params, ret, text, body)
  private def guard(uses: Vector[(String, String)], name: String, params: String, ret: String, text: String,
      body: String, pkg: String = "okay.guard") = t("okay-guard", pkg, uses, name, params, ret, text, body)

  val tasks: Vector[MeaningTask] = Vector(
    // symdex
    symdex(Vector("Tools" -> "tokens"), "tokensWithoutSpaces", "s: String", "Int",
      "the token estimate (`Tools.tokens`) of s with every space removed", """Tools.tokens(s.filterNot(_ == ' '))"""),
    symdex(Vector("Symbols" -> "isLocal", "Symbols" -> "name"), "plainNames", "symbols: List[String]", "List[String]",
      "the plain name (`Symbols.name`) of each symbol that is not local (`Symbols.isLocal`), in their order",
      "symbols.filterNot(Symbols.isLocal).map(Symbols.name)"),
    symdex(Vector("Symbols" -> "names"), "depth", "symbol: String", "Int",
      "how many descriptor names a symbol has (`Symbols.names`)", "Symbols.names(symbol).size"),
    symdex(Vector("Symbols" -> "names"), "outermost", "symbol: String", "Option[String]",
      "the outermost descriptor name of a symbol, none when it has none", "Symbols.names(symbol).headOption"),
    symdex(Vector("Symbols" -> "show"), "readSame", "a: String, b: String", "Boolean",
      "whether two symbols read the same in readable form (`Symbols.show`)", "Symbols.show(a) == Symbols.show(b)"),
    symdex(Vector("Equivalence" -> "verdict"), "foundWhere", "out: String", "Option[String]",
      "when a comparison's output reads (`Equivalence.verdict`) and says NOT equivalent, where the difference was " +
        "found (its `found`); none otherwise",
      "Equivalence.verdict(out).toOption.filter(v => !v.equivalent).map(_.found)"),
    symdex(Vector("Equivalence" -> "header"), "importCount", "text: String", "Int",
      "how many of a file's header lines (`Equivalence.header`) are imports (start with \"import \")",
      """Equivalence.header(text).count(_.startsWith("import "))"""),
    symdex(Vector("Archive" -> "id"), "distinctIds", "texts: List[String]", "Set[String]",
      "the distinct archive ids (`Archive.id`) of the texts", "texts.map(Archive.id).toSet"),
    symdex(Vector("Hook" -> "allowed"), "allAllowed", "commands: List[String]", "Boolean",
      "whether the hook allows every command (`Hook.allowed`); true for no commands", "commands.forall(Hook.allowed)"),
    symdex(Vector("Hook" -> "allowed"), "firstRefused", "commands: List[String]", "Option[String]",
      "the first command the hook does not allow, if any", "commands.find(c => !Hook.allowed(c))"),
    symdex(Vector("CodeFacts" -> "seeded"), "seededAmong", "names: List[String]", "List[String]",
      "the names that are seeded relations (`CodeFacts.seeded`), without repeats, sorted",
      "names.filter(CodeFacts.seeded).distinct.sorted"),
    symdex(Vector("Scip" -> "descriptor"), "descriptorLength", "symbol: String", "Int",
      "the length of a SCIP symbol's descriptor (`Scip.descriptor`)", "Scip.descriptor(symbol).length"),
    // okay-store
    store(Vector("Where" -> "leaves"), "leafCount", "v: Json", "Int",
      "how many leaves (`Where.leaves`) a JSON value has", "Where.leaves(v).size"),
    store(Vector("Where" -> "leaves"), "leavesJoined", "v: Json", "String",
      "the leaves' texts (`Where.leaves`) joined by commas", """Where.leaves(v).mkString(",")"""),
    store(Vector("Where" -> "at"), "textAt", "doc: Json, path: List[String]", "Option[String]",
      "the text at a path (`Where.at`) when the value there is a string; none otherwise",
      "Where.at(doc, path).collect { case Json.Str(s) => s }"),
    store(Vector("Where" -> "at"), "present", "doc: Json, path: List[String]", "Boolean",
      "whether the document has a value at the path (`Where.at`) that is not null",
      "Where.at(doc, path).exists(_ != Json.Null)"),
    store(Vector("Where" -> "ordered"), "before", "a: Json, b: Json", "Boolean",
      "whether a orders strictly before b (`Where.ordered` gives a negative number); false when they do not compare",
      "Where.ordered(a, b).exists(_ < 0)"),
    store(Vector("Where" -> "ordered"), "comparable", "a: Json, b: Json", "Boolean",
      "whether two values can be ordered at all (`Where.ordered`)", "Where.ordered(a, b).isDefined"),
    store(Vector("Data" -> "key"), "keyLength", "n: Long", "Int",
      "the length of the key the store gives the number n (`Data.key`)", "Data.key(n).length"),
    store(Vector("Dialect" -> "jsonPath", "Dialect" -> "pgJsonPath"), "bothPaths", "path: List[String]",
      "(String, String)", "SQLite's JSON path (`Dialect.jsonPath`) and Postgres's (`Dialect.pgJsonPath`) for a path",
      "(Dialect.jsonPath(path), Dialect.pgJsonPath(path))"),
    store(Vector("Dialect" -> "quoted"), "quotedOrNone", "name: String", "Option[String]",
      "the identifier quoted (`Dialect.quoted`); none when it is refused (it throws)",
      "scala.util.Try(Dialect.quoted(name)).toOption"),
    store(Vector("SqlData" -> "doc"), "docOrNull", "text: String", "Json",
      "the document read back from its text (`SqlData.doc`); `Json.Null` when it does not read (it throws)",
      "scala.util.Try(SqlData.doc(text)).getOrElse(Json.Null)"),
    store(Vector("Where" -> "compare"), "compareSign", "a: Option[Json], b: Option[Json]", "Int",
      "the sign (-1, 0 or 1) of `Where.compare`", "Integer.signum(Where.compare(a, b))"),
    store(Vector("Data" -> "named", "SqlData" -> "tableOf"), "tableOfNamed", "collection: String", "Option[String]",
      "the table (`SqlData.tableOf`) of a collection's name (`Data.named`); none when either throws",
      "scala.util.Try(SqlData.tableOf(Data.named(collection))).toOption"),
    // okay-proof
    proof(Vector("Logic" -> "wellSorted"), "bothWellSorted", "a: Formula, b: Formula", "Boolean",
      "whether both formulas are well-sorted (`Logic.wellSorted`)", "Logic.wellSorted(a) && Logic.wellSorted(b)"),
    proof(Vector("Arithmetic" -> "truth"), "truthOr", "f: Formula, otherwise: Boolean", "Boolean",
      "a formula's truth (`Arithmetic.truth`), or `otherwise` when it is not computable",
      "Arithmetic.truth(f).getOrElse(otherwise)"),
    proof(Vector("Logic" -> "sortOf", "Logic" -> "sortKey"), "sortName", "t: Term", "String",
      "the key (`Logic.sortKey`) of a term's sort (`Logic.sortOf`); \"none\" when it has none",
      """Logic.sortOf(t).map(Logic.sortKey).getOrElse("none")"""),
    proof(Vector("Logic" -> "alphaEq"), "renamedOnly", "a: Formula, b: Formula", "Boolean",
      "whether two formulas are equal up to bound variables' names (`Logic.alphaEq`) but not equal as they are",
      "Logic.alphaEq(a, b) && a != b"),
    proof(Vector("Linear" -> "lin"), "isLinear", "t: Term", "Boolean",
      "whether a term has a linear form (`Linear.lin`)", "Linear.lin(t).isDefined"),
    proof(Vector("syntax" -> "int"), "intKey", "n: BigInt", "String",
      "the key of the integer term for n (`syntax.int`; a term's `key`)", "syntax.int(n).key"),
    proof(Vector("syntax" -> "str"), "strTerms", "xs: List[String]", "List[Term]",
      "a string term (`syntax.str`) for each text that is not empty, in order",
      "xs.filter(_.nonEmpty).map(syntax.str)"),
    proof(Vector("Json" -> "parse"), "fieldOf", "text: String, k: String", "Option[Json]",
      "the member k of the JSON the text parses to (`Json.parse`, then `get`); none when it does not parse or has none",
      "Json.parse(text).toOption.flatMap(_.get(k))"),
    // okay-guard
    guard(Vector("Markdown" -> "anchor"), "anchors", "headings: List[String]", "List[String]",
      "each heading's anchor (`Markdown.anchor`), repeats dropped, in order", "headings.map(Markdown.anchor).distinct"),
    guard(Vector("Days" -> "day"), "sameDay", "a: Long, b: Long", "Boolean",
      "whether two instants (milliseconds) fall on the same day (`Days.day`)", "Days.day(a) == Days.day(b)"),
    guard(Vector("PolicyText" -> "quote"), "quotedList", "xs: List[String]", "String",
      "each text quoted (`PolicyText.quote`), joined by \", \"", """xs.map(PolicyText.quote).mkString(", ")"""),
    guard(Vector("PolicyText" -> "defaultOf"), "denies", "text: String", "Boolean",
      "whether a policy's default (`PolicyText.defaultOf`) is \"deny\"", """PolicyText.defaultOf(text) == "deny""""),
    guard(Vector("Sha256" -> "hex"), "hexPrefix", "text: String, n: Int", "String",
      "the first n characters of the text's SHA-256 in hex (`Sha256.hex`); empty when n is not positive",
      "Sha256.hex(text).take(n)"),
    guard(Vector("Markdown" -> "href"), "pages", "urls: List[String]", "List[String]",
      "the page each link goes to (`Markdown.href`), for the links that end with \".md\"",
      """urls.filter(_.endsWith(".md")).map(Markdown.href)"""),
    guard(Vector("PolicyText" -> "remove"), "withoutFirstLine", "text: String", "String",
      "the text with its first line taken out (`PolicyText.remove`, lines counted from 1)",
      "PolicyText.remove(text, 1, 1)"),
    guard(Vector("SymdexFacts" -> "relative"), "relativeAll", "root: String, paths: List[String]", "List[String]",
      "each path as symdex names a file, relative to the root (`SymdexFacts.relative`)",
      "paths.map(SymdexFacts.relative(root, _))", pkg = "okay.guard.policy"))
