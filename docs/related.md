# Related repositories and research

## Repositories

| Repository | Relation to okay-inlay |
|---|---|
| **okay-desk** | the first application ([okay-desk.md](okay-desk.md)): depends on `okay-inlay` 0.1.0; runs the compiled `Closure` in the page; `TablesDomain` is a `Domain`; the core model, the bus and its adapter were built there |
| **mlx-scala** (`mlx-scala`, `mlx-scala-llm`) | Apple MLX from Scala 3: the `mlx` module's GPU, the models (Qwen3, Qwen3.5 with layer hooks), `Port` (decode masks), `Names`, `Lora`; features added for inlay: `Qwen3.Hook`, `Qwen35.hook`, `Mlx.valueAndGrad`, `Port.choices` |
| **okay-llm** | its gateway carries the quote mask (`okay_quote`) to okay-mlx; its `Qwen3Compute` is the forward pass okay-desk runs in the browser |
| **okay-datalog** | Datalog with many handlers; `specs/datalog.md` plans an inlay handler (Eval == Inlay == Proof); okay-desk falls back to it when a query does not fit the compiled closure |
| **okay-reliability** (was okay-reason) | exact reasoning as a tool a model uses (`assert`, `query`, `solve`, `why_not`); `specs/reason.md` |
| **symdex** | code intelligence: the source of forge's tasks and of the `Names` mask's members; its checker verifies forge's attempts |
| **okay-agent** | the agent the symbols, forge and reason benchmarks target |
| **verity**, **okay-store** | proposed memory for the browser assistant (`specs/assistant.md`) |
| **okay-uplc** | the target of a UPLC-shaped CEK in weights (BACKLOG 4) |

## Research

From `specs/inlay.md` §7 and `specs/bridge.md` §10:

- **Tracr** — compiling RASP programs into transformer weights (arXiv 2301.05062): the starting point.
- **RASP, RASP-L** — what transformers can express and length-generalise.
- **Learning Transformer Programs** — the reverse direction: training programs out of transformers.
- **Looped transformers as programmable computers** (Giannou et al., 2023), **ALTA** — loops in weights.
- **InterpBench / TracrBench** — models with known circuits.
- **Memorizing Transformers, RETRO, KBLaM** — memory inside attention.
- **DeepProbLog, Scallop, Logic Tensor Networks** — logic with neural predicates; the closest to the bridge.
- **Logic-LM** — a language model with a solver.
- **Hillerström & Lindley** — abstract machines for effect handlers (the CEK with handlers).
- **Monitor-guided decoding** — static analysis constraining code models (the `Names` mask).
