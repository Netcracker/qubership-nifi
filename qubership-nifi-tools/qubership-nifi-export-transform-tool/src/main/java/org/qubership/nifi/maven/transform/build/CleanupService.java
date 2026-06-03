package org.qubership.nifi.maven.transform.build;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Deletes extracted configuration directories (flowConf_*) after a successful Build.
 * Only called when the --delete parameter is set to true.
 *
 * Deletion errors are logged as warnings and do not stop execution.
 */
public class CleanupService {

    private static final String FLOW_CONF_PREFIX = "flowConf_";

    private final FileSystemService fileSystem;
    private final Log log;

    public CleanupService(FileSystemService fileSystem, Log log) {
        this.fileSystem = fileSystem;
        this.log = log;
    }

    /**
     * Finds and deletes all flowConf_* directories in the given export directory.
     *
     * @param exportDir root directory containing exported flow files
     */
    public void cleanup(Path exportDir) {
        try (Stream<Path> stream = Files.walk(exportDir, 1)) {
            stream
                .filter(path -> !path.equals(exportDir))
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith(FLOW_CONF_PREFIX))
                .forEach(this::deleteDirectory);
        } catch (IOException e) {
            log.warn("Failed to scan export directory for cleanup: " + e.getMessage());
        }
    }


    private void deleteDirectory(Path dir) {
        try {
            fileSystem.deleteRecursively(dir);
            log.info("Deleted extracted config directory: " + dir);
        } catch (IOException e) {
            log.warn("Failed to delete directory '" + dir + "': " + e.getMessage());
        }
    }
}
