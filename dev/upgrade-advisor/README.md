# Upgrade Advisor for Apache NiFi 2.x

## Scripts overview

The 'upgradeAdvisor.sh' script is designed to detect deprecates of components in exported flows when migrating to NiFi 2.4.0. The script generates a report 'updateAdvisorResult.txt', which lists all detected components.
Example of running a script:

`bash upgradeAdvisor.sh <pathToFlow>`

As input arguments used in script:

| Argument               | Required | Default                       | Description                                                 |
|------------------------|----------|-------------------------------|-------------------------------------------------------------|
| pathToFlow             | Y        | ./export                      | Path to the directory where the exported flows are located. |
