# ADR 0004: Isolate model providers behind investigation adapters

## Context

Model providers expose similar concepts—system instructions, messages, tool declarations, tool calls, and tool results—but use different HTTP APIs and wire formats. Coupling Helix's investigation policy to Gemini's JSON representation would spread provider-specific behavior into orchestration and diagnostic tools.

Helix also needs to preserve its evidence policy if a model provider changes. Provider portability is useful only when safety and tool semantics remain in Helix rather than being reimplemented by every adapter.

## Decision

The agent core defines provider-neutral `InvestigationModel`, `InvestigationSession`, `InvestigationRequest`, `InvestigationTurn`, `ToolDefinition`, and `ToolCall` contracts.

`GeminiReasoningProvider` is an adapter that translates those contracts to and from Gemini's API. It handles authentication, model configuration, function declarations, conversation history, function calls, function responses, and provider errors. It does not execute diagnostic tools or decide whether the evidence policy is satisfied.

Adapters are discovered through Java `ServiceLoader`. Gemini is currently the only implemented tool-calling adapter; multi-provider support is an architectural extension point, not a shipped capability.

## Alternatives considered

### Call Gemini directly from the orchestrator

This reduces the initial class count but couples investigation state, policy, and tests to one external API.

### Adopt a general LLM framework immediately

A framework can accelerate provider coverage, but tool calling is a trust boundary for Helix. A lowest-common-denominator abstraction or opaque conversation state would make evidence-sensitive failures harder to debug. Thin adapters keep the protocol visible.

### One adapter per model

Models that share a provider protocol should share an adapter. Model selection is configuration unless the API or tool semantics materially differ.

## Consequences

Benefits:

- Diagnostic tools and evidence gates are provider-independent.
- Provider request/response logic can be contract-tested in isolation.
- Adding a provider does not require changes to artifact parsers or analyzers.
- External API churn is contained in a small module.

Costs and risks:

- Every provider requires maintained translation and error handling.
- Subtle differences in parallel calls, call identifiers, and conversation state require provider-specific tests.
- The CLI currently selects the first available `InvestigationModel`; explicit provider selection must be added before multiple adapters can be enabled safely.

## Evidence in the repository

- [`InvestigationModel`](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/InvestigationModel.java)
- [`InvestigationSession`](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/InvestigationSession.java)
- [`InvestigationRequest`](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/InvestigationRequest.java)
- [`GeminiReasoningProvider`](../../helix-reasoning/src/main/java/io/helix/reasoning/gemini/GeminiReasoningProvider.java)
- [Gemini adapter tests](../../helix-reasoning/src/test/java/io/helix/reasoning/gemini/GeminiReasoningProviderTest.java)
