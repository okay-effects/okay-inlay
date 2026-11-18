# Domains: a framework for reading meanings (the operator, 2026-11-07)

BACKLOG 15; specs/bus.md §5 (the kit wants a second family of tasks). The operator's point 6: everything built for
tables in okay-desk is a scheme — a domain declares its SPACE (a type of meanings, an enumerator, an exact
compiler, the phrases) and gets readers, the measure and abstention for free. Tables are the first instance;
symbols of code, the calendar and units, the inputs of compiled blocks are the next. Then the bus is checked by a
framework, not by one family of tasks.

## 1. What a domain declares (`okay.inlay.domain.Domain`)

- `Schema`: the world questions are about (tables; a calendar and a set of units; a codebase's index).
- `Point`: a meaning — a program of the space, well-formed by construction.
- `enumerate(schema)`: the space, bounded and lazy — coverage is a number.
- `random(rnd, schema)`: a point drawn by the same structure (the rare shapes weighted up).
- `run(point, schema)`: the EXACT engine — the answer, or why there is none. Nothing else computes.
- `say(rnd, point, schema, language)`: the grammar's sentence for a point (several wordings; a teacher's rewrites
  later, okay-desk specs/teacher.md).
- `slots(schema)`: the constructors a point is made of, each with its CHOICES over the schema (a closed set: the
  kind, a unit, a column) or as COPIED (a number, a date, a name the question writes: read exactly from the text,
  never guessed).
- `parts(point)`: the point as slot → choice; `assemble(schema, parts)`: back to a point, or none if not well-formed.

Laws a domain must pass (`DomainLaws`): `assemble(parts(p)) == p` for drawn points; every drawn point is in
`enumerate`; `run` agrees with the domain's hand-computed cases; every phrase names the copied parts it carries.

## 2. What the kit gives (`okay.inlay.domain.Kit`)

- **Pairs**: the space × the phrases (× a teacher), kept when the answer says something.
- **Reading**: a `Reader` gives, per slot, a distribution over its choices (a model's probe on the bus, or any
  other); copied slots are read from the text; the best choice per slot, assembled; validated by `run`.
- **Abstention**: a slot whose best two choices are within a margin abstains — the reading says "not sure: A or B"
  instead of answering. The threshold per slot is the kit's to fit on held-out pairs.
- **The measure by constructor**: per slot right / wrong / abstained, the whole reading, and "wrong above the
  threshold" (answered and wrong) — the number the exactness promise rests on.

## 3. The second instance: units and the calendar (`Units`)

Points: `Convert(quantity, from, to)` within a dimension (time: second … week; length: millimetre … kilometre;
mass: gram … tonne; data: byte … gigabyte), `Shift(date, n, unit, forward)` (a date n days / weeks / months after
or before), `Between(date, date, unit)` (days or weeks between two dates). Exact: rational arithmetic, the
Gregorian calendar by hand (no java.time: Scala.js). Phrases in English and Russian. Slots: kind, from-unit,
to-unit, shift-unit, direction (choices); quantity, dates (copied).

## 4. The test on the bus

The reference model of bus v1 (Qwen3-0.6B-4bit, the state after layer 19 at the prompt's last token); linear
probes per slot trained on the domain's pairs, measured on held-out pairs by constructor. The question it answers:
does the bus, defined on tables, carry a second family's meanings with the same recipe — the kit's second family.

## 5. Measured (2026-11-07)

`UnitsSuite` (core, JVM and Scala.js): the engine against hand answers, the calendar both ways over 1999–2031, parts
and assemble inverse on 500 drawn and all 886 enumerated points, every phrase carrying its copied parts. The kit end
to end with a reader of keywords (300 pairs, en + ru): whole 88%, 0 wrong when answered — a misread unit makes a
point the engine refuses (two dimensions), so it is unread, not wrong.

`DomainBench` (mlx; DOMAINS=1): bus v1's states (cut19, layer 19, the prompt's last token; 900 prompts in 5 s),
probes per slot and choice trained on 600 pairs, measured on 300 held-out:

