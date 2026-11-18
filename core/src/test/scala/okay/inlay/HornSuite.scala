package okay.inlay

class HornSuite extends munit.FunSuite:
  test("compiled conjunction, alternative derivations and multi-hop proof equal hand-computed results"):
    val rules = Vector(
      Horn.Rule("r1", Vector("a"), "b"),
      Horn.Rule("r2", Vector("b", "c"), "d"),
      Horn.Rule("r3", Vector("a", "c"), "b"),
      Horn.Rule("r4", Vector("d"), "e")
    )
    val block = Horn.Block(Vector("a", "b", "c", "d", "e", "unknown"), rules)
    val result = block.run(Set("a", "c"))
    assertEquals(result.known, Set("a", "b", "c", "d", "e"))
    assertEquals(result, block.run(Set("a", "c"), compiled = false))
    assertEquals(result.proof("e").map(_.rule), Vector("r1", "r2", "r4"))
    assert(!block.run(Set("a")).known("d"))

  test("cycles do not invent evidence, and contradictory signed atoms do not imply arbitrary conclusions"):
    val block = Horn.Block(
      Vector("a", "not-a", "b", "unrelated"),
      Vector(Horn.Rule("ab", Vector("a"), "b"), Horn.Rule("ba", Vector("b"), "a"))
    )
    assertEquals(block.run(Set.empty).known, Set.empty[String])
    assertEquals(block.run(Set("a", "not-a")).known, Set("a", "not-a", "b"))

  test("compiled rounds equal an independent least-fixed-point evaluator on bounded random programs"):
    val rnd = scala.util.Random(17)
    for _ <- 0 until 100 do
      val atoms = Vector.tabulate(8)(i => s"a$i")
      val rules = Vector.tabulate(12)(i =>
        Horn.Rule(s"r$i", rnd.shuffle(atoms).take(1 + rnd.nextInt(3)), atoms(rnd.nextInt(atoms.size)))
      )
      val facts = atoms.filter(_ => rnd.nextBoolean()).toSet
      var expected = facts
      var more = true
      while more do
        val next = expected ++ rules.filter(_.premises.forall(expected)).map(_.conclusion)
        more = next != expected
        expected = next
      val block = Horn.Block(atoms, rules)
      assertEquals(block.run(facts).known, expected)
      assertEquals(block.run(facts), block.run(facts, compiled = false))

  test("capacity errors and undeclared atoms are refused before compilation"):
    intercept[IllegalArgumentException](Horn.Block(Vector("a"), Vector(Horn.Rule("r", Vector("b"), "a"))))
    intercept[IllegalArgumentException](Horn.Block(Vector.fill(2)("a"), Vector.empty))
    intercept[IllegalArgumentException](Horn.Block(Vector("a"), Vector.empty).run(Set("b")))
