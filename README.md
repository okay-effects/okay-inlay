# okay-inlay

Programs inlaid into transformer weights: exact lookups over tables, counting, joins, a register machine, a CEK
machine for the λ-calculus, Datalog's bottom-up evaluation, and algebraic effect handlers with multi-shot
continuations — each built as attention heads and ReLU neurons, run as a transformer (float32), and checked against
an ordinary interpreter. The point is not speed (a CPU is millions of times faster) but what an exact, compiled
mechanism inside a model can be: data access, deduction, search, effects — composed with what only the model has.

**Documentation: [`docs/`](docs/README.md)** — concepts, a guide to the DSL, the API, the experiments, the modules,
exact blocks beside a real model (the bridge, the core, the bus), okay-desk, every result, related work.

```sh
sbt coreJVM/test   # the DSL, the compiler and the laws
bin/check          # every experiment
```

The findings and the plan: [`specs/inlay.md`](specs/inlay.md).
