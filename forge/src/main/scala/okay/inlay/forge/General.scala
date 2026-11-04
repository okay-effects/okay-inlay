package okay.inlay.forge

/**
 * THE REGRESSION SET: general Scala tasks, no project's API — each stated exactly (what an edge case gives) and with
 * its reference; checked as any task. What a model trained for a project must not lose.
 */
object General:
  private val none = Member("scala", "", "", "")
  private def t(name: String, params: String, ret: String, text: String, body: String) =
    Task(s"general.$name", none, "general", name, params, ret, text,
      s"def $name${if params.isEmpty then "" else s"($params)"}: $ret = $body")

  val tasks: Vector[Task] = Vector(
    t("reverseWords", "s: String", "String", "the words of s (split by spaces, empty ones dropped) in reverse order, " +
      "joined by single spaces", """s.split(" ").filter(_.nonEmpty).reverse.mkString(" ")"""),
    t("isPalindrome", "s: String", "Boolean", "whether s, lower-cased and with only its letters and digits kept, reads " +
      "the same backwards", "{ val t = s.toLowerCase.filter(_.isLetterOrDigit); t == t.reverse }"),
    t("fizz", "n: Int", "String", "\"FizzBuzz\" if n is divisible by 15, \"Fizz\" by 3, \"Buzz\" by 5, else n as text",
      """if n % 15 == 0 then "FizzBuzz" else if n % 3 == 0 then "Fizz" else if n % 5 == 0 then "Buzz" else n.toString"""),
    t("countVowels", "s: String", "Int", "how many characters of s are vowels (a, e, i, o, u, either case)",
      """s.count("aeiouAEIOU".contains(_))"""),
    t("secondLargest", "xs: List[Int]", "Option[Int]", "the second largest DISTINCT value of xs, if there is one",
      "xs.distinct.sorted.reverse.drop(1).headOption"),
    t("mergeSorted", "a: List[Int], b: List[Int]", "List[Int]", "all elements of a and b in one ascending list",
      "(a ++ b).sorted"),
    t("wordFreq", "s: String", "Map[String, Int]", "how often each word of s occurs (words split by spaces, empty ones " +
      "dropped)", """s.split(" ").filter(_.nonEmpty).groupBy(identity).view.mapValues(_.length).toMap"""),
    t("chunk", "xs: List[Int], n: Int", "List[List[Int]]", "xs cut into consecutive groups of n (the last may be " +
      "shorter); no groups at all when n is not positive", "if n <= 0 then Nil else xs.grouped(n).toList"),
    t("gcd", "a: Int, b: Int", "Long", "the greatest common divisor of a and b, non-negative (0 for 0 and 0)",
      "BigInt(a).gcd(BigInt(b)).toLong"),
    t("digitSum", "n: Int", "Int", "the sum of the decimal digits of n's absolute value",
      "math.abs(n.toLong).toString.map(_.asDigit).sum"),
    t("dedupe", "xs: List[Int]", "List[Int]", "xs with each value kept at its first occurrence only", "xs.distinct"),
    t("isSorted", "xs: List[Int]", "Boolean", "whether xs is in non-decreasing order",
      "xs.zip(xs.drop(1)).forall((a, b) => a <= b)"),
    t("rotate", "xs: List[Int], k: Int", "List[Int]", "xs rotated left by k positions (k may be negative or larger " +
      "than the length); an empty list stays empty",
      "if xs.isEmpty then xs else { val m = ((k % xs.size) + xs.size) % xs.size; xs.drop(m) ++ xs.take(m) }"),
    t("parseInts", "s: String", "List[Int]", "the integers in s, separated by commas (each trimmed); a part that is " +
      "not an integer skipped", """s.split(",").toList.flatMap(_.trim.toIntOption)"""),
    t("safeDiv", "a: Int, b: Int", "Option[Int]", "a divided by b (integer division), none when b is 0",
      "if b == 0 then None else Some(a / b)"),
    t("initials", "name: String", "String", "the upper-cased first letter of each word of name (split by spaces, " +
      "empty ones dropped)", """name.split(" ").filter(_.nonEmpty).map(_.head.toUpper).mkString"""),
    t("sumOfSquares", "xs: List[Int]", "Long", "the sum of the squares of xs, as a Long",
      "xs.map(x => x.toLong * x).sum"),
    t("longestRun", "xs: List[Int]", "Int", "the length of the longest run of equal consecutive elements (0 for none)",
      "if xs.isEmpty then 0 else xs.foldLeft((0, 0, Option.empty[Int])) { case ((best, cur, prev), x) => " +
        "val c = if prev.contains(x) then cur + 1 else 1; (best max c, c, Some(x)) }._1"),
    t("countWhere", "xs: List[Int], k: Int", "Int", "how many elements of xs are greater than k", "xs.count(_ > k)"),
    t("titleCase", "s: String", "String", "s with the first character of each space-separated part upper-cased " +
      "(spaces kept as they are)", """s.split(" ", -1).map(_.capitalize).mkString(" ")"""))
