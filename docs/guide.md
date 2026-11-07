# Guide: writing programs that compile into weights

All code here is in package `okay.inlay` (`import okay.inlay.*`) and is taken from the test suites in
`core/src/test/scala/okay/inlay/`, where it runs. The full reference is [api.md](api.md).

## 1. The shape of a program

A program declares its **input fields**, then a list of **layers**. Each layer has **heads** (attention: read
other positions) and **rules** (the MLP: compute at a position). Running it takes a vector of records — one per
position, each a map from field names to values — and returns the records with every field filled in.

```scala
Program(inputs: Seq[Field], layers: Seq[Layer], maxLen: Int = 64, causal: Boolean = true)
Layer(heads: Seq[Head] = Nil, rules: Seq[Rule] = Nil)
```

- `maxLen` counts the start position (an implicit position 0 the compiler adds).
- `causal = true`: a position sees itself and the earlier ones (a decoder). `false`: every position sees all.
- Fields are **write-once**: an input is given by the embedding, every other field is written by exactly one op,
  and read only in a later layer (or by the rules of the layer whose head wrote it, when that is not a set).

A value of a field at a position is a `Val`:

```scala
Val.C(Some("paid"))   // a Cat's value; Val.C(None) is "none"
Val.B(Some(5))        // a Bits value
Val.F(true)           // a Flag
Val.M(Set("a", "b"))  // a Many (a set)
```

An input left out of a record is none.

## 2. A lookup: read a table by key

Rows hold a key and a price; a later position asks for a key and gets that row's price.

```scala
val skus = Vector("A", "B", "C", "D", "E")
val (key, price, ask, got) = (Cat("key", skus), Cat("price", Vector("7", "10", "25", "40")), Cat("ask", skus),
  Cat("got", Vector("7", "10", "25", "40")))
val p = Program(Seq(key, price, ask), Seq(Layer(heads = Seq(Lookup(Seq(got -> price), Seq(ask), Seq(key))))), 16)
val in = Vector(Map("key" -> c("B"), "price" -> c("25")), Map("key" -> c("A"), "price" -> c("10")),
  Map("ask" -> c("B")), Map("ask" -> c("C")))
val out = Interpret.run(p, in)
assertEquals(out.map(_("got")), Vector(Val.C(None), Val.C(None), c("25"), Val.C(None)))
assertEquals(Forward.run(p, Compile(p), in), out)
// where: def c(v: String) = Val.C(Some(v))
```

`Lookup(copy, query, key)`: position i attends to the positions whose `key` fields equal i's `query` fields, and
copies each `(dst -> src)` pair. The ask for C finds nothing and reads none. Options:

