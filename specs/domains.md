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
