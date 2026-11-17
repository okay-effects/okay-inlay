# The concept: choose, compute, place, prove, teach (2026-11-17)

The operator asked for one finished, orderly concept out of the week's separate threads: the reader that chooses
(okay-desk `specs/theory.md`), programs in weights (this repository), narrower blocks, programs as data, a proved
compiler, exact programs as teachers. This file is that one picture. It adds nothing measured; it says what each
thing IS in one scheme, what is known of it, and which experiment would settle what is not. Where it repeats
okay-desk's theory it points there instead of restating it.

## 0. In one sentence

**A small model only chooses among options that programs offer; programs compute; a law binds every way of running
a program to its reference meaning; where a program runs is a matter of cost; and the exact side teaches the
learned side.**

Five verbs, five parts. Only the first is learned and uncertain. Everything else is exact and carries a law.

## 1. Choose — the one learned thing

Understanding a text is refining a term with typed holes, each hole filled from a FINITE set of options the tools
compute from the data; one learned relation — does the text mean these canonical words — weighs the options
(theory.md §1, §10). Nothing outside the options can be chosen, so the model cannot invent; it can only choose
wrongly, and how often is bounded by calibration, with the rest asked (guarantees.md §3).

Consequence for size: the model's job is recognising a paraphrase, not reasoning. That is why it can be small.
Measured: 0.6B reads about half of unseen questions; 1.7B halves the errors; 4B adds nothing more (frames.md §5.7).

## 2. Compute — programs with a reference meaning

Every exact part is a program as DATA with a reference interpreter: the grammar and its options, the engines
(relations, Datalog, Horn, SAT/DPLL, the grid solver, arithmetic), the checks that refute, the saying and its
inverse parser. The reference interpreter is the meaning. Nothing else is.

Programs take other programs as data (`Registers`, `Cek`: an interpreter whose program sits in the context), so
"a new program" need not mean "new code" or "new weights".

## 3. Place — a handler is a way of running a program, bound by one law

    for every program p and input x in its bounds:   run_h(p, x) = Interpret(p, x)

A handler h is anything that obeys this: the reference itself on the CPU; compiled weights (`Compile` + `Forward`,
MLX, the browser); a trained dense model that was checked; the runtime outside the model; a person. The program
does not change when its handler does (specs/inlay.md §8.6: one program, two handlers).

**Which handler is a question of cost, never of meaning.** The CPU runs every one of these programs faster. The
weights are worth it only when one of four things is needed (bridge.md §10, restated):

1. the program READS the model's inner states (a probe's judgement as a fact — measured, bridge-1, bridge-4);
2. the model's later layers READ the program's result (an exact organ — not measured);
3. the result must act INSIDE the pass (a mask with no readback from the GPU — to measure, SPRINT `readback`);
4. a training signal must pass THROUGH it (needs a softened attention — not measured).

**Resources are the handler's type.** A compiled program costs width, depth, positions and numeric margin. Every
"compression" discussed is a rewrite that keeps the law and spends or saves one of them:

| rewrite | saves | spends | exact |
| --- | --- | --- | --- |
| liveness (fields share coordinates, a `Clear` head) | width | a head a layer | yes |
| binary codes for a field | width | neurons to decode | yes |
| a looped layer | parameters | steps | yes |
| the trace as memory (built: `Machine`) | width | positions | yes |
| half precision, quantised weights | bytes | margin | while the margin lasts |
| a projection into fewer dimensions | width | margin | while the margin lasts |

So one ledger: each rewrite declares what it spends, and the compiler shows the total stays inside the device's
budget and the margin. "Exact on this phone at this length" becomes a property of the compiled program, stated
before it runs. Known from elsewhere: algorithms compress only a few times by superposition (BACKLOG "Narrower
exact blocks"); the exact rewrites above are not limited by that.

## 4. Prove — a ladder for the law

The same law, three strengths, and each program says which one it has:

1. **sampled** — random inputs (every `*Suite` here now);
2. **exhaustive** — every input of a finite domain; a proof by enumeration;
3. **proved** — for all inputs in the bounds, by a margin lemma for a head and composition over layers (a
   verified compiler; okay-lean is where it would live). Not started.

The learned part has no law, only two measured properties: calibration, and the round trip of what the person is
shown (theory.md §3, L5). The guarantee of an answer is the product: a calibrated choice × exact programs × words
that are the term's own (theory.md §4).

## 5. Teach — the exact side makes the labels

Because a program knows the right answer and the whole path to it, it serves the learning in five roles, all
without a gradient through it:

1. **the verifier**: a (text, term) pair is kept only when the term's answer agrees (built: the teacher's
   read-back, the exact judge);
