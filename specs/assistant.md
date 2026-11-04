# A personal assistant in the browser — offline, no keys (spec, 2026-11-04)

Status: a concept (operator's direction, 2026-11-04), the product of specs/bridge.md §11. A chat in a page: a small
model runs in the browser, its memory and history in the browser's storage, no key to any online model, free. It
remembers what it is given, searches when allowed, answers understanding the question — and it is exact where
large online models are not, because beside the small model stand the compiled machines.

## 1. The loop

1. **Remember.** The person pastes or drops data: text, CSV, JSON, mail, a page. Two things are kept, both in
   IndexedDB with their source: the **text itself**, chunked and indexed (so quotes can be verbatim), and the
   **facts** the readers draw from it (okay-store records: entities, relations, numbers, dates — typed, each with its
   source span and a confidence). Nothing is "remembered in weights": memory is data, exact and inspectable; the
   person can see and correct a fact.
2. **Search** (when allowed). An extension opens searches and pages in the person's own browser (no API, no
   server; a page alone cannot read other sites for CORS); what comes back goes through the same Remember.
3. **Ask.** The small model does what it is good at: it understands the question and writes a PROGRAM — a query
   over the facts (Datalog / SQL), a count, a comparison, a constraint — which the exact handler runs over the
   store; then it phrases the answer from the result, under masks: numbers only from the result, quotes verbatim
   (the Quote port), identifiers only from the store (the Symbols port). The answer cites rows and spans. What
   the facts cannot answer, it says so, and asks or searches.
4. **Automate.** A question that worked is kept as a skill (its program), run again on new data or on a schedule
   in the page; the model is not needed to re-run it.

## 2. Parts

| part | what | where |
|---|---|---|
| the model | Qwen3.5-0.6B / 1.7B, 4-bit, ~0.4–1 GB cached once; WebGPU (WebGL2 slow) | an existing WebGPU runtime first; ours on Scala.js later |
| readers | small trained readers per domain (reliability's `Micro`, a relation reader, a table-column reader) and ASK (the model asked yes/no); two readers → unsure marked | Scala.js forward, MB each |
| machines | the compiled programs (count, lookup, closure, Datalog, DPLL) — exact by the law | `Forward` on Scala.js now (the smoke: `web/`), WebGPU next |
| store | okay-store records, text chunks with an index | IndexedDB |
| masks | Quote, Symbols, numbers-from-results — the decode port in the browser | the runtime's logits hook |

## 3. What it can do, honestly

- Keep a growing personal base — mail, orders, notes, pages — and answer over it exactly: counts, joins, dates,
  "which of these…", "how many…", "is there a contradiction between…", with sources. The large models fail at
  exactly this over long material (bridge-1/4); a small one with the machines does not.
- Quote and name things only as they are in the base: no invented figure, identifier or citation (the mask).
- Search and fold results into the base, then reason over them exactly.
- Run saved programs on new data: a weekly check, a reconciliation, a watch.

What it cannot: general knowledge and fluent long writing at a large model's level (a 1.7B is a 1.7B); free-form
reasoning the program cannot express. The design pins the small model to what it does well (understand, write a
program, phrase) and takes the rest from the machines.

## 4. The order

1. The machines in the browser — done (okay-inlay `web/`: the closure exact in a page, 0.5–2 s on plain JS).
2. The store in IndexedDB (okay-store's handler) and the text index; Remember.
3. The model in the page (runtime choice; the decode port hook) and Ask with the data pack (specs/bridge.md §11.1).
4. A reader (`Micro`, okay-reliability specs/reader.md) and the incidents pack.
5. The extension and Search; the Quote mask in the browser.

## 5. Verity as the memory (operator, 2026-11-04)

The operator's shape: the assistant SEARCHES NOTHING itself. It is given a link — it reads the page and remembers the
link and the main information from it; it is given private (not secret, private) information — it remembers it; asked
later, it answers and shows the links. That is verity (~/work/my/verity, 2026-10-20: "the one who remembers your
details exactly" — kinds of records, versioned facts in an append-only journal, secrets shown only through `Reveal`
under a consent policy, faces: terminal, window, browser, MCP) with two things added:

- **Remember** — from a link: the page's text kept verbatim (chunked, indexed) and a record of kind `page` (url,
  title, when read, the main facts — each a span of the text, so nothing remembered is invented); from a note: a
  record of kind `note` (text, tags, the entities named). The readers draw the facts; the small model names the
  entities and the title; every fact points to its span. A page is fetched by the person's own browser (an
  extension, or the page's text pasted; a page alone cannot read other sites) — no server, no key.
- **Ask** — the small model understands the question and writes a QUERY over the records and the text index (exact
  retrieval: names, dates, kinds, words; no embeddings needed for v1); the exact machines merge entities, pick the
  current version, count, find what contradicts what; the answer is phrased under the masks — quotes verbatim
  from the kept spans, links from the kept records, numbers from the results — and verity's policy decides what is
  shown and writes every showing down.

Why it fits: verity already has the journal, the kinds, the versions, the consent and the faces; what it lacked was
reading a page into records and answering a question in words. Both come from the bridge: readers in, a program out.
The browser version needs okay-store's handler on IndexedDB (verity keeps the file on the server today) and the small
model in the page (§2). The order stands (§4): the store in the browser, then the model and Ask, then Remember from
a link; the extension last.
