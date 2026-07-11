# Heap Test Data

Phase 2 analyzer tests use normalized heap summaries so they remain small and license-clean.

Real `.hprof` fixture dumps are intentionally not committed yet. When adding them, keep each file
under 50 MB, document how it was generated, and ensure it contains no proprietary application data.

`helix-parser-hprof` includes an optional integration test that generates a small heap dump at
test runtime and reads it through Eclipse MAT. It runs only when `HELIX_MAT_HOME` points to a
local MAT installation:

```bash
HELIX_MAT_HOME=/path/to/MemoryAnalyzer.app/Contents/Eclipse \
  mvn -pl helix-parsers/helix-parser-hprof -am \
  -Dtest=MatHeapSnapshotReaderIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
