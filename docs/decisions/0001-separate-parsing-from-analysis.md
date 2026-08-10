# ADR 0001: Separate artifact parsing from diagnostic analysis

## Context

Helix accepts artifacts with unrelated formats and semantics: thread-dump text, multiple generations of GC logs, binary JFR and HPROF files, and HotSpot fatal-error logs. Coupling analysis rules directly to those formats would duplicate diagnostic logic, make vendor support invasive, and require large artifact fixtures for every analyzer test.

The formats also evolve independently. A JDK logging change should affect its parser without forcing changes to deadlock, memory-retention, or correlation logic.

## Decision

Artifact-specific modules parse input into domain records owned by `helix-core`. Analyzers consume those normalized records and do not parse source text or call vendor APIs.

The boundary is expressed through `ArtifactParser`, `ParsedArtifact`, and `Analyzer`. Domain-specific records remain explicit—such as `GcEvent`, `ThreadSample`, and `JfrEvent`—instead of being flattened into a generic key-value event schema. Shared concepts such as artifacts, findings, stack frames, evidence, and timeline events are modeled once.

Parsers and analyzers are discovered through Java `ServiceLoader`, allowing a new format implementation to feed existing analyzers without changing the CLI orchestration.

## Alternatives considered

### One parser-and-analyzer pipeline per artifact

This is initially simpler, but repeats severity, reliability, reporting, and correlation behavior. Cross-artifact reasoning would depend on every source representation.

### Normalize everything into generic maps

This minimizes record types but moves schema errors to runtime and makes analyzer contracts difficult to understand or refactor safely.

### Use JMC and MAT objects throughout the system

This preserves vendor detail but leaks large external APIs into the analysis layer and makes unit testing dependent on binary artifacts and external runtimes.

## Consequences

Benefits:

- Analysis rules are testable using compact normalized fixtures.
- Parser improvements do not require analyzer rewrites.
- HotSpot and OpenJ9 sources can converge on shared diagnostic concepts.
- Reporters and tools operate independently of the original artifact format.

Costs and risks:

- Normalization may omit format-specific detail needed by a future analyzer.
- Domain records require deliberate schema evolution and compatibility discipline.
- Parser correctness becomes a critical trust boundary; normalized data is only as accurate as its source mapping.

When a format-specific field becomes diagnostically important, the preferred response is to extend the relevant typed domain record rather than bypass the boundary with raw text.

## Evidence in the repository

- [`ArtifactParser`](../../helix-core/src/main/java/io/helix/core/spi/ArtifactParser.java)
- [`ParsedArtifact`](../../helix-core/src/main/java/io/helix/core/model/ParsedArtifact.java)
- [`Analyzer`](../../helix-core/src/main/java/io/helix/core/spi/Analyzer.java)
- [Thread-dump parser](../../helix-parsers/helix-parser-threaddump/src/main/java/io/helix/parser/threaddump/ThreadDumpArtifactParser.java)
- [Thread analyzer](../../helix-analyzers/helix-analyzer-threads/src/main/java/io/helix/analyzer/threads/ThreadAnalyzer.java)
