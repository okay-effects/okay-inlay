# A bounded recurrent Horn block (built 2026-11-08)

The operator's target in okay-desk is a minimal phone model with exact inference tools, not free text judged by
another model. `Horn.Block` supplies a common inference machine beside the existing closure and DPLL machines.

Atoms are finite named propositions. Each rule names one to eight positive premises and a conclusion; explicit
negative propositions are independent atoms. Each compiled round writes a categorical result for every firing
rule, then unions these conclusions with the known set. The host feeds the new set back until a fixed point;
it does not decide which rules fire. The block compiles once and is reused for all rounds, keeping the compiled
layout independent of chain length. A proof records the block's first firing for each newly derived atom.

There is no negation-as-failure, contraposition, or explosion from contradictory signed atoms. Callers decide how
to present known positive/negative goals, unknowns and conflicts. This is propositional Horn inference; grounding
variables and validating source readings belong to the caller. Bounds: 64 distinct atoms and 64 distinct rules.
Invalid atoms, malformed rules and exceeded bounds are refused before compilation. No empirical truth guarantee
is implied by a correct derivation from supplied premises.

`HornSuite` (JVM and Scala.js) checks hand-computed conjunctions and multi-hop proofs, multiple justifications,
unseeded cycles, non-explosion and invalid inputs. Its third test compares `Forward(Compile(program))`, `Interpret`,
and an independent least-fixed-point evaluator on 100 bounded random programs. All four tests passed on both
platforms on 2026-11-08. okay-desk's `DeductionSuite` adds sourced reading and answer/proof checks.
