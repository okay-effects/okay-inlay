var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
//> using scala 3.7.3
// one attention head reading an external table: exact lookup by softmax

// residual stream of the query token: [A B C D | one | price]
val skus = Vector("A", "B", "C", "D")
def token(sku: String): Array[Float] =
  val x = Array.fill(6)(0f); x(skus.indexOf(sku)) = 1f; x(4) = 1f; x

// table rows in the memory's own space: [A B C D | one | value]
case class Row(key: String, value: Float)
def row(r: Row): Array[Float] =
  val m = Array.fill(6)(0f); if r.key != "null" then m(skus.indexOf(r.key)) = 1f else m(4) = 0.5f; m(5) = r.value; m

def dot(a: Array[Float], b: Array[Float]) = a.indices.map(i => a(i) * b(i)).sum

// W_Q: query = (sku, one) scaled by lambda; W_K: key = (sku, one); W_V: value = dim 5; W_O: write to price (dim 5)
def head(x: Array[Float], table: Seq[Row], lambda: Float, pred: (Int, Int) => Boolean): (Seq[Float], Float) =
  val rows = (Row("null", 0f) +: table).map(row)
  val scores = rows.map { m =>
    val k = m.indexWhere(_ == 1f)
    val q = x.indexWhere(_ == 1f)
    if m(4) == 0.5f then lambda * 0.5f                    // the default row: always half
    else if pred(q, k) then lambda else 0f                 // W_QK = lambda * predicate table
  }
  val mx = scores.max
  val e = scores.map(s => math.exp((s - mx).toDouble).toFloat)
  val w = e.map(_ / e.sum)
  (w, w.zip(rows).map((wi, m) => wi * m(5)).sum)

val table = Seq(Row("A", 10), Row("B", 25), Row("C", 7), Row("D", 40))
val eq = (q: Int, k: Int) => q == k
for lambda <- Seq(1f, 4f, 10f, 30f, 250f) do
  val (w, out) = head(token("B"), table, lambda, eq)
  println(f"lambda=$lambda%5.0f weights=${w.map(v => f"$v%.6g").mkString("[", ", ", "]")} price=$out%.7f")
println("--- no match (sku=B, table without B)")
for lambda <- Seq(10f, 250f) do
  val (w, out) = head(token("B"), table.filterNot(_.key == "B"), lambda, eq)
  println(f"lambda=$lambda%5.0f weights=${w.map(v => f"$v%.6g").mkString("[", ", ", "]")} price=$out%.7f")
println("--- duplicate key B: 25 and 35")
println(head(token("B"), table :+ Row("B", 35), 250f, eq))
println("--- predicate key < query (sku=C): rows A,B")
println(head(token("C"), table, 250f, (q, k) => k < q))
println(f"float32 exp(-125) = ${math.exp(-125).toFloat}, exp(-100) = ${math.exp(-100).toFloat}")
// ---- checks
check(head(token("B"), table, 250f, eq)._2 == 25f, "lookup B = 25, exactly")
check(head(token("B"), table.filterNot(_.key == "B"), 250f, eq)._2 == 0f, "a missing key reads 0")
check(head(token("B"), table :+ Row("B", 35), 250f, eq)._2 == 30f, "a duplicate key: AVG 30")
check(head(token("C"), table, 250f, (q, k) => k < q)._2 == 17.5f, "a range: AVG 17.5")
if failures > 0 then sys.exit(1) else println("all checks passed")
