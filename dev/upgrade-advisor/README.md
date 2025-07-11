# Upgrade Advisor for Apache NiFi 2.x

## Scripts overview

The 'upgradeAdvisor.sh' script is designed to detect deprecated components and features in NiFi configuration exports (versioned flows, controller services, reporting tasks) before migrating to NiFi 2.x.
The script scans current directory and subdirectories for exports, analyzes them and generates a report 'updateAdvisorResult.txt', which lists all components and features that might be affected by the upgrade.
Example of running a script:

`bash upgradeAdvisor.sh <pathToExports>`

As input arguments used in script:

| Argument      | Required | Default | Description                                                                                                                              |
|---------------|----------|---------|------------------------------------------------------------------------------------------------------------------------------------------|
| pathToExports | Y        | .       | It's location of NiFi configuration exports implying flows, controller services, reporting tasks (or some configuration files for them). |
