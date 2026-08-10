# Architectural decisions

Helix records significant architectural choices as lightweight Architecture Decision Records (ADRs). These documents explain the context, alternatives, consequences, and conditions under which a decision should be revisited.

The decision log covers implemented architecture. Roadmap ideas are kept in the [README](README.md) and are not presented as accepted decisions.

## Suggested reading path

For a short architecture review, start with:

1. [ADR 0003](docs/decisions/0003-bound-llm-investigations-with-typed-tools.md) — the safety and auditability boundary around the AI investigation agent.
2. [ADR 0001](docs/decisions/0001-separate-parsing-from-analysis.md) — the modular boundary that allows heterogeneous JVM artifacts to share analysis infrastructure.
3. [ADR 0002](docs/decisions/0002-classify-evidence-reliability.md) — the trust model separating observations, calculations, and heuristics.

| ADR | Decision | Why it matters |
|---|---|---|
| [0001](docs/decisions/0001-separate-parsing-from-analysis.md) | Separate parsing from analysis | Extensibility, testability, and domain modeling |
| [0002](docs/decisions/0002-classify-evidence-reliability.md) | Classify evidence reliability | Trustworthy diagnostics and defensible AI conclusions |
| [0003](docs/decisions/0003-bound-llm-investigations-with-typed-tools.md) | Bound LLM investigations with typed tools | AI safety, data minimization, and auditability |
| [0004](docs/decisions/0004-isolate-model-providers-behind-adapters.md) | Isolate model providers behind adapters | External-system boundaries and provider portability |
| [0005](docs/decisions/0005-integrate-eclipse-mat-as-external-runtime.md) | Integrate MAT as an external runtime | Build-versus-buy judgment and dependency isolation |
| [0006](docs/decisions/0006-use-explicit-correlation-rules-with-fallback.md) | Use explicit correlation rules with fallback | Determinism, resilience, and causal restraint |
