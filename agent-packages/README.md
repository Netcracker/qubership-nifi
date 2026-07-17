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

3. Install the declared dependencies and compile their instructions for the configured agent targets:

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
