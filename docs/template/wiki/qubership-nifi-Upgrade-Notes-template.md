# qubership-nifi <qubership-nifi.version> Upgrade Notes

qubership-nifi <qubership-nifi.version> includes an upgrade to Apache NiFi <nifi.version> and , which may affect existing deployments. This document outlines the key upgrade notes to help ensure a smooth transition to the new version.

## Updating Custom Components to Apache NiFi <nifi.version>

Refer to recommendations provided on the page [Updating Custom Components to new Apache NiFi version](https://github.com/Netcracker/qubership-nifi/wiki/Updating-Custom-Components-to-new-Apache-NiFi-version) were ${nifi.version} = <nifi.version> and ${nifi-api.version} = <nifi-api.version>

## Property changes in Apache NiFi components

Apache NiFi <nifi-api.version> brings changes for properties in multiple components. Full list of changes compared with Apache NiFi <nifi.version> is available on [Apache NiFi <nifi-api.version> Component Properties Delta](https://github.com/Netcracker/qubership-nifi/wiki/Apache-NiFi-<nifi.version>-Component-Properties-Delta) page.

NiFi components modified by these changes contain `migrateProperties` method, which can handle migration from old properties to new.
When importing versioned flow from Registry or uploading process group via upload API (`/nifi-api/process-groups/{pgId}/process-groups/upload`) or UI, `migrateProperties` is automatically applied making process group configuration valid, but creating local changes for versioned process groups. Create APIs (e.g. `/nifi-api/process-groups/${pgId}/controller-services`) do not call `migrateProperties` and input JSON must be adapted to target NiFi version before the call.

## Broken external controller service resolution by name

Since 1.x, Apache NiFi's versioned flows support external controller services and can resolve them by either ID or name.
In Apache NiFi 2.x, this mechanism may not work correctly when a versioned flow is imported from an older export and the migrateProperties method logic is applied to migrate properties. Name-based resolution then fails if the property was migrated (for example, renamed).

### Mitigation options

1. Apply [the update script for 2.x flows](https://github.com/Netcracker/qubership-nifi/tree/main/dev/update-scripts-flow-2.x) with the `--external-cs` option before importing the versioned flow. It looks up external controller services by name on the root PG and updates the IDs inside the versioned flow to match the target Apache NiFi instance. If the export contains no external controller services, this step is skipped.
2. Apply [the update script for 2.x flows](https://github.com/Netcracker/qubership-nifi/tree/main/dev/update-scripts-flow-2.x) with the `--properties` option before importing the versioned flow. It renames all properties in the versioned flow export, using the source Apache NiFi version (from the export), the target Apache NiFi instance version, and a predefined list of renames between versions.
