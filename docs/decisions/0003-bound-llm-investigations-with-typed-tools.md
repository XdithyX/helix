# ADR 0003: Bound LLM investigations with typed, read-only tools

## Context

JVM artifacts can be large, noisy, and sensitive. Sending raw thread dumps, GC logs, JFR events, or heap structures to an LLM would increase cost and data exposure while asking a probabilistic model to repeat parsing and calculation already handled more reliably by Java code.

At the same time, fixed reports cannot anticipate every question. A user may ask why a JVM became unresponsive, whether an OOM was caused by retention or allocation rate, or which evidence is still missing.

## Decision

Helix uses a bounded, single-agent investigation loop over registered diagnostic tools.

The initial model request contains the question, a sanitized artifact summary, a finding index, required-evidence policy, and tool definitions. It does not contain raw artifacts or every finding's raw data. The model may request only these read-only tools:

- `list_artifacts`
- `search_findings`
- `query_threads`
- `query_jfr``
- `query_gc`
- `query_heap`

Each tool declares a name, description, typed Java input, and JSON schema. Helix validates and executes calls against the existing `InvestigationContext`; the model never executes Java code, shell commands, or arbitrary file reads.

The orchestrator limits an investigation to eight steps and records each tool call. It provides explicit `conclude_investigation` and `report_insufficient_evidence` control tools. For causal memory questions, non-empty heap, JFR, and GC results are mandatory when those normalized artifacts are available.

## Alternatives considered

### Send the complete report or raw artifacts in one prompt

This is easy to prototype but wastes context, weakens data minimization, and makes it harder to determine which evidence influenced the answer.

### Allow arbitrary shell or filesystem tools

This increases apparent autonomy but creates an unnecessary security boundary and makes investigations difficult to reproduce.

### Build a multi-agent system immediately

The current problem benefits more from reliable evidence selection and evaluation than from agent coordination. Multiple agents would add failure modes before the single-agent path is measured.

## Consequences

Benefits:

- Raw diagnostic artifacts remain local to parsers and tools.
- Tool schemas constrain arguments and make calls testable.
- The final answer has an auditable evidence trace.
- The model can retrieve only the evidence relevant to a question.
- Helix can reject premature OOM conclusions independently of prompt compliance.

Costs and risks:

- Tool design becomes part of the product API and requires compatibility discipline.
- Current mandatory-evidence policies are strongest for Java-heap OOM investigations and must expand for other incident classes.
- A model can still produce an unsupported statement after receiving valid evidence; evaluation and output validation remain necessary.
- An eight-step budget may be insufficient for future distributed investigations and should be configurable only with measured need.

## Evidence in the repository

- [`InvestigationOrchestrator`](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/InvestigationOrchestrator.java)
- [`DiagnosticTool`](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/DiagnosticTool.java)
- [`DiagnosticToolRegistry`](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/DiagnosticToolRegistry.java)
- [Default diagnostic tools](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/tools/DefaultDiagnosticTools.java)
- [Investigation policy tests](../../helix-agent-core/src/test/java/io/helix/agent/core/investigation/InvestigationOrchestratorTest.java)
