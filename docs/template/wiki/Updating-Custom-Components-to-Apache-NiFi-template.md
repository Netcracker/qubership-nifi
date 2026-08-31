# Updating Custom Components to Apache NiFi <nifi.version>

This document outlines the steps required to update custom Apache NiFi components (processors, controller services, reporting tasks, etc.) for compatibility with Apache NiFi version **<nifi.version>**.

## Dependency Updates

### Update NiFi Version in `pom.xml`

Depending on your configuration, update the NiFi version property and the NiFi API version property or dependencies in your root `pom.xml` file, or in all `pom.xml` files through the project.

For example, if you use properties to manage versions, update the `nifi.version` property:

```xml
<properties>
...
    <nifi.version><nifi.version></nifi.version>
...
    <nifi.api.version><nifi-api.version></nifi.api.version>
...
</properties>
```

For Apache NiFi <nifi.version>, the compatible `nifi-api` version is `<nifi-api.version>`.