- `latest = true` — several matches: the latest wins (a variable's last assignment, the last marker);
- `all = true` — gather the source value of every match into a `Many` (a set; never inexact);
- `wild = true` — a query field that is none matches any key.

Without `latest` or `all`, several matches that disagree make the reference throw `Inexact`: a join is exact only
on a unique key.

## 3. A count

```scala
val status = Cat("status", Vector("paid", "unpaid"))
val ask = Cat("ask", Vector("paid", "unpaid"))
val count = Cat("count", (0 to 64).map(_.toString).toVector)
val p = Program(Seq(status, ask), Seq(Layer(heads = Seq(Count(count, Seq(ask), Seq(status))))), maxLen = 64)
val w = Compile(p)
val in = statuses.map(s => Map("status" -> Val.C(Some(s)))) :+ Map("ask" -> Val.C(Some("unpaid")))
val ref = Interpret.run(p, in).last("count")
val got = Forward.run(p, w, in).last("count")
```

`Count(dst, query, key)` writes the number of matching positions into `dst`, whose values must be exactly
`"0" … "K"`; counts at or above K are written as K. Inside, the head gives the start 1/(c+1) of the attention and
an MLP ladder of 2K + 1 neurons decodes c (see [api.md](api.md#count)).

## 4. Rules: computing at a position

```scala
val (kind, size, urgent, lane, echo) = (Cat("kind", Vector("bug", "task", "idea")), Cat("size", Vector("s", "l")),
  Flag("urgent"), Cat("lane", Vector("now", "soon", "later")), Cat("echo", Vector("s", "l")))
val p = Program(Seq(kind, size, urgent), Seq(Layer(rules = Seq(
  Rule(Seq(Guard.On(urgent)), Seq(Write.To(lane, "now"), Write.Copy(echo, size))),
  Rule(Seq(Guard.Off(urgent), Guard.Is(kind, "bug")), Seq(Write.To(lane, "soon"))),
  Rule(Seq(Guard.Off(urgent), Guard.IsNot(kind, "bug"), Guard.Is(size, "s")), Seq(Write.To(lane, "soon"))),
  Rule(Seq(Guard.Off(urgent), Guard.IsNot(kind, "bug"), Guard.Is(size, "l")), Seq(Write.To(lane, "later")))))), 16)
```

A `Rule(guards, writes)` fires when every guard holds (and never at the start). Guards: `Is`/`IsNot` on a `Cat`,
`On`/`Off` on a `Flag`, `In`/`NotIn` on a `Many`. Writes: `To(cat, value)`, `Raise(flag)`, `Copy(dst, src)` (Cat to
Cat with the same values, or Bits to Bits of the same width), `Union(dst, srcs)` (the union of sets, no guards). A
field no firing rule writes is none; two firing rules writing one field make the reference throw `Inexact`. Each
rule compiles into AND-neurons: one per constant written, one per value of a copied `Cat`, two per bit of copied
`Bits`.

## 5. Positions and the previous token

The embedding can fill position fields itself — `Builtin.pos(bits)` (the position, 1-based), `Builtin.prev(bits)`
(`pos − 1`), `Builtin.back(d, bits)` (`pos − d`). With them a head reads the previous token, or the token d back:
a `Lookup` from `prev` to `pos`. `LawSuite`'s "previous token" and "induction" tests use them; `Quotes` builds its
windows from `back1 … backk`.

## 6. Checking a program before compiling

`p.problems` lists everything wrong with a program, and `p.checked` (called by `Compile` and `Interpret`) refuses
one with problems:

- a field read before it is written, or written twice;
- a head whose query and key differ in size or cannot be compared (Cat~Cat with the same values, Bits~Bits of the
  same width, a `Many` query against a `Cat` key);
- a `Count` whose destination is not `"0" … "K"`;
- builtins with too few bits for `maxLen`; and the rest listed in [api.md](api.md#program).

## 7. The law in your own tests

`LawSuite`'s helper is the pattern: compile once, run both meanings on random inputs, skip the ones the reference
calls `Inexact`, and require the rest equal.

```scala
def law(p: Program, n: Int)(gen: => Vector[Interpret.Record]): Unit =
  val w = Compile(p)
  var exact = 0
  for _ <- 1 to n do
    val in = gen
    try
      val ref = Interpret.run(p, in)
      exact += 1
      assertEquals(Forward.run(p, w, in), ref)
    catch case _: Inexact => ()
  assert(exact >= n / 2)
```

## 8. Bigger programs in the library

Two programs in the core are worth reading as examples of the style:

- **`Closure`** (`Closure.scala`) — transitive closure over sentences "X is above Y" with a non-causal program:
  edges from sentences, then rounds of gathering heads (`all = true`, a set query against a key) and `Union` rules;
  2^rounds hops in 2 + 2·rounds + 2 layers. It answers "is a above b", which sentences the answer rests on, and
  which sentences lie on a cycle. okay-desk runs it in the browser for Datalog reachability queries.
- **`Quotes`** (`Quotes.scala`) — exact quotes: inside `[ … ]` the next token may only continue a span of the
  source. `window` (2k heads), `cheap` (doubling plus a `Count`: log₂k + 4 heads) and `cheapBits` (tokens as
  `Bits`) — the last one runs beside Qwen3-4B as a logits mask ([modules.md](modules.md#mlx)).

## 9. Running on a GPU

`okay.inlay.gpu.OnMlx` (module `mlx`) runs the same `Weights` as MLX matrices on Apple's GPU, with the same law:

```scala
OnMlx.run(p, Compile(p), inputs) == Interpret.run(p, inputs)
```

Keep float32: in half precision the `latest` heads are not exact (see [concepts.md](concepts.md)).
