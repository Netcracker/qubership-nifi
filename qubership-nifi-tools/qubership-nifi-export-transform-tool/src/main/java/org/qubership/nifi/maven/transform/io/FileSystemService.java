package org.qubership.nifi.maven.transform.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for file system operations.
 * All read and write operations use UTF-8 encoding.
 */
public class FileSystemService {

    /**
     * Creates the directory and all missing parent directories.
     * Does nothing if the directory already exists.
     *
     * @param path directory path to create
     * @throws IOException if the directory cannot be created
     */
    public void createDirectories(Path path) throws IOException {
        Files.createDirectories(path);
    }

    /**
     * Writes text content to a file using UTF-8 encoding.
     * Creates the file if it does not exist, overwrites it if it does.
     *
     * @param file    target file path
     * @param content text content to write
     * @throws IOException if the file cannot be written
     */
    public void writeText(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Reads the full content of a file as a string using UTF-8 encoding.
     *
     * @param file source file path
     * @return file content as string
     * @throws IOException if the file cannot be read
     */
    public String readText(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Returns true if the path points to an existing regular file.
     *
     * @param file path to check
     * @return true if the file exists, false otherwise
     */
    public boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    /**
     * Deletes a file if it exists. Does nothing if the file is absent.
     *
     * @param file file to delete
     * @throws IOException if the file exists but cannot be deleted
     */
    public void deleteIfExists(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    /**
     * Recursively deletes a directory and all its contents.
     * Does nothing if the path does not exist.
     *
     * @param dir directory to delete
     * @throws IOException if any file or directory cannot be deleted
     */
    public void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        try (var stream = Files.walk(dir)) {
            var paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.delete(path);
            }
        }
    }
}
