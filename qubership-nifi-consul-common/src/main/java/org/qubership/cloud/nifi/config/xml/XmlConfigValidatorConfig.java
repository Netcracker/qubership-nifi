package org.qubership.cloud.nifi.config.xml;

public record XmlConfigValidatorConfig(
        String defaultPath,
        String defaultMainConfigDirectoryPath,
        String defaultRestoreDirectoryPath
) {
}
