package org.qubership.nifi.maven.transform.build;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CleanupServiceTest {

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    /**
     * Returns the temporary directory used by this test.
     * @return path to temporary directory
     */
    Path getTempDir() {
        return tempDir;
    }

    @Mock
    private FileSystemService fileSystem;

    /** Mock Maven logger. */
    @Mock
    private Log log;

    private CleanupService service;

    @BeforeEach
    void setUp() {
        service = new CleanupService(fileSystem, log);
    }

    @Test
    void cleanupDeletesFlowConfDirectory() throws IOException {
        Path flowConfDir = Files.createDirectory(tempDir.resolve("flowConf_myflow"));

        service.cleanup(tempDir);

        verify(fileSystem).deleteRecursively(flowConfDir);
    }

    @Test
    void cleanupDeletesMultipleFlowConfDirectories() throws IOException {
        Path dir1 = Files.createDirectory(tempDir.resolve("flowConf_flow1"));
        Path dir2 = Files.createDirectory(tempDir.resolve("flowConf_flow2"));

        service.cleanup(tempDir);

        verify(fileSystem).deleteRecursively(dir1);
        verify(fileSystem).deleteRecursively(dir2);
    }

    @Test
    void cleanupIgnoresNonFlowConfDirectory() throws IOException {
        Files.createDirectory(tempDir.resolve("other_dir"));

        service.cleanup(tempDir);

        verify(fileSystem, never()).deleteRecursively(any());
    }

    @Test
    void cleanupIgnoresFlowConfFile() throws IOException {
        Files.createFile(tempDir.resolve("flowConf_myflow"));

        service.cleanup(tempDir);

        verify(fileSystem, never()).deleteRecursively(any());
    }

    @Test
    void cleanupDeletesFlowConfDirectoryNestedBeyondDepthOne() throws IOException {
        Path nestedParent = Files.createDirectories(tempDir.resolve("env1/group1"));
        Path flowConfDir = Files.createDirectory(nestedParent.resolve("flowConf_myflow"));

        service.cleanup(tempDir);

        verify(fileSystem).deleteRecursively(flowConfDir);
    }

    @Test
    void cleanupLogsWarningWhenDeleteFails() throws IOException {
        Files.createDirectory(tempDir.resolve("flowConf_myflow"));
        doThrow(new IOException("disk error")).when(fileSystem).deleteRecursively(any());

        service.cleanup(tempDir);

        verify(log).warn(argThat((CharSequence msg) -> msg.toString().contains("disk error")));
    }
}
