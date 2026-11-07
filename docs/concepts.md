# Concepts

## A transformer can be built, not only trained

A transformer is a stack of layers over a sequence of positions. Each position carries a vector — the residual
stream — and each layer adds to it: attention heads move information between positions, an MLP (two matrices and
a ReLU) transforms it in place. Training finds the matrices by gradient descent. But the matrices can also be
*computed* from a program, so that the network performs the program exactly. This is the idea of Tracr (compiling
RASP into weights, arXiv 2301.05062); okay-inlay takes it further — to interpreters, effect handlers, Datalog and
SAT search — and makes it a library with a law.

Speed is not the point: a CPU runs the same program millions of times faster. The point is what an exact mechanism
*inside* a model can be, and how it composes with what only the model has — its reading of text, its context, its
internal state:

- exact data access (a table read by an attention head);
- deduction (Datalog's rules as joins, all facts of a round at once);
- search (effect handlers with multi-shot continuations; DPLL with all branches in parallel);
- computation (an interpreter: the program is data in the context, the weights are the machine).

## The building blocks

Each of these is verified by an experiment (see [experiments.md](experiments.md)).

**A head is a lookup.** Give every position a one-hot code of a key. The query-key matrix `W_QK` is the truth
table of "query equals key", times a large λ. Softmax then puts all attention on the matching position: the losing
scores are at least λ lower, and `exp(−λ)` underflows to exactly 0 in float32 (exp(−125) is already 0). Attention
becomes exactly hard, and the value matrix copies the matched row's field. A start position with a fixed score half
a gap below a match makes "not found" read zeros. Several matches average — SQL's AVG — which is why a lookup is
exact only on a unique key.

**A neuron is a gate.** Over one-hot inputs, `ReLU(x₁ + … + xₙ − (n−1))` is AND: it is 1 when all n inputs are 1
and ≤ 0 otherwise. Two ReLUs make a step (`ReLU(x) − ReLU(x − 1)` clamps to [0, 1]); a ladder of steps turns a
fraction into an integer.

**Counting is attention's width.** If a head attends equally to the c matching positions and to the start, the
start receives exactly 1/(c+1) of the attention. Copy a constant 1 from the start, and the position now holds
1/(c+1); a ladder of ReLU steps reads c from it. This is the `Count` head.

**The residual only adds.** A layer cannot overwrite; it adds. So every field is written once (static single
assignment): an assignment is "add the value to a field that was zero", and a machine that mutates state writes a
fresh record each step.

**Memory is append-only.** Immutable structures — environments, persistent stacks, monotone facts — fit as they
are. Mutation needs "the latest write wins": the score gets `ε · position` added, so among matches the latest is
the largest.

**Exactness is kept, not hoped for.** One-hot (or ±1) codes, hard attention and gates keep every value at exactly
0 or 1 after hundreds of steps. Binary pointers (±1 per bit, log₂ N dimensions) keep it with far fewer neurons.

**A transformer is a BSP machine.** Positions are processors; a layer (or one turn of a looped block) is a
superstep; the MLP is local computation; attention reads other processors. A sequential machine uses one position;
Datalog and DPLL use all of them at once.

**Rules become neurons.** A step function written as a table of rules — guards, constants, copies, shifts — compiles
mechanically into AND-neurons. This is what the library automates.

## The law

Every program has two meanings:

- `Interpret.run(p, inputs)` — an ordinary interpreter over records of named fields: the truth;
- `Forward.run(p, Compile(p), inputs)` — the compiled weights run as a float32 transformer, the result decoded.

The law, checked on random inputs for every program in the test suites:

```
decode(Forward(Compile(p), embed(x))) == Interpret(p, x)
```

When the reference itself cannot be exact — a lookup whose matches disagree, two rules writing one field — it
throws `Inexact` instead of returning something; the tests skip such inputs and require that most are exact.

## The model of computation

A program runs over a sequence of positions. Each position holds a **record** of named fields; a field has a type
that fixes how it sits in the residual:

| Field | Values | In the residual |
|---|---|---|
| `Cat(name, values)` | one of `values`, or none | one-hot, `values.size` dimensions (none: all zero) |
| `Bits(name, n)` | an integer in [0, 2ⁿ), or none | ±1 per bit, plus a presence dimension |
| `Flag(name)` | true / false | one dimension, 1 / 0 |
| `Many(name, values)` | a set of `values` | one 0/1 dimension per value |

A program is a sequence of **layers**; a layer is heads (in parallel), then rules (the MLP). Inputs come from the
embedding; every other field is written by exactly one op and read only after the layer that writes it. A program
is *causal* by default (a position sees itself and earlier ones, like a decoder) or not (every position sees every
position — a block of its own, as Datalog needs).

## What a compiled block is good for — and what it is not

A compiled block does not understand language. It computes, exactly, over what it is given. The interesting
systems put a model and a block together: the model reads the question and the data into the block's inputs
(which table, which column, which value), the block computes, and the result goes back. Then the errors have an
address: the block adds none, so every mistake is in the reading or in the writing back. That is the subject of
[bridge-and-bus.md](bridge-and-bus.md).

The honest limits, from the specs:

- fixed depth computes a *window*: a recurrence over the whole sequence needs a layer per step, a loop, or state
  carried in tokens;
- half precision breaks it: float16 and bfloat16 cannot hold the `latest` scores (about 65,536 — past float16's
  65,504), so a block runs in float32 beside the model, not inside a half-precision engine;
- a dense trained model has no free residual dimensions and no clean token identity at depth, so a block is a side
  model joined through an interface, not weights spliced into the model's own layers;
- one-hot sizes grow with the domain; Datalog in weights is for small fixed domains (thousands of constants, not
  millions).