2. **the paths**: a gold term's refinements are the listwise labels (theory.md §6; built);
3. **the reward**: any candidate whose answer is right counts — labels from answers alone, e.g. a person's
   correction (not built; spurious right answers are the known risk);
4. **the curriculum**: difficulty is the length of the derivation (not built);
5. **the counterexample loop**: train, run the law, add every failure, repeat; on a finite domain end with
   enumeration — a TRAINED dense model with a law of strength 2 (not built; this is also the other road to a
   narrow block: by learning and checking, not by construction).

And one role with the program in the model: a frozen exact organ beside it, the model trained to read it. Whether
a model then stops computing by itself and delegates is the open question behind "a small model with exact organs
instead of a large one" (ablate the organ after training and measure the fall).

## 6. A module

The definition exists and is built: `okay.inlay.domain.Domain` (specs/domains.md) — a schema, a type of meanings,
an enumerator, the exact engine, the phrases, and SLOTS that are either a choice over a closed set or a copy of
the text's own words, with the laws `assemble(parts(p)) == p`; its `Kit` gives the readers, the measure by slot
and the abstention. The bus (specs/bus.md) is where a model's states meet those slots. In okay-desk's words an
ability is five functions — its types and constructors, its options, its denotation, its checks, its saying
(theory.md §2). This concept adds two optional things from §3 and §4: a compiled form with its resource type (a
file beside an unchanged base), and the strength of its law. (okay-shop's `okay.shop.commands`, written
2026-11-17 without this framework, is one more such domain; it is to meet `Domain` by an adapter where the
training lives, or be rewritten on it once okay-inlay is released — the operator's choice.)

A new ability needs no training of its own if the one
matcher is shared (theory.md §10; technology.md §12.4: not yet shown once).

## 7. The applications are instances

| application | choose | compute | notes |
| --- | --- | --- | --- |
| okay-desk: questions over a table | a query frame | the relational engine | live; the measures above |
| okay-desk: logic tasks | the statement | the grid solver, Horn | live |
| a master's commands (okay-shop specs/commands.md) | a command frame | the workshop itself | A0 pending |
| the Socratic lesson (Elysion) | which position an answer takes | Horn: the contradiction and its proof | idea |
| one rule in four languages | — (written in the canonical words) | saying and parsing | idea |
| puzzles with hints | — | the solver; a hint = the next proof step | idea |
| an agent's rules (okay-guard) | a rule frame | the policy engine | idea |

## 8. What would change this picture

Each open claim with the experiment that settles it, cheapest first:

1. §3 use 3 — `readback` (SPRINT): the share of a question's time spent reading logits back. Small: weights as a
   handler have no practical case on the phone, and §3's rewrites stay knowledge.
2. §3 the exact rewrites — `liveness` (SPRINT): widths before and after. Expected modest on today's programs.
3. §6 — a second ability read by the shared matcher with no training of its own.
4. §5 the organ — delegation: a frozen `Count` beside a small model, trained to read it, then ablated.
5. §5 role 5 — a trained model with an enumerated law for one small program.
6. §4 strength 3 — the margin lemma for `Lookup`, proved.
7. §1 — people's own questions; every bound here holds only for texts like the calibration's.

Prior art by part, as far as read: choosing among options — semantic parsing, constrained decoding; compiling to
weights — RASP, Tracr; benchmarks of known circuits — InterpBench; computing in superposition — bounds and lower
bounds; interpreters in weights — looped transformers. What is this concept's own is the JOIN: one law for every
handler, resources as the handler's type, and the exact side as the teacher of the one learned relation.
