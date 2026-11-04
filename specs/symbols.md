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
