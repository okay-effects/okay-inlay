# Exact quotes (spec, 2026-11-04)

BACKLOG 11. A model quotes its source verbatim: what it puts between the markers `⟦` and `⟧` is a span of the
source in its context, token for token — it chooses what to quote, it cannot misquote or invent an identifier.

## The protocol

The system prompt: "quotes, identifiers, numbers from the documents: inside ⟦ ⟧". Outside the markers the model writes
freely. After `⟦`, every token must continue a span of the source matching what was copied so far; `⟧` allowed at
any point (a shorter span is still verbatim).

- The source: the prompt's tokens (the documents are in it), or the spans the request names.
- The state: off, or the source positions where the copy so far could continue (`matches`). Open: every source
  position. A token t taken: `matches := { p + 1 | source(p) = t, p ∈ matches }`. Allowed: `{ source(p) | p ∈
  matches } ∪ { ⟧ }`. Several spans matching narrow as the copy goes on.
- Markers that are several tokens: the open marker recognized when its last token is taken; the close marker's
  first token allowed, the rest forced.
- Start only at a source token's boundary (" order" and "order" are different tokens): the copy is of the source's
  tokens, so a quote reads as it was tokenized there.

## Stages

**(a) The decode port** — a per-step hook in mlx-scala's `Session`: before each pick, the port's allowed set (or
none: free); the disallowed tokens' logits −∞. While a port constrains, the next step is built after the current
token is read (the mask depends on it) — the overlap lost only then; guesses off with a port. `Quote` — the port
above, pure, over token ids. okay-llm's gateway: a request's `okay_quote` (markers; the source by default the
prompt) → okay-mlx's backend builds the port from the prompt's tokens.

**(b) Weights** — the same as an added block on Qwen (a head "the latest marker", previous-token heads making k-gram
keys from ±1 token codes, an induction head to the matching positions, its read a mask −λ); the law: (a) == (b) on
every step.

**(c) An unmodified engine** — closed 2026-11-04 (specs/dsl.md, "Stage (c)"): float16/bfloat16 break the `latest` head
(112–127 of ~850 positions not decodable), and a dense model has no free residual dims nor the token at depth; the
block stays a side model the engine combines (b).

## The benchmark

Documents (logs, invoices, chats; ru/en) and questions whose answers need quotes and identifiers, gold answers.
Arms: none / (a) / (b). Metrics: misquotes and invented identifiers (target 0), answers right (the mask must not
make them worse), tokens/s.

## Laws (tests)

- Every quote made under the port is a span of the source's tokens.
- Outside the markers the port changes nothing (the same tokens as without it, greedy).
- The port never leaves the model with no token allowed (`⟧` always is).
