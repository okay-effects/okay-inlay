# Code without invented APIs (spec, 2026-11-04)

BACKLOG 12. A model writing Scala cannot name a member that does not exist: after `Owner.` — an object, class or
package of the project — the identifier it writes is one of Owner's members, from symdex's index. It chooses which
(its understanding of the task); it cannot invent one. The quote's sibling (specs/quotes.md): the same decode port,
the allowed set from the codebase instead of the source text.

## The protocol (no markers: the code itself)

- The members: symdex `query goal=named(S, N)` over the project — every symbol and its name; the owner read from the
  SemanticDB symbol (`symdex/Equivalence.target().` → owner `Equivalence`, member `target`; a package's members its
  top-level objects and classes). One query, at the start.
- The state, from the text so far (decoded): (A) it ends with an owner's name at an identifier's start — a token
  beginning with `.` must begin a member (`.ta` → some member starts with `ta`); other tokens free (the identifier
  goes on: another name). (B) it ends with `Owner.partial` — a token must keep `partial` a member's prefix, or
  complete a member and end the identifier (a non-identifier character after it). (C) otherwise free.
- Chained selections (`a.b.c`), locals shadowing an owner, members of the standard library: out of v0.

## Stages

(a) `Names` — a decode port in mlx-scala, over the tokens' texts (each token's bytes once); (b) weights: the owner's
member trie as a lookup (later, after (a) is measured).

## The measure

Tasks on symdex's own code whose answers call its objects (`Equivalence`, `CodeFacts`, `Tools`, …), the API not in
the prompt (the model must recall or guess); Qwen3-4B (and 35B) free and under `Names`. Metrics: invented member
references (`Owner.x`, x not a member — target 0 held), how often the port overrode the model's first choice, and
whether the code compiles against symdex's classpath (scala-cli) — the end that matters: real names with wrong
arguments still fail, so the compile rate says how far names alone go.

## First measure (2026-11-04, Qwen3-4B, 10 tasks, `SymbolsBench`, bench/symbols/history.tsv)

| arm | symdex member references | invented | compile |
|---|---|---|---|
| free | 3 | 3 (`Equivalence.impureSymbols`, `Exec.runProcess`, `Equivalence.generators`) | 5 of 10 |
| held (`Names`) | 2 | 0 | 5 of 10 |

- **Free, every symdex member the model named was invented** — the problem is real.
- **Held, the mask removed them but did not fix the code**, and showed how a mask alone derails (inlay.md §3):
  wanting `impureSymbols`, the model got the real `impure` (even right: `Equivalence.impure.exists(…)` compiles) and
  then wrote its own rest anyway — `Equivalence.impure Symbols`; refused `.generators`, it dropped the dot —
  `Equivalence generators` (an infix the mask does not see). "0 invented" is partly cosmetic.
- The five that compile did not touch symdex at all (the model wrote the logic itself); the three that used it fail
  free and held alike. Names are not the API: signatures and types are what the model lacks.
- The 100-answer default of symdex's `query` hid most members at first (21 owners); `limit=100000` gives 76 owners,
  582 members.

Next: (1) choose the member WHOLE, by the model's own scores over the members' full token sequences, then force it
— not prefix by prefix; (2) close the escapes (the dot dropped, a name glued after a member); (3) the fair arm: the
members (or signatures) in the prompt, free and held — the mask as a guarantee on top of knowledge, not a substitute.
