# ADR 0002: Classify diagnostic evidence by reliability

## Context

JVM diagnostics mix observations of very different strength. A deadlock cycle explicitly present in a thread dump is not equivalent to a heap-growth heuristic, and a calculated GC percentile is not equivalent to a model-generated explanation.

If all findings are presented uniformly, users and an LLM can mistake a plausible signal for a proven root cause. A single confidence number does not solve this problem because confidence combines source quality, calculation quality, and diagnostic interpretation into an opaque value.

## Decision

Every finding carries a reliability classification in addition to severity and confidence:

- **FACT:** directly established by a supporting artifact within that artifact's semantics.
- **DERIVED:** calculated deterministically from normalized observations.
- **HEURISTIC:** an interpretation based on thresholds, patterns, or incomplete evidence that requires corroboration.

Findings retain artifact identifiers and evidence events. Investigation tools return explicit evidence references, and tool calls are recorded in the final investigation trace.

Reliability and severity remain independent. A highly reliable observation can be informational, while a high-severity heuristic can still require validation. An LLM may explain or connect findings, but it does not upgrade their reliability classification.

## Alternatives considered

### Confidence score only

A decimal score looks precise but does not explain whether a claim was observed, calculated, or inferred. Scores also drift as thresholds change.

### Binary confirmed/unconfirmed status

This is easier to display but loses the important distinction between deterministic calculations and pattern-based interpretations.

### Let the LLM assess evidence quality

This makes trust dependent on provider behavior and prompt compliance. Reliability must be established before probabilistic reasoning begins.

## Consequences

Benefits:

- Reports communicate epistemic strength instead of only operational urgency.
- Agent policies can require stronger evidence for causal conclusions.
- False-positive audits can target heuristic thresholds without questioning factual parsing.
- Findings remain reviewable without reproducing an LLM conversation.

Costs and risks:

- Classification requires judgment and consistent review across analyzers.
- `FACT` means the artifact contains the observation; it does not prove an external business symptom or a broader causal claim.
- Confidence remains useful but must not be presented as statistically calibrated until Helix has an evaluation dataset.

## Evidence in the repository

- [`FindingReliability`](../../helix-core/src/main/java/io/helix/core/model/FindingReliability.java)
- [`Finding`](../../helix-core/src/main/java/io/helix/core/model/Finding.java)
- [`EvidenceReference`](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/EvidenceReference.java)
- [`ToolResult`](../../helix-agent-core/src/main/java/io/helix/agent/core/investigation/ToolResult.java)
- [Validated OOM report excerpt](../../examples/reports/oom-investigation.txt)
