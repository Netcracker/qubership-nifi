# Upgrade Advisor for Apache NiFi 2.x

## Overview

The `upgradeAdvisor.sh` script is designed to detect deprecated components and features in NiFi configuration exports (versioned flows, controller services, and reporting tasks) before migrating to NiFi 2.x.
The script scans the specified directory and its subdirectories for exports, analyzes them, and generates a report that lists all components and features that might be affected by the upgrade in each export file, along with recommendations on how to mitigate upgrade issues. The name of the report file is specified using the `<reportFileName>` input parameter for the script.

## Report File Structure

The report file obtained as a result of the upgrade advisor operation contains the following columns:

| Column name                        | Description                                                                                                                                     |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Flow name                          | Path to the file containing the data flow description in JSON format.                                                                           |
| Level                              | Severity of the issue: error (if there is no known solution or solution can be applied only together with upgrade to 2.x) or warning (if there is known solution that can be applied on 1.x).                                                                                             |
| Issue                              | Short description of the identified problem.                                                                                                    |
| Solution                           | Recommended approach to resolve the issue. `Required NiFi version for solution` column defines Apache NiFi version needed to apply solution. If the required version is newer than versions that should be supported by the application, then two flow versions must be created: existing flow for older versions and new one for newer versions (including 2.x). |
| Required NiFi version for solution | Minimum Apache NiFi version required to apply the proposed solution.                                                                            |
| Component                          | Name and unique identifier of the component where the issue occurred.                                                                      |
| Process Group                      | Name and identifier of the process group containing the problematic component.                                                             |

## Running the Advisor with Bash

### Prerequisites for Bash

Ensure you have the following tools installed:
1. Bash – a command shell for running the script.
2. jq – a command-line utility for processing JSON data in Bash. You can download jq from [official site](https://jqlang.org/download/).

### Usage with Bash

To run the advisor, execute the following command (replace parameters as needed, parameters in square brackets are optional):
```bash
bash <pathToScripts>/upgradeAdvisor.sh [<pathToExports>] [<csvSeparator>] [<reportFileName>]
```

The parameters referenced in the command above are described in the table below.

| Parameter      | Required | Default                    | Description                                                                                                                                                                |
|----------------|----------|----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| pathToScripts  | Y        | .                          | Path to the directory containing the `upgradeAdvisor.sh` script.                                                                                                           |
| pathToExports  | N        | .                          | Location of NiFi configuration exports, including flows, controller services, reporting tasks, or related configuration files.                                             |
| csvSeparator   | N        | `comma`                    | Character for separating values in the csv file. Available values: `comma` (corresponds to ',') and `semicolon` (corresponds to ';'). |
| reportFileName | N        | `upgradeAdvisorReport.csv` | Name of the report file with flow information.                                                                                                                             |

Report filename is defined by `<reportFileName>` parameter. This file will be placed in the current working directory.

## Running the Advisor as Docker container

### Prerequisites for Docker

Ensure you have the following tool installed:
1. Docker – any version of Docker Engine or a Docker-compatible container runtime.

### Usage for Docker

To run the advisor, execute the following command (replace parameters as needed, parameters in square brackets are optional):
```bash
docker run --rm -v "<pathToScripts>:/advisor" -v "<pathToExports>:/export" -w "/advisor/" --entrypoint=/bin/bash ghcr.io/netcracker/nifi-registry:1.0.3 upgradeAdvisor.sh /export/ [<csvSeparator>] [<reportFileName>]
```

The parameters referenced in the command above are described in the table below.

| Parameter      | Required | Default                    | Description                                                                                                                                                                |
|----------------|----------|----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| pathToScripts  | Y        | .                          | Path to the directory containing the `upgradeAdvisor.sh` script.                                                                                                           |
| pathToExports  | Y        | .                          | Location of NiFi configuration exports, including flows, controller services, reporting tasks, or related configuration files.                                             |
| csvSeparator   | N        | `comma`                    | Character for separating columns in csv file. Parameter has two available values -- comma (corresponds to ',') and semicolon (corresponds to ';'). Default is comma (','). |
| reportFileName | N        | `upgradeAdvisorReport.csv` | Name of the report file with flow information.                                                                                                                             |


Report filename is defined by `<reportFileName>` parameter. This file will be placed in the `<pathToScripts>` directory.
