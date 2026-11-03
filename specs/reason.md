# okay-reason — exact reasoning as a tool the model uses (spec, 2026-11-03)

Status: planned. The operator's decision (2026-11-03): a tool through which a model finds right answers it does not
find alone — the model does what it is good at (reading text, stating facts, leading an investigation), the tool does
what it gets wrong (deduction, search, counting, uniqueness). symdex's `query` generalized: "Datalog over a codebase's
index" becomes "logic over the model's observations". May become its own repository once v0 works.

## 1. What it is

An MCP server on okay-mcp (as symdex), usable by any agent: Claude Code, okay-agent (which starts a project's MCP
servers itself), opencode. Two layers:

**General logic**

| Tool | What it does |
|---|---|
| `assert` | facts into the session's base, each with its source (`log:42`, `chat:5`) and an optional confidence |
| `query` | a Datalog goal over the facts and rules, with proofs |
| `solve` | constraints (hard and soft) → the solutions, their number, an UNSAT core when there is none |
| `why_not` | why a hypothesis is impossible: a minimal set of facts that excludes it |

**Domain packs** — rules given, as symdex's prelude: the model states facts in the pack's schema, never writes logic.
First: **failures** (relations `depends`, `fallback`, `replicas`, `symptom`, `healthy`, `intervention`; tool
`explain` → the minimal explanations, how many, what to check next). Later: kinship, reconciliation of payments,
schedules, rules with exceptions.

## 2. How a model uses it (the failures pack)

1. It reads the architecture, the logs, the chat; `assert(depends(checkout, payments), symptom(checkout, timeout,
   "14:02", src="log:17"), healthy(reports, "14:10", src="chat:5"), …)`.
2. `explain()` → "two minimal explanations (k = 1): A redis down from 13:58 (7 of 7 symptoms), B payments slow from
   14:01 (7 of 7); the check that tells them apart: inventory's health (A: its cache serves stale data, warnings in
   its log; B: clean); Postgres excluded — reports would be down, and it is healthy (chat:5)".
3. It looks at inventory's log (or asks the person), asserts what it found, `explain()` again → "one explanation: A".
4. It answers the person, citing the proof.

## 3. The reasoning (failures)

- **Propagation (Datalog)**: for each candidate `fault(component, kind)`, the symptoms it explains (`explains`) and the
  healthy observations it contradicts (`contradicts`) — by kind (down → connection errors, slow → timeouts), delays,
  caches and replicas masking.
- **Abduction (SAT)**: candidates as variables; each observed symptom a clause "one of its explainers"; each healthy
  observation "none of its contradictors"; minimality by "at most k", k = 1, 2, 3.
- **Counting** at the minimal k: unique or several explanations, all listed.
- **Next check**: the observation that best splits the remaining explanations.
- **Why not X**: an UNSAT core.

## 4. Making it usable by a model

- The schema in the tool's description (relations and their meaning), as symdex's `query`; an unknown relation an
  error that lists the right ones.
- Every fact with its source; proofs cite sources, not fact numbers.
- Confidence: an explanation resting on an unsure fact is marked ("rests on 'inventory is alive', 0.6 — check it").
- Compact text for a model, `format: json` for a program.
- Under okay-guard: every call seen and journaled.

## 5. Handlers (one program, many)

- Now: okay-datalog (released) and a SAT solver with counting and cores on the CPU (DPLL as ordinary code).
- Next: okay-inlay's handler for the same programs (weights on MLX), with the law "the answers are the same".
- Deeper, in a model of our own: the decode port and modules in weights — the tool reached inside generation, not by
  text (okay-agent specs/in-generation.md).

## 6. The proof that it helps

A benchmark of failures with known answers (okay-inlay `specs/rca.md` to come: a generator of systems, faults and
observations rendered as architecture text, logs in several formats, chat in ru/en, with gold answers):

| Arm | What |
|---|---|
| A | the model alone |
| T | the model + okay-reason, choosing what to extract and when to call (an agent with MCP) |
| E | gold facts + okay-reason (the ceiling) |

Metrics: root causes exact (as a set), minimal, consistent with healthy observations, ambiguity recognized, the next
check useful, the share of confident wrong answers — by difficulty (graph size, 1–3 roots, masking, partial
observation, noise). T ≫ A: the tool works; T ≈ E: the model states facts well. Run on okay-agent with Qwen3.6-35B (MCP,
journal, replay).

## 7. Plan

1. The generator of failures with gold answers.
2. okay-reason v0: MCP server, `assert` / `query` / `explain` / `why_not` over okay-datalog, SAT with counting and
   cores, the failures pack.
3. The benchmark A / T / E on okay-agent + Qwen — the main result.
4. okay-inlay's handler for the exact part.
5. More packs: reconciliation of payments, kinship, rules with exceptions.
