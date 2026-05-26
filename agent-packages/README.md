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

2. Add the desired packages to your `apm.yml` file as dependencies. For example:

   ```yaml
   dependencies:
     apm:
       - Netcracker/qubership-nifi/agent-packages/adapt-nifi-flows-to-2-x#<version>
   ```

3. Install the packages by running:

   ```bash
    apm install
   ```

4. Once installed, you can import and use the packages in your AI agent code as needed.

## Available Packages

- **adapt-nifi-flows-to-2-x**: This package provides skill to help adapt Apache NiFi flows to version 2.x. It includes
  scripts and templates to facilitate the migration process.
