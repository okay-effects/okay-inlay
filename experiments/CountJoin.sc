var failures = 0
def check(ok: Boolean, what: String): Unit = if !ok then { failures += 1; println(s"FAILED: $what") }
// COUNT via selector width (attention + MLP) and JOIN via two heads, float32
val skus = Vector("A", "B", "C", "D")
// a row: key index (-1 = the null row), value vector
case class Row(key: Int, value: Vector[Float])

// one head: score = lambda * pred for real rows, lambda * nullScore for the null row; softmax; weighted values
def head(q: Vector[Float], rows: Seq[Row], lambda: Float, nullScore: Float,
         pred: (Int, Int) => Boolean): (Seq[Float], Vector[Float]) =
  val scores = rows.map { r =>
    if r.key < 0 then lambda * nullScore
    else lambda * skus.indices.map(i => if pred(i, r.key) then q(i) else 0f).sum
  }
  val mx = scores.max
  val e = scores.map(s => math.exp((s - mx).toDouble).toFloat)
  val w = e.map(_ / e.sum)
  (w, rows.zip(w).map((r, wi) => r.value.map(_ * wi)).reduce((a, b) => a.zip(b).map(_ + _)))

def onehot(i: Int, n: Int = 4) = Vector.tabulate(n)(j => if j == i then 1f else 0f)
val relu = (x: Float) => math.max(0f, x)

// ---- COUNT: the null row scores the same as a match, carries 1 in its value; weight on it = 1/(k+1)
val maxRows = 8
// the MLP: 1/(k+1) -> k; per threshold t_k (between 1/(k+1) and 1/(k+2)) two ReLUs make a step
val ts = (0 until maxRows).map(k => ((1.0 / (k + 1) + 1.0 / (k + 2)) / 2).toFloat)
val steep = 4f * (maxRows + 2) * (maxRows + 2)
def mlp(y: Float): (Int, Float) =
  val hidden = ts.flatMap(t => Seq(relu(steep * (y - t)), relu(steep * (y - t) - 1)))
  val count = ts.indices.map(k => 1f - (hidden(2 * k) - hidden(2 * k + 1))).sum
  (hidden.size, count)

val orders = Seq("A", "B", "B", "C", "B", "D").map(s => Row(skus.indexOf(s), Vector(0f)))
val countRows = Row(-1, Vector(1f)) +: orders
def count(q: String, pred: (Int, Int) => Boolean, lambda: Float = 250f) =
  val (w, out) = head(onehot(skus.indexOf(q)), countRows, lambda, 1f, pred)
  val (neurons, c) = mlp(out(0))
  println(f"COUNT WHERE sku ${if pred(1, 1) then "=" else "<"} $q%s: weights=${w.map(v => f"$v%.3g").mkString("[", ",", "]")}" +
    f" y=${out(0)}%.6f  mlp($neurons neurons) -> $c%.4f")
count("B", _ == _)
count("C", (q, k) => k < q)
count("A", (q, k) => k < q)
count("D", _ == _)
println("-- lambda=10 (not yet hard):"); count("B", _ == _, 10f)

// ---- JOIN: orders(orderId -> sku), prices(sku -> price); head 1 copies sku as a one-hot, head 2 looks it up
val ids = Vector("o1", "o2", "o3", "o4")
def idHot(id: String) = onehot(ids.indexOf(id))
val ordersById = Seq("o1" -> "C", "o2" -> "B", "o3" -> "D")
def orderRows(t: Seq[(String, String)]) =
  Row(-1, Vector.fill(4)(0f)) +: t.map((o, s) => Row(ids.indexOf(o), onehot(skus.indexOf(s))))
val priceRows = Row(-1, Vector(0f)) +: Seq("A" -> 10f, "B" -> 25f, "C" -> 7f, "D" -> 40f)
  .map((s, p) => Row(skus.indexOf(s), Vector(p)))
def join(order: String, t: Seq[(String, String)] = ordersById) =
  val (_, sku) = head(idHot(order), orderRows(t), 250f, 0.5f, _ == _)       // layer 1: attention
  val (_, price) = head(sku, priceRows, 250f, 0.5f, _ == _)                 // layer 2: attention
  println(f"order $order: sku=${sku.mkString("[", ",", "]")} price=${price(0)}")
join("o1"); join("o2"); join("o4")
println("-- orderId not unique: o2 -> B and o2 -> D")
join("o2", ordersById :+ ("o2" -> "D"))
// ---- checks
def countOf(q: String, pred: (Int, Int) => Boolean, l: Float = 250f) =
  mlp(head(onehot(skus.indexOf(q)), countRows, l, 1f, pred)._2(0))._2
def near(a: Float, b: Float) = math.abs(a - b) < 1e-4
check(near(countOf("B", _ == _), 3), "COUNT sku = B is 3")
check(near(countOf("C", (q, k) => k < q), 4), "COUNT sku < C is 4")
check(near(countOf("A", (q, k) => k < q), 0), "COUNT sku < A is 0")
check(near(countOf("B", _ == _, 10f), 3), "COUNT at lambda 10 still 3 (the ladder corrects)")
def joinPrice(order: String, t: Seq[(String, String)] = ordersById) =
  head(head(idHot(order), orderRows(t), 250f, 0.5f, _ == _)._2, priceRows, 250f, 0.5f, _ == _)._2(0)
check(joinPrice("o1") == 7f && joinPrice("o2") == 25f && joinPrice("o4") == 0f, "JOIN o1 7, o2 25, o4 missing 0")
check(!Set(25f, 40f).contains(joinPrice("o2", ordersById :+ ("o2" -> "D"))), "a non-unique key breaks the JOIN (expected)")
if failures > 0 then sys.exit(1) else println("all checks passed")
