# Upgrade Advisor for Apache NiFi 2.x

## Overview

The `upgradeAdvisor.sh` script is designed to detect deprecated components and features in NiFi configuration exports (versioned flows, controller services, and reporting tasks) before migrating to NiFi 2.x.
The script scans the specified directory and its subdirectories for exports, analyzes them, and generates a report (`upgradeAdvisorReport.txt`) that lists all components and features that might be affected by the upgrade in each export file, along with recommendations on how to mitigate upgrade issues.

## Running the Advisor with Bash

### Prerequisites for Bash

Ensure you have the following tools installed:
1. Bash – a command shell for running the script.
2. jq – a command-line utility for processing JSON data in Bash. You can download jq [here](https://jqlang.org/download/).

### Usage with Bash

To run the advisor, execute the following command (replace parameters as needed):
```bash
bash <pathToScripts>/upgradeAdvisor.sh <pathToExports>
```

The parameters referenced in the command above are described in the table below.

| Parameter      | Required | Default | Description                                                                                                                              |
|---------------|----------|---------|------------------------------------------------------------------------------------------------------------------------------------------|
| pathToExports | Y        | .       | Location of NiFi configuration exports, including flows, controller services, reporting tasks, or related configuration files. |
| pathToScripts | N        | .       | Path to the directory containing the `upgradeAdvisor.sh` script. |

The report file `upgradeAdvisorReport.txt` will be placed in the current working directory.

## Running the Advisor as Docker container

### Prerequisites for Docker

Ensure you have the following tool installed:
1. Docker – any version of Docker Engine or a Docker-compatible container runtime.

### Usage for Docker

To run the advisor, execute the following command (replace parameters as needed):
```bash
docker run --rm -v "<pathToScripts>:/advisor" -v "<pathToExports>:/export" -w "/advisor/" --entrypoint=/bin/bash ghcr.io/netcracker/nifi-registry:1.0.3 upgradeAdvisor.sh /export/
```

The parameters referenced in the command above are described in the table below.

| Parameter      | Required | Default   | Description                                                                                                                              |
|---------------|----------|-----------|------------------------------------------------------------------------------------------------------------------------------------------|
| pathToExports | Y        | .         | Location of NiFi configuration exports, including flows, controller services, reporting tasks, or related configuration files. |
| pathToScripts | Y        | ./advisor | Path to the directory containing the `upgradeAdvisor.sh` script. |

The report file `upgradeAdvisorReport.txt` will be placed in the `<pathToScripts>` directory.
