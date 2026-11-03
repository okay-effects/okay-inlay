# okay-datalog — one Datalog program, many handlers (spec, 2026-11-03)

Status: planned; the first library step of okay-inlay (BACKLOG). Operator's decision 2026-11-03: the same Datalog
code runs as ordinary Scala on the JVM, as JavaScript in Cloudflare Workers (Scala.js), and compiles into transformer
weights run by MLX — by different handlers of the same effects. okay-facts' rules and constraints (okay-facts
specs/design.md §18) use it in the Worker instead of writing a Datalog of their own.

## 1. The shape

```
rules (data, parsed like Scalus parses Scala into SIR)
   │
   ▼
the IR: relational algebra + fixpoint, as effects
   Scan(R) · Join(a, b, keys) · Project(cols) · Select(pred) · Union · Diff · Fixpoint(step)
   │
   ├─ Eval    semi-naive evaluation, hash joins                 JVM · Scala.js (Workers) · Native
   ├─ Proof   the same evaluation in the why-provenance semiring JVM · Scala.js · Native
   ├─ Inlay   compiled into layers: a join = an attention head,  → a layer program → MLX (mlx-scala, JVM/Native)
   │          OR = an MLP step, the fixpoint = a loop            → a reference executor of the matrices (everywhere)
   └─ Sql     recursive CTEs for SQLite / D1 / okay-store        (optional)
```

The program never names its handler: `Eval.run(rules, facts)`, `Proof.run(rules, facts)`,
`Inlay.compile(rules, schema).on(Mlx).run(facts)` — one line each, the effects architecture rule.

## 2. The language

- Facts and rules, Horn clauses without function symbols: `anc(X, Z) :- parent(X, Y), anc(Y, Z).`
- Stratified negation (`not r(X)` over a relation of a lower stratum); stratification computed and refused when
  impossible (a cycle through negation), like a compiler error.
- Comparisons and constants in bodies (`price(S, P), P <= 300`); aggregates later (stratified, as in Soufflé).
- Input relations (EDB) from sources (handlers again: okay-store, a shop's object, a CSV, a model's reader);
  derived relations (IDB) out.
- A Scala interpolator `datalog"""…"""` (parsed at compile time where possible) and a plain AST for generated rules
  (from an ontology's OWL 2 RL, a shop's policies).

## 3. Semirings as handlers

Datalog evaluation parameterized by a commutative semiring (provenance semirings, Green, Karvounarakis, Tannen 2007):

| Semiring | Result | Use |
|---|---|---|
| Boolean (∨, ∧) | the derived facts | always |
| why-provenance / derivation trees | proofs | okay-facts' `proof` in `facts[]` |
| Viterbi (max, ×) on [0, 1] | a conclusion's confidence from neural facts | intent facts (model's confidence) |
| counting (ℕ, +, ×) | the number of derivations | diagnostics, ranking |
| thresholded float32 steps | the Boolean semiring in weights | the Inlay handler |

`Proof` is `Eval` in the derivation semiring, not separate code. Termination: Boolean and Viterbi converge; counting
may not on recursion (bounded or refused).

## 4. The Inlay handler

From the okay-inlay experiments (`Datalog.sc`): constants are positions, a binary relation is a bit-row per position,
a rule's join is an attention head (query = a row of one relation, key = the constant's code, value = a row of the
other), OR is a two-ReLU step, monotone updates write only differences, the fixpoint is a loop with a "no change"
flag. Generalized:

- relations of any arity by encoding tuples (pairs as positions, or a relation per argument position);
- binary codes for constants (`HandlersBinary.sc`: ±1 attention, log₂ N axes);
- strata as successive loops; negation of a finished stratum's relation as a NOT gate;
- comparisons with constants as precomputed bit-masks;
- the output: a **layer program** (heads, MLPs, loop structure) — run by mlx-scala on the GPU, or by a reference
  executor of the matrices in pure Scala (JVM / JS / Native) for tests.

Efficient only for small fixed domains (dense n × n bit rows, O(n²) a round on attention): thousands of constants on
MLX, not millions. Handler choice by size: what fits runs in weights, the rest by `Eval` — the program unchanged.

## 5. Laws (property tests, as in okay-agent)

- `Eval(rules, facts) == Inlay(rules, facts)` (the reference executor; and on MLX on the Mac) on random programs and
  data within the Inlay handler's limits.
- `Proof(rules, facts).facts == Eval(rules, facts)`, and every proof checks: its leaves are input facts, each step an
  instance of a rule.
- Semi-naive equals naive evaluation.
- Stratification refuses exactly the programs with a cycle through negation.

## 6. Modules

```
okay-datalog        (JVM / JS / Native)  language, parser, stratification, the IR and its effects, Eval, Proof,
                                         semirings, Sql
okay-inlay-core     (JVM / JS / Native)  the DSL of step rules → a layer program; the reference executor
okay-inlay-mlx      (JVM / Native)       a layer program → mlx-scala tensors, run on the GPU
okay-datalog-inlay  (JVM / JS / Native)  the Datalog IR → a layer program (joins, steps, loops)
```

The same principle for the whole of okay-inlay: every module is a program over effects with at least two handlers
— a reference one (runs directly, cross-platform) and Inlay (compiles into weights) — and a law that they agree. The
register machine, CEK, DPLL and the effect handlers of the experiments are already built as "reference +
transformer"; the library makes it systematic.

## 7. Users

- okay-facts (§18): `Eval` and `Proof` in the Worker for the shops' rules (service links, hours, policies, ontology
  hierarchies); Viterbi for conclusions resting on intent facts.
- okay-inlay research: `Inlay` on MLX; Datalog with a neural relation (BACKLOG) — the neural relation an input
  handler over a model's states.
- Later: a browser handler on WebGPU (the layer program client-side, an okay-ui page).

## 8. Stages

1. `okay-datalog`: language, parser, stratification, IR, `Eval` (semi-naive), laws; JVM + Scala.js; released under
   the okay-effects Pages maven (0.1.0, republished while unused).
2. Semirings, `Proof`, Viterbi; okay-facts §18 starts on it.
3. `okay-inlay-core` (layer program + reference executor) and `okay-datalog-inlay`; the law Eval == Inlay.
4. `okay-inlay-mlx`: the layer program on MLX; measure against `Eval` (sizes where the GPU wins, if any).
5. `Sql` handler; WebGPU later.
