# Agent Packages

This directory contains packages in APM format that can be used by AI agents.

## Prerequisites

To use these packages, you need to install APM (Agent Package Manager). Refer to
the [APM documentation](https://microsoft.github.io/apm/#install-apm) for installation instructions.

## Usage

1. If `apm.yml` is not present in your repository, create it by running the following command:

   ```bash
   apm init
   ```

2. Add the desired packages to your `apm.yml` file as dependencies, like in example below:

   ```yaml
   dependencies:
     apm:
       - Netcracker/qubership-nifi/agent-packages/adapt-nifi-flows-to-2-x#<tag|branch|commit SHA>
   ```

3. Install the packages by running:

   ```bash
    apm install
   ```

4. Once installed, you can import and use the packages in your AI agent code as needed.

## Available Packages

- **adapt-nifi-flows-to-2-x**: This package provides skill to help adapt Apache NiFi flows to version 2.x. It includes
  scripts and templates to facilitate the migration process.
- **qubership-nifi-linters**: This package provides the `/lint` command that runs codespell,
  checkstyle, markdownlint and editorconfig-checker against a module, reusing the repo's configs
  under `.github/linters/`. It also installs a PostToolUse hook that lints each file as it is
  written or edited.
- **nifi-development-kit**: This package provides the `nifi-custom-component-developer-skill`
  skill - conventions and correctness rules for writing or reviewing custom Apache NiFi
  components (Processors, Controller Services, Reporting Tasks): class-level annotations,
  PropertyDescriptor/Relationship naming, FlowFile null-checks, batch writes to external
  systems, instance field initialization scope, and unit testing.
