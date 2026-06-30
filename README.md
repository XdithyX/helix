# Helix

Helix is a unified Java diagnostic platform. The full project goal is to ingest JVM artifacts
such as heap dumps, thread dumps, GC logs, JFR recordings, crash logs, and live JVM data, then
produce correlated findings and reports.

This repository currently contains Phase 1 through Phase 7 work:

- Maven multi-module scaffold for the specified project structure
- `helix-core` domain models, SPI interfaces, event bus, and progress monitor
- `helix-cli` with `analyze` and `version` commands
- `helix-reporter-text` and `helix-reporter-json`
- `helix-parser-hprof` with a MAT-backed HPROF parser
- `helix-analyzer-memory` with Phase 2 heap findings
- `helix-parser-threaddump` with HotSpot, OpenJ9, JFR-text, and multi-snapshot parsing
- `helix-analyzer-threads` with Phase 3 thread findings
- `helix-parser-gclog` with unified logging, JDK 8 legacy GC logs, and OpenJ9 verbose GC parsing
- `helix-analyzer-gc` with Phase 4 pause, throughput, cause, leak-gradient, allocation, and promotion findings
- `helix-parser-jfr` with `jdk.jfr.consumer.RecordingFile`-based event extraction
- `helix-analyzer-jfr` with flame graph, allocation, lock, I/O, deopt, and virtual-thread pinning findings
- `helix-parser-crashlog` with `hs_err_pid` fatal error log parsing
- `helix-analyzer-crash` with crash classification, native library, stack corruption, and environment findings
- `helix-correlation` with Phase 7 timeline correlation rules and narrative generation
- GitHub Actions CI running `mvn verify`

Heap dump, thread dump, GC log, JFR, crash log, and cross-artifact correlation
analysis are currently implemented. Live attach, HTML reports, reasoning, and web UI
remain future phases.

## Requirements

- Java 21
- Maven 3.9+

## Build

```bash
mvn verify
```

## Run

```bash
mvn -pl helix-cli -am package
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar version
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar --output text analyze path/to/thread-dump.txt
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar --output text analyze --type gclog path/to/gc.log
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar --output text analyze --type jfr path/to/profile.jfr
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar --output text analyze --type crash path/to/hs_err_pid123.log
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar --output text analyze --correlate path/to/gc.log path/to/profile.jfr
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar --output json analyze path/to/heap.hprof
```

Reports are written to `./helix-reports/` by default.

## HPROF Parsing

HPROF parsing is backed by Eclipse MAT. MAT 1.16.1 is distributed as an Eclipse RCP
application, not as standard Maven Central coordinates. To analyze real `.hprof` files,
install the standalone MAT application and set `HELIX_MAT_HOME` to either the MAT
installation directory or that installation's `plugins/` directory.

- `org.eclipse.mat.api_1.16.1.202501091339.jar`
- `org.eclipse.mat.parser_1.16.1.202501091339.jar`
- `org.eclipse.mat.hprof_1.16.1.202501091339.jar`

```bash
export HELIX_MAT_HOME=/path/to/MemoryAnalyzer.app/Contents/Eclipse
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar --output text analyze path/to/heap.hprof
```

Helix validates that `HELIX_MAT_HOME` contains at least the `org.eclipse.mat.api`,
`org.eclipse.mat.parser`, and `org.eclipse.mat.hprof` bundles, then uses MAT's
headless Eclipse runtime to run lightweight heap queries.

## Thread Dump Parsing

Thread dump parsing is built into Helix and does not require external tools.

```bash
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar \
  --output text \
  analyze --type thread helix-core/src/test/resources/test-data/threads/deadlock-two-thread.txt
```

The Phase 3 parser supports HotSpot `jstack` style dumps, OpenJ9 `3XMTHREADINFO`
dumps, simple JFR text thread-state events, and files containing multiple timestamped
snapshots. Malformed or truncated thread blocks are retained as partial parses with
warnings instead of failing the whole artifact.

## GC Log Parsing

GC log parsing is built into Helix and does not require external tools.

```bash
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar \
  --output text \
  analyze --type gclog helix-core/src/test/resources/test-data/gc/g1gc-high-pause.log
```

The Phase 4 parser supports JDK unified logging (`-Xlog:gc*`), JDK 8 legacy GC
logs, OpenJ9 verbose GC XML fragments, and truncated logs from rotation. The analyzer
reports pause percentiles, throughput, GC cause breakdown, explicit `System.gc()`,
heap-after-GC leak gradients, humongous/metadata patterns, allocation rate, and
promotion rate.

## JFR Parsing

JFR parsing uses the JDK's built-in `jdk.jfr.consumer.RecordingFile` API and does
not require JMC.

```bash
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar \
  --output text \
  analyze --type jfr path/to/profile.jfr
```

The Phase 5 parser extracts GC, execution sample, allocation, monitor, file/socket
I/O, deoptimization, compiler inlining failure, virtual-thread pinning, and native
library load events. The analyzer emits CPU flame graph data, allocation hotspots,
lock contention, slow I/O, deoptimization summaries, and virtual-thread pinning
findings.

## Crash Log Parsing

Crash log parsing supports HotSpot `hs_err_pid<N>.log` fatal error logs.

```bash
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar \
  --output text \
  analyze --type crash helix-core/src/test/resources/test-data/crash/hs_err_pid_segv.log
```

The Phase 6 parser extracts signal/exception details, problematic frame, native
frames, JVM state, heap info, VM flags, environment data, compiled methods, dynamic
libraries, and register dumps. The analyzer classifies crash categories, identifies
native library ownership, flags stack-corruption clues, and reports environment
mismatches.

## Correlation

Cross-artifact correlation is enabled with `--correlate`.

```bash
java -jar helix-cli/target/helix-cli-0.1.0-SNAPSHOT.jar \
  --output text \
  analyze --correlate path/to/gc.log path/to/profile.jfr path/to/hs_err_pid123.log
```

The Phase 7 correlator time-aligns artifact timeline events, applies the mandatory
correlation rules, and adds a narrative paragraph to the report. Drools `.drl` rule
resources are bundled under `helix-correlation/src/main/resources/rules/` and executed
with the embedded Drools runtime; a deterministic Java fallback remains available if
rule loading fails.
