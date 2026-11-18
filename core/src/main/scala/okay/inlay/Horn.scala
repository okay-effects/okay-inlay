package okay.inlay

/** Bounded propositional Horn inference. A recurrent compiled block computes one simultaneous round; the host only
  * feeds its output back and records the block's fired rules. No negation-as-failure or closed-world default.
  */
object Horn:
  final case class Rule(id: String, premises: Vector[String], conclusion: String)
  final case class Derivation(atom: String, rule: String, premises: Vector[String], round: Int)
  final case class Result(known: Set[String], steps: Vector[Derivation], rounds: Int):
    /** A finite proof, with premises before conclusions. Alternative derivations do not duplicate a fact. */
    def proof(goal: String): Vector[Derivation] =
      val byAtom = steps.map(s => s.atom -> s).toMap
      var visited = Set.empty[String]
      val out = Vector.newBuilder[Derivation]
      def visit(atom: String): Unit = if !visited(atom) then
        visited += atom
        byAtom.get(atom).foreach { step =>
          step.premises.foreach(visit); out += step
        }
      visit(goal)
      out.result()

  val MaxAtoms = 64
  val MaxRules = 64

  final class Block(val atoms: Vector[String], val rules: Vector[Rule]):
    require(atoms.nonEmpty && atoms.size <= MaxAtoms && atoms.distinct == atoms, "distinct bounded Horn atoms")
    require(rules.size <= MaxRules && rules.map(_.id).distinct.size == rules.size, "distinct bounded Horn rules")
    require(
      rules.forall(r =>
        r.id.nonEmpty && r.premises.nonEmpty && r.premises.size <= 8 &&
          r.premises.distinct == r.premises && (r.premises :+ r.conclusion).forall(atoms.contains)
      ),
      "a Horn rule needs declared atoms and one to eight distinct premises"
    )

    val known = Many("known", atoms)
    val next = Many("next", atoms)
    val fired: Vector[Cat] = rules.indices.map(i => Cat(s"fired$i", atoms)).toVector
    val program = Program(
      Seq(known),
      Seq(
        Layer(rules = rules.zip(fired).map { (rule, out) =>
          okay.inlay.Rule(rule.premises.map(Guard.In(known, _)), Seq(Write.To(out, rule.conclusion)))
        }),
        Layer(rules = Seq(okay.inlay.Rule(Nil, Seq(Write.Union(next, known +: fired)))))
      ),
      maxLen = 2
    )
    lazy val weights: Weights = Compile(program)

    def run(facts: Set[String], compiled: Boolean = true): Result =
      require(facts.subsetOf(atoms.toSet), "Horn facts must be declared atoms")
      var current = facts
      var steps = Vector.empty[Derivation]
      var round = 0
      var stable = false
      while !stable && round <= atoms.size do
        val input = Vector(Map(known.name -> Val.M(current)))
        val out = (if compiled then Forward.run(program, weights, input) else Interpret.run(program, input)).head
        val result = out(next.name).asInstanceOf[Val.M].values
        require(current.subsetOf(result), "Horn block lost a fact")
        round += 1
        val additions = result -- current
        for atom <- additions.toVector.sorted do
          val i = fired.indexWhere(f => out(f.name) == Val.C(Some(atom)))
          require(i >= 0, "Horn block added an atom without a firing rule")
          steps :+= Derivation(atom, rules(i).id, rules(i).premises, round)
        stable = additions.isEmpty
        current = result
      require(stable, "Horn block did not reach a fixed point within its finite atom bound")
      Result(current, steps, round)
