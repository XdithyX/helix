# Helix

Helix is a Java 21, Maven multi-module project for deterministic, post-incident JVM
diagnostics. Keep parsing and analysis deterministic; AI and MCP layers should query
normalized evidence rather than replace it.

## Pre-flight checks

Before the first code edit or test in a task:

1. Run `git remote -v`, `git status --short --branch`, and `git branch --show-current`.
2. If the working tree is clean and the current branch tracks a remote, run
   `git fetch --prune origin` followed by `git pull --ff-only`.
3. If the tree is dirty, preserve the existing changes. Do not stash, discard, or pull;
   report the state before continuing.
4. If the branch has diverged or cannot fast-forward, stop and ask the user.
5. Read `README.md`, the relevant `pom.xml` files, and any more specific nested
   `AGENTS.md` before changing code.

For parallel work, the coordinating agent performs the fetch and pull once before
delegation. Subagents and reviewers must not fetch, pull, or switch branches in the
shared worktree; they should verify that they are reviewing the same HEAD.

## Development rules

- Make the smallest change that fully solves the task. Avoid unrelated refactors.
- Preserve user changes and never use destructive Git commands.
- Add or update tests with behavior changes. Do not commit generated `target/` files.

## Build and test

Run focused tests while iterating:

    mvn -q -pl <module> -am test

Before handing off a code change, run the full reactor when practical:

    mvn -q verify

## Handoff

- Run `git diff --check` and inspect `git status --short`.
- Summarize changed behavior, tests run, warnings, and any unverified boundary.
