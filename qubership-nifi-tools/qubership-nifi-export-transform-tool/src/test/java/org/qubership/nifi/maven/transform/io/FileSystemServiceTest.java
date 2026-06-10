package org.qubership.nifi.maven.transform.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemServiceTest {

    @TempDir
    Path tempDir;

    private final FileSystemService service = new FileSystemService();

    // -------------------------------------------------------------------------
    // createDirectories
    // -------------------------------------------------------------------------

    @Test
    void createDirectoriesCreatesNewDirectory() throws IOException {
        Path dir = tempDir.resolve("new-dir");

        service.createDirectories(dir);

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void createDirectoriesCreatesNestedDirectories() throws IOException {
        Path dir = tempDir.resolve("a").resolve("b").resolve("c");

        service.createDirectories(dir);

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void createDirectoriesDoesNotThrowWhenDirectoryAlreadyExists() throws IOException {
        Path dir = tempDir.resolve("existing");
        Files.createDirectories(dir);

        service.createDirectories(dir);

        assertTrue(Files.isDirectory(dir));
    }

    // -------------------------------------------------------------------------
    // writeText
    // -------------------------------------------------------------------------

    @Test
    void writeTextCreatesFileWithContent() throws IOException {
        Path file = tempDir.resolve("output.txt");

        service.writeText(file, "hello world");

        assertTrue(Files.exists(file));
        assertEquals("hello world", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void writeTextOverwritesExistingFile() throws IOException {
        Path file = tempDir.resolve("output.txt");
        Files.writeString(file, "old content", StandardCharsets.UTF_8);

        service.writeText(file, "new content");

        assertEquals("new content", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void writeTextWritesUtf8Content() throws IOException {
        Path file = tempDir.resolve("utf8.txt");
        String content = "Hello world - SELECT * FROM table";

        service.writeText(file, content);

        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void writeTextWritesMultilineContent() throws IOException {
        Path file = tempDir.resolve("multiline.sql");
        String content = "SELECT\n    id,\n    name\nFROM users\nWHERE active = true";

        service.writeText(file, content);

        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void writeTextWritesEmptyContent() throws IOException {
        Path file = tempDir.resolve("empty.txt");

        service.writeText(file, "");

        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).isEmpty());
    }

    // -------------------------------------------------------------------------
    // readText
    // -------------------------------------------------------------------------

    @Test
    void readTextReturnsFileContent() throws IOException {
        Path file = tempDir.resolve("input.txt");
        Files.writeString(file, "some content", StandardCharsets.UTF_8);

        String result = service.readText(file);

        assertEquals("some content", result);
    }

    @Test
    void readTextReturnsUtf8Content() throws IOException {
        Path file = tempDir.resolve("utf8.txt");
        String content = "Unicode test text";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(content, service.readText(file));
    }

    @Test
    void readTextReturnsEmptyStringForEmptyFile() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        assertTrue(service.readText(file).isEmpty());
    }

    @Test
    void readTextThrowsIOExceptionForNonExistentFile() {
        Path missing = tempDir.resolve("missing.txt");

        assertThrows(IOException.class, () -> service.readText(missing));
    }

    @Test
    void writeTextThenReadTextRoundtrip() throws IOException {
        Path file = tempDir.resolve("roundtrip.groovy");
        String content = "def x = 42\nprintln x";

        service.writeText(file, content);
        String result = service.readText(file);

        assertEquals(content, result);
    }

    // -------------------------------------------------------------------------
    // exists
    // -------------------------------------------------------------------------

    @Test
    void existsReturnsTrueForExistingFile() throws IOException {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "data", StandardCharsets.UTF_8);

        assertTrue(service.exists(file));
    }

    @Test
    void existsReturnsFalseForNonExistentPath() {
        Path missing = tempDir.resolve("missing.txt");

        assertFalse(service.exists(missing));
    }

    @Test
    void existsReturnsFalseForDirectory() {
        assertFalse(service.exists(tempDir));
    }

    // -------------------------------------------------------------------------
    // deleteIfExists
    // -------------------------------------------------------------------------

    @Test
    void deleteIfExistsDeletesExistingFile() throws IOException {
        Path file = tempDir.resolve("to-delete.txt");
        Files.writeString(file, "data", StandardCharsets.UTF_8);

        service.deleteIfExists(file);

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteIfExistsDoesNothingWhenFileAbsent() throws IOException {
        Path missing = tempDir.resolve("missing.txt");

        assertDoesNotThrow(() -> service.deleteIfExists(missing));
    }

    // -------------------------------------------------------------------------
    // deleteRecursively
    // -------------------------------------------------------------------------

    @Test
    void deleteRecursivelyDeletesEmptyDirectory() throws IOException {
        Path dir = tempDir.resolve("empty-dir");
        Files.createDirectories(dir);

        service.deleteRecursively(dir);

        assertFalse(Files.exists(dir));
    }

    @Test
    void deleteRecursivelyDeletesDirectoryWithFiles() throws IOException {
        Path dir = tempDir.resolve("dir-with-files");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "aaa", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.txt"), "bbb", StandardCharsets.UTF_8);

        service.deleteRecursively(dir);

        assertFalse(Files.exists(dir));
    }

    @Test
    void deleteRecursivelyDeletesNestedStructure() throws IOException {
        Path dir = tempDir.resolve("parent");
        Path child = dir.resolve("child");
        Files.createDirectories(child);
        Files.writeString(child.resolve("file.sql"), "SELECT 1", StandardCharsets.UTF_8);

        service.deleteRecursively(dir);

        assertFalse(Files.exists(dir));
    }

    @Test
    void deleteRecursivelyDoesNotThrowWhenPathDoesNotExist() throws IOException {
        Path missing = tempDir.resolve("non-existent-dir");

        service.deleteRecursively(missing);

        assertFalse(Files.exists(missing));
    }
}