| reader | whole | wrong when answered | from | to | kind, direction, shift and between units |
|---|---|---|---|---|---|
| keywords | 94% | 0 | 97% | 92% | 100% |
| probes on the bus | 91% | 22 (7.3%) | 91% | 91% | 100% |
| the bus, margin 0.5 | 52% | 9 (3.0%), 136 abstained | | | |
| hybrid: units named in the text are exact facts, the bus reads their role | 90% | 5 (1.7%), 26 unread | | | |

What it says. The bus, defined on tables, carries the second family's coarse meanings with the same recipe (the
kind, the direction, the calendar's units: 100%); a unit's identity among sixteen is finer and reads at 91% — as
specs/bus.md §1 expected. The lesson okay-desk learned on tables holds here: what the text names exactly is a fact,
not a reading; the bus decides the role. With that, 1.7% wrong when answered; the unread 26 are the keyword words'
misses (a unit not found), which fail safe.

## 6. Tables, the first instance (2026-11-07)

okay-desk's space declared as a `Domain` (`okay.desk.TablesDomain`, okay-desk branch `teacher`): the points, the
engines (`Exact`, `Logic`) and the phrases unchanged; the slots are the space's constructors over a schema (kind,
table, link, two conditions with copied values, group, aggregate and its column, order and direction, limit, a copied
node and the closure's direction). `TablesDomainSuite`: parts and assemble inverse on 1,000 drawn points over 40
schemas; the kit's pairs answered by the engines with rows. okay-inlay 0.1.0 republished locally with the package.

## 7. On okay-frames (2026-11-17; branch `frames`, not merged — the operator decides)

okay-frames 0.1.0 (`~/work/my/okay-frames`, specs/frames.md) is the shared interface of "a meaning read by
choices": a `Space[D, T]` (the next hole with finite options, assemble, path, say, parse; `close` derived), `Laws`,
a `Reading` as a value driven by its host, a `Measure`. `Domain` / `Kit` is an older writing of the same idea with
flat slots. On this branch every `Domain` is a `Space` and the kit reads through okay-frames' `Reading`.

**A decision that is the operator's: this is the core library's FIRST dependency.** The README says the core has
no dependencies; with this branch `okay-inlay` (JVM and Scala.js) depends on `okay-frames` (pure, no dependency of
its own; resolver `https://okay-effects.github.io/okay-frames/maven`, set for the whole build since every module
depends on `core`). The README is left as it is; merging this branch means changing that sentence, not merging
keeps it true.

**What was built.** `Domain` itself is unchanged; so are `Slot`, `Pair`, `Units`, and the signatures and results of
`Kit.pairs` / `read` / `measure`, so okay-desk's and the benches' code compiles as it was.

- `SlotSpace` (`Kit.space(d)(copier)`): the adapter. Slots are independent, so `next` asks them in
  `slots(schema)` order whatever was chosen. A closed slot offers its choices and `"-"` (the kit's convention for a
  slot the point does not use). A copied slot offers what the domain's `Copier` reads from the text, and `"-"`.
  `assemble` is the domain's, accepted by the exact engine (`run` is right) — what `Kit.read` always required of a
  point. `path` is `parts` in the slots' order, `"-"` where a slot is absent.
