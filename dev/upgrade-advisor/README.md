# Upgrade Advisor for Apache NiFi 2.x

## Scripts overview

The 'upgradeAdvisor.sh' script is designed to detect deprecated components and features in NiFi configuration exports (versioned flows, controller services, reporting tasks) before migrating to NiFi 2.x.
The script scans current directory and subdirectories for exports, analyzes them and generates a report 'updateAdvisorResult.txt', which lists all components and features that might be affected by the upgrade.

## Example of running a script:

### Running with bash

#### Prerequisites

Make sure you have the following tools installed:
1. Bash - command shell to run the script.
2. Jq - command line utility for processing JSON data in bash. You can download [here](https://jqlang.org/download/), for your OS.

Example of running a script using bash:

`bash upgradeAdvisor.sh <pathToExports>`

As input arguments used in script:

| Argument      | Required | Default | Description                                                                                                                              |
|---------------|----------|---------|------------------------------------------------------------------------------------------------------------------------------------------|
| pathToExports | Y        | .       | It's location of NiFi configuration exports implying flows, controller services, reporting tasks (or some configuration files for them). |

### Running with docker

#### Prerequisites

Make sure you have the following tools installed:
1. Docker - any version of Docker Engine or any compatible docker container runtime.

Example of running a script using docker:

`docker run -it --rm -v "<pathToScripts>:/advisor" -v "<pathToExports>:/export" --entrypoint=/bin/bash ghcr.io/netcracker/nifi-registry:1.0.3 /advisor/upgradeAdvisor.sh /export/`

As input arguments used in comand:

| Argument      | Required | Default   | Description                                                                                                                              |
|---------------|----------|-----------|------------------------------------------------------------------------------------------------------------------------------------------|
| pathToExports | Y        | .         | It's location of NiFi configuration exports implying flows, controller services, reporting tasks (or some configuration files for them). |
| pathToScripts | Y        | ./advisor | Path to the directory with the upgradeAdvisor.sh script.                                                                                 |
