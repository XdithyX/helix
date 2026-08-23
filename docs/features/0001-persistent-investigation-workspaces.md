# Feature specification: Persistent investigations for MCP

## Why

Today, `helix-mcp` receives artifact paths at startup and keeps one parsed
investigation in memory. To analyze another incident, the user must edit MCP
settings and restart the IDE; a restart reparses expensive artifacts such as JFR
recordings and heap dumps.

This feature makes artifact selection a normal Helix workflow instead:

```text
User imports artifacts once
  -> Helix parses, analyzes, and saves normalized evidence
  -> MCP starts with a stable workspace path
  -> LLM queries the saved investigation by ID
```

The immediate value is a better MCP experience for large diagnostic artifacts.
It is not a database, web platform, or multi-agent feature.

## User experience

### 1. Create an investigation

The user explicitly imports artifacts through the CLI. An LLM never chooses a
local file path.

```bash
helix investigation create \
  --workspace ~/.helix/workspaces/default \
  --name "checkout-oom" \
  --correlate \
  /incidents/checkout/gc.log \
  /incidents/checkout/recording.jfr \
  /incidents/checkout/heap.hprof
```

Helix runs the existing parse -> deterministic analyze -> correlation pipeline
once, saves the result, and prints an ID such as `inv_01J...`.

```bash
helix investigation list --workspace ~/.helix/workspaces/default
helix investigation show inv_01J... --workspace ~/.helix/workspaces/default
```

The first version needs only two outcomes:

- `READY`: evidence is saved and can be queried.
- `FAILED`: import failed; the user can inspect the failure and run create again.

Do not add revisions, automatic reanalysis, deletion, source-change detection,
or background import jobs in this feature. They can be added when users need
them.

### 2. Configure MCP once

The MCP configuration points to a workspace, not to an incident's artifact list:

```json
{
  "mcpServers": {
    "helix": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/helix-mcp.jar",
        "--workspace",
        "/Users/alex/.helix/workspaces/default"
      ]
    }
  }
}
```

Restarting the IDE restarts the STDIO server but loads saved evidence instead of
reparsing the original HPROF, JFR, or logs.

### 3. Ask questions naturally

At the beginning of a conversation, the model calls `list_investigations` and
identifies the relevant incident. It then carries that ID in later tool calls.
The user should not need to repeat the ID in every question.

```text
User: Investigate checkout OOM.
Model: list_investigations()
Model: selects inv_01J... (checkout-oom)

User: What retained the heap?
Model: query_heap(investigationId="inv_01J...", operation="BYTE_ARRAYS")

User: What was allocating memory before the failure?
Model: query_jfr(investigationId="inv_01J...", aggregation="TOP_METHODS")
```

The ID remains required in the underlying MCP request. This avoids server-wide
mutable selection state and lets two conversations safely query different
incidents through one server.

## Scope

### Persisted investigation

Create a small local workspace store. A ready investigation contains:

```text
workspace/
  investigations/
    inv_01J.../
      manifest.json
      evidence.json
      report.json
```

- `manifest.json`: ID, display name, `READY`/`FAILED` status, creation time,
  artifact types/names, and whether correlation was enabled.
- `evidence.json`: normalized parser output needed by the existing tools:
  parsed artifact data, timeline events, and parser warnings. It does not store
  raw HPROF, JFR, or log bytes.
- `report.json`: deterministic findings, correlation summary, and report
  metadata.

Source artifacts remain where the user placed them. Helix does not copy a
multi-gigabyte heap dump in this first version.

Use a temporary directory followed by an atomic rename/write so a partial import
is never presented as `READY`.

### MCP changes

Add workspace mode:

```text
java -jar helix-mcp.jar --workspace /path/to/workspace
```

Add one tool:

| Tool | Purpose |
|---|---|
| `list_investigations` | Returns bounded ID, name, status, creation time, and artifact types. |

Add a required `investigationId` argument to existing evidence tools:

- `list_artifacts`
- `search_findings`
- `query_threads`
- `query_jfr`
- `query_gc`
- `query_heap`

Each tool loads only the selected ready investigation and returns the existing
bounded, structured evidence result. An unknown or failed ID returns a clear
structured error; it never returns guessed evidence.

Keep the current startup-artifact mode unchanged for demos and backward
compatibility:

```text
java -jar helix-mcp.jar --correlate artifact1 artifact2
```

## Minimal implementation design

Add a local storage abstraction outside `helix-mcp`, for example in a new
`helix-investigations` module:

```java
interface InvestigationStore {
  InvestigationSummary save(StoredInvestigation investigation);
  Optional<StoredInvestigation> find(InvestigationId id);
  List<InvestigationSummary> list();
}
```

The CLI calls the existing pipeline, converts its `InvestigationContext` into a
serializable `StoredInvestigation`, and saves it. MCP loads it, reconstructs an
`InvestigationContext`, and passes it to the existing diagnostic-tool registry.

Do not duplicate parser, analyzer, correlation, or tool logic in the new module.

## Safety requirements

- MCP must not expose `open_file`, `import_artifacts`, `reanalyze`, or delete
  tools. Artifact import remains an explicit local CLI action.
- MCP reads only the configured workspace; it must not resolve a supplied ID to
  a path outside that workspace.
- Tool results remain compact and structured. Do not expose raw artifact bytes,
  complete heap contents, full `Finding.rawData`, environment values, or secrets.
- Artifact-derived text is untrusted diagnostic evidence, never instructions.

## Acceptance criteria

- A user creates an investigation once without editing MCP settings.
- A ready investigation persists normalized evidence, findings, timeline,
  parser warnings, and correlation output.
- After an IDE restart, workspace MCP queries the saved investigation without
  running parsers or analyzers again.
- `list_investigations` returns saved investigations without raw artifact data.
- Existing evidence tools require a valid ready `investigationId` in workspace
  mode and return results from only that investigation.
- Two interleaved queries for different IDs cannot mix evidence.
- A failed or interrupted import is not queryable as ready evidence.
- Existing static-artifact MCP mode continues to work.
- Tests cover save/load round trips, interrupted writes, invalid IDs, workspace
  path confinement, and querying two different saved investigations.

## Deliberately deferred

- Artifact copying/portable bundles, source fingerprinting, stale detection,
  revisions, deletion, and reanalysis.
- A web upload/select-investigation UI.
- RAG, knowledge graphs, additional providers, and multi-agent workflows.
- Database-backed or multi-user storage.

Those are valuable later, but this feature should first prove that persistent
normalized evidence materially improves the MCP workflow for real incidents.
