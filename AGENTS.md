# AGENTS.md — okay-inlay

Compiling programs into transformer weights, and effects inside models. Read `specs/inlay.md` first (what is known,
what each experiment proved, the plan); work from `BACKLOG.md` / `SPRINT.md` (scrumban). A research repository.

- `experiments/*.sc` — scala-cli scripts, each self-checking against a reference (exit 1 on a mismatch);
  `bin/check` runs them all. An experiment is never weakened to pass: a mismatch is a finding, recorded in the spec.
- Every number quoted in the spec comes from an experiment's output; say which.
- Scala 3, lines at most 120 characters, English in code and docs. Scripts are scala-cli, never Python.
- The library (a DSL of step rules → weights) comes next as an sbt project; the experiments migrate into its tests
  as the DSL covers them.
- Commits: plain messages, no attribution trailers.
