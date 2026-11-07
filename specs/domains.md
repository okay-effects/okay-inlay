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
