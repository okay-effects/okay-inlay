package okay.inlay

import Interpret.Record

/** THE LAW (specs/dsl.md): the compiled weights, run, decode to what the reference says — on random inputs, for
 * every program; and the checks refuse what cannot be compiled */
class LawSuite extends munit.FunSuite:
  private val r = scala.util.Random(42)

  /** the law on `n` random inputs; inputs the reference finds inexact skipped (at most half) */
  private def law(p: Program, n: Int)(gen: => Vector[Record]): Unit =
    val w = Compile(p)
    var exact = 0
    for _ <- 1 to n do
      val in = gen
      val ref = try Some(Interpret.run(p, in)) catch case _: Inexact => None
      ref.foreach { want =>
        exact += 1
        val got = Forward.run(p, w, in)
        assertEquals(got, want, s"inputs: $in")
      }
    assert(exact * 2 >= n, s"only $exact of $n inputs exact")

  private def c(v: String) = Val.C(Some(v))
  private def len(max: Int) = 1 + r.nextInt(max)
  private def pick[A](xs: Seq[A]) = xs(r.nextInt(xs.size))

  test("a table lookup: a query reads the price of an earlier row with its key; none for a missing key"):
    val skus = Vector("A", "B", "C", "D", "E")
    val (key, price, ask, got) = (Cat("key", skus), Cat("price", Vector("7", "10", "25", "40")), Cat("ask", skus),
      Cat("got", Vector("7", "10", "25", "40")))
    val p = Program(Seq(key, price, ask), Seq(Layer(heads = Seq(Lookup(Seq(got -> price), Seq(ask), Seq(key))))), 16)
    val in = Vector(Map("key" -> c("B"), "price" -> c("25")), Map("key" -> c("A"), "price" -> c("10")),
      Map("ask" -> c("B")), Map("ask" -> c("C")))
    val out = Interpret.run(p, in)
    assertEquals(out.map(_("got")), Vector(Val.C(None), Val.C(None), c("25"), Val.C(None)))
    assertEquals(Forward.run(p, Compile(p), in), out)
    law(p, 300) {
      // rows of distinct keys, then queries
      val rows = r.shuffle(skus).take(r.nextInt(4)).map(k => Map("key" -> c(k), "price" -> c(pick(price.values))))
      (rows ++ Vector.fill(len(5))(Map("ask" -> c(pick(skus))))).toVector
    }

  private val vocab = Vector("a", "b", "c", "d", "[", "]")
  private val tok = Cat("tok", vocab)
  private val (pos, prev) = (Builtin.pos(5), Builtin.prev(5))
  private def text(n: Int) = Vector.fill(n)(Map[String, Val]("tok" -> c(pick(vocab))))

  test("the previous token: a lookup by position − 1"):
    val ptok = Cat("ptok", vocab)
    val p = Program(Seq(tok, pos, prev), Seq(Layer(heads = Seq(Lookup(Seq(ptok -> tok), Seq(prev), Seq(pos))))), 32)
    val in = text(5)
    assertEquals(Interpret.run(p, in).map(_("ptok")), Val.C(None) +: in.init.map(_("tok")))
    law(p, 200)(text(len(31)))

  test("induction and the latest marker — the quote's core: inside [ ], what a copy would continue with"):
    val ptok = Cat("ptok", vocab)
    val (y, isMark, mark, next, copy) = (Cat("one", Vector("y")), Cat("isMark", Vector("y")), Cat("mark", vocab),
      Cat("next", vocab), Cat("copy", vocab))
    val p = Program(Seq(tok, pos, prev), Seq(
      Layer(heads = Seq(Lookup(Seq(ptok -> tok), Seq(prev), Seq(pos)))),
      Layer(rules = Seq(Rule(Nil, Seq(Write.To(y, "y"))), Rule(Seq(Guard.Is(tok, "[")), Seq(Write.To(isMark, "y"))),
        Rule(Seq(Guard.Is(tok, "]")), Seq(Write.To(isMark, "y"))))),
      Layer(heads = Seq(
        Lookup(Seq(mark -> tok), Seq(y), Seq(isMark), latest = true), // the latest [ or ]
        Lookup(Seq(next -> tok), Seq(tok), Seq(ptok), latest = true))), // after my token, the latest time: what came
      Layer(rules = Seq(Rule(Seq(Guard.Is(mark, "[")), Seq(Write.Copy(copy, next)))))), 32)
    val in = Vector("a", "b", "c", "[", "a", "b").map(t => Map[String, Val]("tok" -> c(t)))
    val out = Interpret.run(p, in)
    // at the last "b", inside [: the latest "b" before followed by... itself here (b's previous is a): "c" came after b
    assertEquals(out.map(_("mark")), Vector(None, None, None, Some("["), Some("["), Some("[")).map(Val.C(_)))
    assertEquals(out.last("copy"), c("c"))
    assertEquals(Forward.run(p, Compile(p), in), out)
    law(p, 300)(text(len(31)))

  test("rules: guards positive and negative, flags, copies — AND-neurons"):
    val (kind, size, urgent, lane, echo) = (Cat("kind", Vector("bug", "task", "idea")), Cat("size", Vector("s", "l")),
      Flag("urgent"), Cat("lane", Vector("now", "soon", "later")), Cat("echo", Vector("s", "l")))
    val p = Program(Seq(kind, size, urgent), Seq(Layer(rules = Seq(
      Rule(Seq(Guard.On(urgent)), Seq(Write.To(lane, "now"), Write.Copy(echo, size))),
      Rule(Seq(Guard.Off(urgent), Guard.Is(kind, "bug")), Seq(Write.To(lane, "soon"))),
      Rule(Seq(Guard.Off(urgent), Guard.IsNot(kind, "bug"), Guard.Is(size, "s")), Seq(Write.To(lane, "soon"))),
      Rule(Seq(Guard.Off(urgent), Guard.IsNot(kind, "bug"), Guard.Is(size, "l")), Seq(Write.To(lane, "later")))))), 16)
    law(p, 300)(Vector.fill(len(15))(Map("kind" -> c(pick(kind.values)), "size" -> c(pick(size.values)),
      "urgent" -> Val.F(r.nextBoolean()))))

  test("the checks: a field written twice, read before written, compared across kinds"):
    val (a, b) = (Cat("a", Vector("x")), Cat("b", Vector("x")))
    val twice = Program(Seq(a), Seq(Layer(rules = Seq(Rule(Nil, Seq(Write.To(b, "x"))))),
      Layer(rules = Seq(Rule(Nil, Seq(Write.To(b, "x")))))))
    assert(twice.problems.exists(_.contains("b written twice")), twice.problems)
    val early = Program(Seq(a), Seq(Layer(rules = Seq(Rule(Seq(Guard.Is(b, "x")), Seq(Write.To(Cat("c", Vector("x")),
      "x")))))))
    assert(early.problems.exists(_.contains("reads b before")), early.problems)
    val kinds = Program(Seq(a, Builtin.pos(6)), Seq(Layer(heads = Seq(Lookup(Seq(b -> a), Seq(a), Seq(Builtin.pos(6)))))))
    assert(kinds.problems.exists(_.contains("cannot be compared")), kinds.problems)
