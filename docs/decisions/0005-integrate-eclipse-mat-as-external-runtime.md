# ADR 0005: Integrate Eclipse MAT as an external runtime

## Context

Correct HPROF analysis requires mature support for large heap snapshots, object graphs, dominators, retained sizes, GC roots, and class-loader relationships. Implementing an HPROF graph engine would be a major project with substantial correctness and memory-management risk.

Eclipse Memory Analyzer provides that capability, but it is distributed as an Eclipse application and OSGi bundle set rather than a conventional collection of Maven Central libraries suitable for direct embedding.

## Decision

Helix delegates real HPROF graph analysis to an installed Eclipse MAT runtime. Users configure `HELIX_MAT_HOME` to a MAT installation or its `plugins` directory.

At startup, Helix verifies that the required MAT API, parser, and HPROF bundles exist. The HPROF parser loads MAT through an isolated runtime boundary, executes supported heap queries, and maps the results into Helix-owned heap records. The memory analyzer depends only on those normalized records and has no MAT dependency.

Tests keep algorithmic analysis separate from the external runtime. Unit tests use normalized heap data; the MAT-backed integration test runs only when a valid installation and fixture are available.

## Alternatives considered

### Implement an HPROF parser and graph engine in Helix

This removes the external installation but would consume significant effort on a solved low-level problem and create high correctness risk for large production heaps.

### Package MAT bundles inside the Helix distribution

This would simplify setup but adds licensing, distribution-size, OSGi-version, and platform-maintenance concerns that are premature for the current CLI.

### Shell out to a manually authored MAT report

This is operationally simple but provides a weaker integration contract and makes structured query evolution and error handling harder.

## Consequences

Benefits:

- Helix reuses a mature heap-analysis engine.
- Memory analyzers remain independent of MAT and easy to unit test.
- MAT-specific loading and failures are isolated in the HPROF parser module.
- The integration can evolve without changing the common evidence model.

Costs and risks:

- Heap analysis requires a separate local MAT installation.
- MAT and Eclipse bundle changes can break runtime loading.
- Integration tests are environment-dependent and cannot provide full CI coverage by default.
- Large heap processing still requires explicit resource limits and operational guidance before server deployment.

## Evidence in the repository

- [`MatHomeResolver`](../../helix-parsers/helix-parser-hprof/src/main/java/io/helix/parser/hprof/MatHomeResolver.java)
- [`MatHeapSnapshotReader`](../../helix-parsers/helix-parser-hprof/src/main/java/io/helix/parser/hprof/MatHeapSnapshotReader.java)
- [`HprofArtifactParser`](../../helix-parsers/helix-parser-hprof/src/main/java/io/helix/parser/hprof/HprofArtifactParser.java)
- [MAT runtime resolution tests](../../helix-parsers/helix-parser-hprof/src/test/java/io/helix/parser/hprof/MatHomeResolverTest.java)
- [Optional MAT integration test](../../helix-parsers/helix-parser-hprof/src/test/java/io/helix/parser/hprof/MatHeapSnapshotReaderIntegrationTest.java)