- Canonical words. `Domain.say` words a point in many ways for TRAINING pairs; it has no inverse and is not
  touched. The space's `say` is the honest plain form of `parts` — `kind=shift; shift_unit=month; direction=after;
  date1=2026-01-31; n=3`, used slots in the slots' order, the same in every language (`\`, `;`, `=` escaped by a
  backslash) — and `parse` its exact inverse: words that are not exactly some point's saying parse to nothing.
  A domain with real canonical words (okay-shop's commands) writes its own `Space` instead.
- `Kit.weigher(d)(reader)`: a `Reader` as the function a `Reading` is weighed by; `Kit.reading` =
  `Reading.run(space)(schema, question)(weigher)`; `Kit.read` says that reading in the kit's older record
  (`parts`, `margins`, `abstained`, `point`); `Kit.measure` is as it was, over `read`; `Kit.framed` is
  okay-frames' own `Measure.of` on the same space, reader and pairs.

**Where the brief's plan did not hold, and what was done instead.**

1. *A copied slot is not one forced option.* The plan: a copied slot's options are the one value the copier reads,
   so okay-frames' `forced` applies. But `Units.copier` reads the first number for both `quantity` and `n`, so for
   "How many minutes are in 3 hours?" the hole `n` would offer only `3` while the point's path says `n` is unused —
   `Laws.reach` fails on every `Convert` and `Shift`. Not using what the text writes is a real choice; so a copied
   slot offers the copied value and `"-"`, and the kit's weigher takes the copied value with no rival (an infinite
   margin, as `Kit.read` always gave it). A copied slot the text does not write offers only `"-"` — that one is
   forced. Behaviour of `Kit.read` is the same either way; `reach` holds only this way.
2. *`Kit.Measure` is not okay-frames' `Measure`.* Two counts differ, so the kit keeps its own record and
   `Kit.framed` gives the other:
   - by hole, okay-frames counts a hole only while the reading is still on the meant path (right for holes that
     depend on each other) and counts unused holes too; the kit counts every slot the meant point uses,
     independently. Keywords, 300 pairs: the kit `from 162/174`, okay-frames `from 252/300`; `quantity 174/174`
     against `0/25` (the copier's over-reading above, ending the path).
   - okay-frames asks about ANY choice under the bar, a doubtful `"-"` among them; the kit abstains only on slots
     the reading uses. The graded reader at bar 0.2: the kit abstained 247, okay-frames asked 267.
   The totals agree where these rules do not bite (keywords at every bar, any reader at bar 0): right = whole,
   unread, wrong equal — asserted in `SlotSpaceSuite`.

**Differences from the old `Kit.read` that remain** (none seen by any suite; all in degenerate inputs):
- a reader's score for a name that is not one of the slot's choices (nor `"-"`) is ignored — nothing outside the
  options can be chosen; the old kit would take such a name as the slot's value;
- a tie between the best two is broken by the slot's order of choices (okay-frames), not by the reader's map order;
- a reader naming ONE choice with a score ≤ 0: the old kit took it with that margin; now an unnamed choice (scored
  0, as the old comparison with 0) is not below it. Scores are probabilities by `Reader`'s contract;
- `next` calls `slots(schema)` once a hole and `close` walks the holes again: about twice the slots' count of
  calls a question where the old kit made one. Nothing measured slower; a domain with costly `slots` should cache.

**Measured** (`sbt coreJVM/test`, `sbt coreJS/test`, 2026-11-17):
- `KitNumbersSuite`: `Kit.measure` (every count, by slot) and every `Kit.read` (parts, margins to 1e-6, abstained,
  point — as one hash) on 300 pairs of seed 11, for the keyword reader and a graded reader (scores for every
  choice and `"-"`, some empty readings), at margins 0, 0.2, 0.5 — equal to the numbers captured on `master`
  f7983a9 with the same suite before the rewrite, on the JVM and on Scala.js. Keywords: whole 264, unread 36,
  wrong 0 at every margin. Graded: whole 169 / unread 128 / wrong 3 at 0; whole 30 / abstained 247 / unread 23 at
  0.2; whole 1 / abstained 299 at 0.5.
- `SlotSpaceSuite`: `Laws.reach` on 500 drawn points each for its own question (en, ru); `Laws.roundTrip` on 500
  drawn and all 886 enumerated points, `Laws.distinct` on the 886; a domain whose copied text holds the words' own
  marks round-trips; okay-frames' measure against the kit's as above.
- `UnitsSuite` unchanged and green. `DomainBench` (mlx) compiles; not run (it needs MLX and the GPU was busy).

Not done: okay-desk's domains (`TablesDomain`, `Code`, `PuzzleDomain`) were not compiled against this branch (they
take okay-inlay from a local publish; nothing was published).
