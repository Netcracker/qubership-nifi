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

Provides two linter integrations:

- The `/lint <module-path>` prompt runs codespell, checkstyle, markdownlint,
  editorconfig-checker, and textlint against a module, then guides the agent through
  fixing the findings.
- A non-blocking `PostToolUse` hook checks each file after an agent writes or edits it
  and returns any findings to the agent.

Both integrations reuse the consumer repository's linter configuration and exclude
build output, test data, and deployed APM agent content. A missing linter is reported
and skipped, so install only the tools required for the checks you want to run.

See the [linter hook documentation](qubership-nifi-linters/.apm/hooks/README.md) for
tool prerequisites, configuration lookup, and a manual dry-run example.

### `nifi-development-kit`

Provides the `nifi-custom-component-developer-skill` skill: conventions and correctness
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

### `qubership-nifi-troubleshooting`

Provides the `nifi-troubleshooting` skill: diagnoses a qubership-nifi instance on an
Apache NiFi 1.x or 2.x image that fails to start, restarts, hangs, never becomes ready,
or degrades at runtime. The agent works from the pod log, a stack trace, bulletins, or a
described symptom.

The skill matches the evidence against a catalog of problems, most of them reproduced on
qubership-nifi:

- Startup failures: invalid JVM arguments, a changed or missing sensitive key, an
  unreachable Consul, a corrupted `flow.json.gz`, and keystore or truststore errors.
- Repository problems: a content archive that cannot be cleaned or is cleaned too slowly,
  and a full content, FlowFile, or provenance repository.

The keystore and truststore errors and the full content repository are not reproduced yet;
their signatures come from the start scripts and the troubleshooting guide.

The answer quotes the evidence, names the cause, and gives the fix as Consul properties or
environment variables. Where the evidence is not enough, the agent asks for specific items,
such as the previous container's log, the exit code, or a thread dump.

A paired instructions file triggers the skill when the user asks why a qubership-nifi
instance fails or pastes NiFi log output.

See the
[`nifi-troubleshooting` skill](qubership-nifi-troubleshooting/.apm/skills/nifi-troubleshooting/SKILL.md)
for the signature table and the reference files.
