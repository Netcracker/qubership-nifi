package org.qubership.nifi.maven.transform.build;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * Constructs a CleanupService with the required dependencies.
     *
     * @param fileSystemValue file system operations used for deletion
     * @param logValue        Maven logger
     */
    public CleanupService(final FileSystemService fileSystemValue, final Log logValue) {
        this.fileSystem = fileSystemValue;
        this.log = logValue;
    }

    /**
     * Finds and deletes all flowConf_* directories in the given export directory,
     * at any nesting depth. flowConf_* directories are created next to the flow file
     * they belong to, and flow files may be nested arbitrarily deep under exportDir.
     *
     * The matching directories are collected into a list before any deletion happens,
     * because deleting a directory while Files.walk is still lazily descending into it
     * would make the walk fail trying to list an already-removed directory.
     *
     * @param exportDir root directory containing exported flow files
     */
    public void cleanup(Path exportDir) {
        List<Path> flowConfDirs;
        try (Stream<Path> stream = Files.walk(exportDir)) {
            flowConfDirs = stream
                .filter(path -> !path.equals(exportDir))
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith(FLOW_CONF_PREFIX))
                .toList();
        } catch (IOException e) {
            log.warn("Failed to scan export directory for cleanup: " + e.getMessage());
            return;
        }
        flowConfDirs.forEach(this::deleteDirectory);
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
