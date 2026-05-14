package org.qubership.nifi.tools.compare;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonComparatorTest {

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    private Path sourceDir;
    private Path targetDir;
    private JsonComparator comparator;

    @BeforeEach
    void setUp() throws IOException {
        sourceDir = tempDir.resolve("source");
        targetDir = tempDir.resolve("target");
        for (String sub : List.of("processors", "controllerService", "reportingTask")) {
            Files.createDirectories(sourceDir.resolve(sub));
            Files.createDirectories(targetDir.resolve(sub));
        }
        comparator = new JsonComparator();
    }

    private void writeJson(Path root, String subfolder, String fileName,
                           String type, String propsJson) throws IOException {
        String json = String.format(
                "{\"type\":\"%s\",\"propertyDescriptors\":{%s}}", type, propsJson);
        Files.writeString(root.resolve(subfolder).resolve(fileName), json);
    }

    private String prop(String apiName, String displayName) {
        return prop(apiName, displayName, "");
    }

    private String prop(String apiName, String displayName, String description) {
        return String.format(
                "\"%s\":{\"name\":\"%s\",\"displayName\":\"%s\",\"description\":\"%s\"}",
                apiName, apiName, displayName, description);
    }

    private void loadAndCompare() throws IOException {
        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        comparator.compare();
    }

    private void loadAndCompare(String dictionaryPath) throws IOException {
        comparator.load(sourceDir.toString(), targetDir.toString(), dictionaryPath);
        comparator.compare();
    }

    private Path writeDictionary(String yaml) throws IOException {
        Path dictFile = tempDir.resolve("dictionary.yaml");
        Files.writeString(dictFile, yaml);
        return dictFile;
    }

    @Test
    void loadSetsIsLoadedTrue() throws IOException {
        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        assertTrue(comparator.isLoaded());
    }

    @Test
    void loadThrowsForNonExistentDirectory() {
        assertThrows(IOException.class, () ->
                comparator.load("/nonexistent/path", targetDir.toString(), null));
    }

    @Test
    void isLoadedFalseBeforeLoad() {
        assertFalse(comparator.isLoaded());
    }

    @Test
    void compareThrowsIfNotLoaded() {
        assertThrows(IllegalStateException.class, () -> comparator.compare());
    }

    @Test
    void compareIdenticalComponentsNoCsvRecords() throws IOException {
        String props = prop("p1", "Prop One");
        writeJson(sourceDir, "processors", "Comp.json", "org.example.Comp", props);
        writeJson(targetDir, "processors", "Comp.json", "org.example.Comp", props);

        loadAndCompare();

        assertTrue(comparator.getCsvRecords().isEmpty());
        assertTrue(comparator.getTypeToChangedProperties().isEmpty());
    }

    @Test
    void compareRenamedApiNameDetectedAsRename() throws IOException {
        writeJson(sourceDir, "processors", "Proc.json",
                "org.example.Proc", prop("old-api", "Same Display"));
        writeJson(targetDir, "processors", "Proc.json",
                "org.example.Proc", prop("new-api", "Same Display"));

        loadAndCompare();

        List<String[]> records = comparator.getCsvRecords();
        assertEquals(1, records.size());
        assertEquals("rename", records.get(0)[2]);
        assertEquals("old-api", records.get(0)[5]);
        assertEquals("new-api", records.get(0)[6]);
    }

    @Test
    void compareRenamedApiNameRecordedInTypeToRenamedProperties() throws IOException {
        writeJson(sourceDir, "processors", "Proc.json",
                "org.example.Proc", prop("old-api", "Display"));
        writeJson(targetDir, "processors", "Proc.json",
                "org.example.Proc", prop("new-api", "Display"));

        loadAndCompare();

        Map<String, Map<String, String>> renames = comparator.getTypeToChangedProperties();
        assertTrue(renames.containsKey("org.example.Proc"));
        assertEquals("new-api", renames.get("org.example.Proc").get("old-api"));
    }

    @Test
    void compareDeletedProperty() throws IOException {
        writeJson(sourceDir, "processors", "P.json", "org.example.P",
                prop("keep", "Keep") + "," + prop("gone", "Gone Prop"));
        writeJson(targetDir, "processors", "P.json", "org.example.P",
                prop("keep", "Keep"));

        loadAndCompare();

        List<String[]> records = comparator.getCsvRecords();
        assertEquals(1, records.size());
        assertEquals("deleted", records.get(0)[2]);
        assertEquals("gone", records.get(0)[5]);
    }

    @Test
    void compareAddedProperty() throws IOException {
        writeJson(sourceDir, "processors", "P.json", "org.example.P",
                prop("keep", "Keep"));
        writeJson(targetDir, "processors", "P.json", "org.example.P",
                prop("keep", "Keep") + "," + prop("new-prop", "New Prop"));

        loadAndCompare();

        List<String[]> records = comparator.getCsvRecords();
        assertEquals(1, records.size());
        assertEquals("added", records.get(0)[2]);
        assertEquals("new-prop", records.get(0)[6]);
    }

    @Test
    void compareComponentTypeIsSubfolderName() throws IOException {
        writeJson(sourceDir, "controllerService", "Svc.json",
                "org.example.Svc", prop("old", "Display"));
        writeJson(targetDir, "controllerService", "Svc.json",
                "org.example.Svc", prop("new", "Display"));

        loadAndCompare();

        List<String[]> records = comparator.getCsvRecords();
        assertEquals("controllerService", records.get(0)[1]);
    }

    @Test
    void compareReportingTaskSubfolderInRecord() throws IOException {
        writeJson(sourceDir, "reportingTask", "Task.json",
                "org.example.Task", prop("old", "Display"));
        writeJson(targetDir, "reportingTask", "Task.json",
                "org.example.Task", prop("new", "Display"));

        loadAndCompare();

        assertEquals("reportingTask", comparator.getCsvRecords().get(0)[1]);
    }

    @Test
    void loadBuildsTypeToFolderMap() throws IOException {
        writeJson(sourceDir, "processors", "P.json", "org.example.P", prop("a", "A"));
        writeJson(sourceDir, "controllerService", "S.json", "org.example.S", prop("b", "B"));

        comparator.load(sourceDir.toString(), targetDir.toString(), null);

        Map<String, String> folderMap = comparator.getTypeToFolderMap();
        assertEquals("processors", folderMap.get("org.example.P"));
        assertEquals("controllerService", folderMap.get("org.example.S"));
    }

    @Test
    void compareCaseInsensitiveDisplayNameNoChanges() throws IOException {
        writeJson(sourceDir, "processors", "P.json", "org.example.P",
                prop("same-api", "my property"));
        writeJson(targetDir, "processors", "P.json", "org.example.P",
                prop("same-api", "My Property"));

        loadAndCompare();

        assertTrue(comparator.getCsvRecords().isEmpty());
    }


    @Test
    void compareDictionaryMappingPreventsDeleteAndAdd() throws IOException {
        writeJson(sourceDir, "processors", "Proc.json",
                "org.example.Proc", prop("same-api", "Old Name"));
        writeJson(targetDir, "processors", "Proc.json",
                "org.example.Proc", prop("same-api", "New Name"));

        Path dictFile = writeDictionary(
                "displayNameMapping:\n"
                        + "- Proc:\n"
                        + "    old name: New Name\n");

        loadAndCompare(dictFile.toString());

        assertTrue(comparator.getCsvRecords().isEmpty(),
                "Dictionary mapping should match Old Name to New Name");
    }


    @Test
    void compareDictionaryMappingPreventsDeleteAndAdd2() throws IOException {
        writeJson(sourceDir, "processors", "Proc.json",
                "org.example.Proc", prop("same-api1", "Old Name"));
        writeJson(targetDir, "processors", "Proc.json",
                "org.example.Proc", prop("same-api2", "New Name"));

        Path dictFile = writeDictionary(
                "displayNameMapping:\n"
                        + "- Proc:\n"
                        + "    old name: New Name\n");

        loadAndCompare(dictFile.toString());

        assertTrue(!comparator.getCsvRecords().isEmpty());
    }

    @Test
    void compareNonUniqueDisplayNameIdenticalPropsNoChanges() throws IOException {
        String props = String.join(",",
                prop("api-1", "Shared", "Desc A"),
                prop("api-2", "Shared", "Desc B"));

        writeJson(sourceDir, "processors", "D.json", "org.example.D", props);
        writeJson(targetDir, "processors", "D.json", "org.example.D", props);

        loadAndCompare();

        assertTrue(comparator.getCsvRecords().isEmpty());
    }

    @Test
    void compareNonUniqueDisplayNameRenameDetectedByDescription() throws IOException {
        String sourceProps = String.join(",",
                prop("api-1", "Shared", "Desc A"),
                prop("api-2", "Shared", "Desc B"));
        String targetProps = String.join(",",
                prop("api-1", "Shared", "Desc A"),
                prop("api-2-new", "Shared", "Desc B"));

        writeJson(sourceDir, "processors", "D.json", "org.example.D", sourceProps);
        writeJson(targetDir, "processors", "D.json", "org.example.D", targetProps);

        loadAndCompare();

        List<String[]> records = comparator.getCsvRecords();
        assertEquals(1, records.size());
        assertEquals("rename", records.get(0)[2]);
        assertEquals("api-2", records.get(0)[5]);
        assertEquals("api-2-new", records.get(0)[6]);
    }

    @Test
    void compareFileOnlyInSourceNoCsvRecords() throws IOException {
        writeJson(sourceDir, "processors", "Gone.json",
                "org.example.Gone", prop("p", "Display"));

        loadAndCompare();

        assertTrue(comparator.getCsvRecords().isEmpty());
    }

    @Test
    void compareFileOnlyInTargetNoCsvRecords() throws IOException {
        writeJson(targetDir, "processors", "New.json",
                "org.example.New", prop("p", "Display"));

        loadAndCompare();

        assertTrue(comparator.getCsvRecords().isEmpty());
    }

    @Test
    void compareMultipleSubfolders() throws IOException {
        writeJson(sourceDir, "processors", "P.json",
                "org.example.P", prop("p-old", "PD"));
        writeJson(targetDir, "processors", "P.json",
                "org.example.P", prop("p-new", "PD"));

        writeJson(sourceDir, "controllerService", "S.json",
                "org.example.S", prop("s-old", "SD"));
        writeJson(targetDir, "controllerService", "S.json",
                "org.example.S", prop("s-new", "SD"));

        loadAndCompare();

        List<String[]> records = comparator.getCsvRecords();
        assertEquals(2, records.size());

        boolean hasProcessors = records.stream().anyMatch(r -> "processors".equals(r[1]));
        boolean hasCS = records.stream().anyMatch(r -> "controllerService".equals(r[1]));
        assertTrue(hasProcessors);
        assertTrue(hasCS);
    }

    @Test
    void compareEmptyDirectoriesNoCsvRecords() throws IOException {
        loadAndCompare();
        assertTrue(comparator.getCsvRecords().isEmpty());
    }

    @Test
    void loadNonExistentDictionaryThrows() {
        assertThrows(IOException.class, () ->
                comparator.load(sourceDir.toString(), targetDir.toString(),
                        "/nonexistent/dict.yaml"));
    }

    @Test
    void getSourceJsonMapContainsLoadedFiles() throws IOException {
        writeJson(sourceDir, "processors", "P.json", "org.example.P", prop("a", "A"));

        comparator.load(sourceDir.toString(), targetDir.toString(), null);

        assertTrue(comparator.getSourceJsonMap().containsKey("P.json"));
    }

    @Test
    void getTargetJsonMapContainsLoadedFiles() throws IOException {
        writeJson(targetDir, "processors", "P.json", "org.example.P", prop("a", "A"));

        comparator.load(sourceDir.toString(), targetDir.toString(), null);

        assertTrue(comparator.getTargetJsonMap().containsKey("P.json"));
    }

    @Test
    void allowedToDeleteDeletedPropertyInAllowListRecordedAsNull() throws IOException {
        writeJson(sourceDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep") + "," + prop("kerb-api", "Kerberos Principal"));
        writeJson(targetDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep"));

        Path dict = writeDictionary(
                "propertiesAllowedToDelete:\n"
                        + "- DBCPConnectionPool:\n"
                        + "    - Kerberos Principal\n");

        loadAndCompare(dict.toString());

        Map<String, String> props = comparator.getTypeToChangedProperties()
                .get("org.apache.nifi.dbcp.DBCPConnectionPool");
        assertTrue(props.containsKey("kerb-api"));
        assertNull(props.get("kerb-api"));
    }

    @Test
    void allowedToDeleteDeletedPropertyNotInAllowListNotInChangedProperties() throws IOException {
        writeJson(sourceDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep") + "," + prop("other-api", "Other Property"));
        writeJson(targetDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep"));

        Path dict = writeDictionary(
                "propertiesAllowedToDelete:\n"
                        + "- DBCPConnectionPool:\n"
                        + "    - Kerberos Principal\n");

        loadAndCompare(dict.toString());

        assertTrue(comparator.getTypeToChangedProperties().isEmpty(),
                "Property not in allow-list should not appear in changed properties");
    }

    @Test
    void allowedToDeleteDeletedPropertyNotInAllowListStillInCsv() throws IOException {
        writeJson(sourceDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep") + "," + prop("other-api", "Other Property"));
        writeJson(targetDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep"));

        Path dict = writeDictionary(
                "propertiesAllowedToDelete:\n"
                        + "- DBCPConnectionPool:\n"
                        + "    - Kerberos Principal\n");

        loadAndCompare(dict.toString());

        List<String[]> records = comparator.getCsvRecords();
        assertEquals(1, records.size());
        assertEquals("deleted", records.get(0)[2]);
        assertEquals("other-api", records.get(0)[5]);
    }

    @Test
    void allowedToDeleteNoDictionaryDeletedNotInChangedProperties() throws IOException {
        writeJson(sourceDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep") + "," + prop("gone-api", "Gone Prop"));
        writeJson(targetDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep"));

        loadAndCompare();

        assertTrue(comparator.getTypeToChangedProperties().isEmpty());
        assertEquals(1, comparator.getCsvRecords().size());
        assertEquals("deleted", comparator.getCsvRecords().get(0)[2]);
    }

    @Test
    void allowedToDeleteDictionaryWithoutAllowedSectionDeletedNotInJson() throws IOException {
        writeJson(sourceDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep") + "," + prop("gone-api", "Gone Prop"));
        writeJson(targetDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep"));

        Path dict = writeDictionary(
                "displayNameMapping:\n"
                        + "- DBCPConnectionPool:\n"
                        + "    some old name: Some New Name\n");

        loadAndCompare(dict.toString());

        assertTrue(comparator.getTypeToChangedProperties().isEmpty());
    }

    @Test
    void allowedToDeleteCaseInsensitiveMatch() throws IOException {
        writeJson(sourceDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep") + "," + prop("kerb-api", "kerberos principal"));
        writeJson(targetDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep-api", "Keep"));

        Path dict = writeDictionary(
                "propertiesAllowedToDelete:\n"
                        + "- DBCPConnectionPool:\n"
                        + "    - Kerberos Principal\n");

        loadAndCompare(dict.toString());

        Map<String, String> props = comparator.getTypeToChangedProperties()
                .get("org.apache.nifi.dbcp.DBCPConnectionPool");
        assertTrue(props.containsKey("kerb-api"));
    }

    @Test
    void allowedToDeleteMultiplePropertiesOnlyAllowedRecorded() throws IOException {
        writeJson(sourceDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep", "Keep") + ","
                        + prop("kerb-svc", "Kerberos Credentials Service") + ","
                        + prop("kerb-princ", "Kerberos Principal") + ","
                        + prop("kerb-pwd", "Kerberos Password") + ","
                        + prop("not-allowed", "Some Other Prop"));
        writeJson(targetDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("keep", "Keep"));

        Path dict = writeDictionary(
                "propertiesAllowedToDelete:\n"
                        + "- DBCPConnectionPool:\n"
                        + "    - Kerberos Credentials Service\n"
                        + "    - Kerberos Principal\n"
                        + "    - Kerberos Password\n");

        loadAndCompare(dict.toString());

        Map<String, String> props = comparator.getTypeToChangedProperties()
                .get("org.apache.nifi.dbcp.DBCPConnectionPool");
        assertEquals(3, props.size());
        assertNull(props.get("kerb-svc"));
        assertNull(props.get("kerb-princ"));
        assertNull(props.get("kerb-pwd"));
        assertFalse(props.containsKey("not-allowed"));

        assertEquals(4, comparator.getCsvRecords().size());
    }

    @Test
    void allowedToDeleteDifferentComponentTypeNotAffected() throws IOException {
        writeJson(sourceDir, "controllerService", "Hikari.json",
                "org.apache.nifi.dbcp.HikariCPConnectionPool",
                prop("keep", "Keep") + "," + prop("kerb-api", "Kerberos Principal"));
        writeJson(targetDir, "controllerService", "Hikari.json",
                "org.apache.nifi.dbcp.HikariCPConnectionPool",
                prop("keep", "Keep"));

        Path dict = writeDictionary(
                "propertiesAllowedToDelete:\n"
                        + "- DBCPConnectionPool:\n"
                        + "    - Kerberos Principal\n");

        loadAndCompare(dict.toString());

        assertTrue(comparator.getTypeToChangedProperties().isEmpty(),
                "Allow-list for DBCPConnectionPool should not affect HikariCPConnectionPool");
    }

    @Test
    void allowedToDeleteMixedWithRenameBothRecorded() throws IOException {
        writeJson(sourceDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("old-api", "Display A") + ","
                        + prop("kerb-api", "Kerberos Principal"));
        writeJson(targetDir, "controllerService", "Pool.json",
                "org.apache.nifi.dbcp.DBCPConnectionPool",
                prop("new-api", "Display A"));

        Path dict = writeDictionary(
                "propertiesAllowedToDelete:\n"
                        + "- DBCPConnectionPool:\n"
                        + "    - Kerberos Principal\n");

        loadAndCompare(dict.toString());

        Map<String, String> props = comparator.getTypeToChangedProperties()
                .get("org.apache.nifi.dbcp.DBCPConnectionPool");
        assertEquals(2, props.size());
        assertEquals("new-api", props.get("old-api"));
        assertNull(props.get("kerb-api"));
    }
}
