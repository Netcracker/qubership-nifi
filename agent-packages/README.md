# Agent packages

This directory contains [Agent Package Manager (APM)](https://microsoft.github.io/apm/)
packages for working with Qubership NiFi repositories. Each package deploys agent
primitives, such as instructions, skills, prompts, or hooks, to the agent targets
configured in the consuming repository.

## Prerequisites

1. Install [APM](https://microsoft.github.io/apm/#install-apm), then initialize APM in
    the consuming repository if it does not already contain an `apm.yml` file:

    ```bash
    apm init
    ```

2. Add the required package to `dependencies.apm` in `apm.yml`.
Pin the dependency to a Git tag, branch, or commit SHA that contains the package:

    ```yaml
    dependencies:
      apm:
        - Netcracker/qubership-nifi/agent-packages/adapt-nifi-flows-to-2-x#<git-ref>
    ```

3. Install the declared dependencies for the configured agent targets:

    ```bash
    apm install
    ```

`apm install` deploys skills, prompts, and hooks and writes the resolved dependency graph to `apm.lock.yaml`.
Do not edit deployed or compiled files directly; update package version in `apm.yml` and run install command again.

## Available packages

### `adapt-nifi-flows-to-2-x`

Applies recommendations from a NiFi Upgrade Advisor report to exported NiFi 1.x flow JSON files.

The skill combines deterministic Python transformations with agent-assisted decisions
for parameter contexts, cross-file controller services, and script translation. It
preserves each JSON file's detected indentation, separator spacing, trailing newline,
and key order.

Requirements and inputs:

- Python 3.10 or later.
- The `upgradeAdvisorReport.csv` produced by the Upgrade Advisor script.
- The directory containing the exported flow JSON files.

See the
[`adapt-nifi-flows-to-2-x` workflow](adapt-nifi-flows-to-2-x/.apm/skills/adapt-nifi-flows-to-2-x/SKILL.md)
for the complete migration procedure and the manual review points.

### `qubership-nifi-linters`

Provides the `/lint <module-path>` prompt, which runs codespell, checkstyle, markdownlint,
editorconfig-checker, and textlint against a module, then guides the agent through fixing
the findings.

The prompt reuses the consumer repository's linter configuration and excludes build
output, test data, and deployed APM agent content. A missing linter is reported and
skipped, so install only the tools required for the checks you want to run.

Since version 2.0.0 the package no longer contains the per-file linter hook. Add
[`qubership-nifi-lint-hook-claude`](#qubership-nifi-lint-hook-claude) and
[`qubership-nifi-lint-hook`](#qubership-nifi-lint-hook) to get it.

### `qubership-nifi-lint-hook-claude`

Provides a non-blocking `PostToolUse` hook for Claude Code. After the agent writes or
edits a file, the hook runs codespell, editorconfig-checker, checkstyle, markdownlint, and
textlint on it and returns any findings to the agent. It uses the same linter
configuration, exclusions, and missing-linter handling as the `/lint` prompt.

The hook command is anchored to `${CLAUDE_PROJECT_DIR}`, so it keeps working after the
agent changes into a subdirectory. Declare the package with `targets: [claude]`:

```yaml
dependencies:
  apm:
    - git: Netcracker/qubership-nifi
      path: agent-packages/qubership-nifi-lint-hook-claude
      ref: <git-ref>
      targets: [claude]
```

Without `targets:`, APM also deploys the hook to Codex and Cursor, where its command fails
because neither sets `CLAUDE_PROJECT_DIR`.

See the [linter hook documentation](qubership-nifi-lint-hook-claude/.apm/hooks/README.md) for
tool prerequisites, configuration lookup, and a manual dry-run example.

### `qubership-nifi-lint-hook`

Provides the same `PostToolUse` linter hook for Codex and Cursor. Its command is relative
to the working directory, because neither harness sets `CLAUDE_PROJECT_DIR`. Declare the
package with `targets: [codex, cursor]`:

```yaml
dependencies:
  apm:
    - git: Netcracker/qubership-nifi
      path: agent-packages/qubership-nifi-lint-hook
      ref: <git-ref>
      targets: [codex, cursor]
```

Without `targets:`, APM also deploys the hook to Claude Code, which then lints each file
twice.

The package carries its own copy of the hook script, identical to the one in
`qubership-nifi-lint-hook-claude`. Its prerequisites are the same; see the
[linter hook documentation](qubership-nifi-lint-hook-claude/.apm/hooks/README.md).

### `nifi-development-kit`

Provides two skills: `nifi-custom-component-developer-skill` for custom NiFi components, and `nifi-flow-builder` for
NiFi flow definitions.

The `nifi-custom-component-developer-skill` skill carries conventions and correctness
rules for writing or reviewing custom Apache NiFi components (Processors, Controller
Services, Reporting Tasks), extracted from the existing qubership-nifi codebase rather
than the generic NiFi API docs.

The skill applies whenever an agent creates, extends, or reviews a component, covering:

- Class-level annotation choice and ordering.
- `PropertyDescriptor` and `Relationship` naming conventions.
- FlowFile presence checks for `INPUT_REQUIRED` vs `INPUT_ALLOWED` components.
- Instance field initialization scope (`@OnScheduled` vs `onTrigger`).
- Batch writes to external systems (accumulate/flush/commit/rollback, retry vs failure
  routing).
- Unit testing patterns for processors, controller services, and reporting tasks.

See the
[`nifi-custom-component-developer-skill` skill](nifi-development-kit/.apm/skills/nifi-custom-component-developer-skill/SKILL.md)
for the full rule set and its bundled reference files.

The `nifi-flow-builder` skill builds, modifies, reviews, and debugs NiFi flow definition JSON: flow exports, versioned
flow snapshots, and process group exports. It takes component types, bundle coordinates, property keys, allowable
values, and relationships from a NiFi Knowledge Base built from the target NiFi version, not from the agent's memory.
A paired instructions file triggers the skill when the agent creates or edits a NiFi flow definition JSON file. The
skill's own description also triggers it when the agent picks a processor or controller service.

Requirements and inputs:

- Python 3.12 or later. The bundled scripts use only the standard library, except that certificate mode in
  `scripts/verify_live.py` needs either the `cryptography` package or the `openssl` command to read the PKCS#12 file.
- A NiFi Knowledge Base for the target NiFi version, built with
  [`qubership-nifi-kb-builder-tool`](../qubership-nifi-tools/qubership-nifi-kb-builder-tool/README.md). The skill
  looks for it under `--kb`, then `NIFI_KB_PATH`, then inside the workspace.
- Optional: a running NiFi instance, to import a flow and read back its validation state with
  `scripts/verify_live.py`.

See the
[`nifi-flow-builder` skill](nifi-development-kit/.apm/skills/nifi-flow-builder/SKILL.md)
for the full workflow and its reference files.

### `qubership-nifi-docs-maintenance`

Provides the `update-project-documentation` skill: keeps `README.md`,
`docs/user-guide.md`, `docs/administrator-guide.md`, and
`docs/installation-guide.md` in sync after a significant change, such as an
environment variable added, removed, or changed, a new NiFi Processor,
Controller Service, or Reporting Task, or a new tool/child module.

The skill covers:

- Mapping a change to the doc(s) it affects.
- Regenerating `docs/user-guide.md` via the `qubership-nifi-docs-generator`
  Maven plugin instead of hand-editing its generated tables.
- Conventions for the environment variables table in
  `docs/administrator-guide.md`.
- Keeping the root `README.md` and per-tool `README.md` files consistent when
  a tool or child module is added.

A paired instructions file triggers the skill whenever an environment
variable, NiFi component, or tool/child module is added, removed, or changed.

See the
[`update-project-documentation` skill](qubership-nifi-docs-maintenance/.apm/skills/update-project-documentation/SKILL.md)
for the full workflow.
