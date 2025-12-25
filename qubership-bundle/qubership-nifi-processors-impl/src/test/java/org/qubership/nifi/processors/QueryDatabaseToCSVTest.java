/*
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.nifi.processors;

import org.qubership.nifi.processors.extract.QueryDatabaseToCSV;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.apache.nifi.util.file.FileUtils;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.nifi.processors.extract.QueryDatabaseToCSV.REL_FAILURE;
import static org.qubership.nifi.processors.extract.QueryDatabaseToCSV.REL_SUCCESS;
import static org.qubership.nifi.processors.extract.QueryDatabaseToCSV.BATCH_SIZE;
import static org.qubership.nifi.processors.extract.QueryDatabaseToCSV.CUSTOM_QUERY;
import static org.qubership.nifi.processors.extract.QueryDatabaseToCSV.DBCP_SERVICE;
import static org.qubership.nifi.processors.extract.QueryDatabaseToCSV.WRITE_BY_BATCH;

public class QueryDatabaseToCSVTest {
    private static final String DB_LOCATION = "target/db_ldt";
    private TestRunner testRunner;
    private Connection connection;
    private static final String TABLE_NAME = "TEST_TABLE";
    private static final String TABLE_NAME2 = "TEST_TABLE2";

    @BeforeEach
    public void init() throws InitializationException, ClassNotFoundException, SQLException {
        final DBCPService dbcp = new DBCPServiceSimpleImpl();
        final Map<String, String> dbcpProperties = new HashMap<>();

        testRunner = TestRunners.newTestRunner(QueryDatabaseToCSV.class);
        testRunner.setValidateExpressionUsage(false);

        testRunner.setProperty(CUSTOM_QUERY, "SELECT * FROM " + TABLE_NAME + " order by id");
        testRunner.setProperty(DBCP_SERVICE, "dbcp");

        testRunner.addControllerService("dbcp", dbcp, dbcpProperties);
        testRunner.enableControllerService(dbcp);

        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        connection = DriverManager.getConnection("jdbc:derby:" + DB_LOCATION + ";create=true");

        // set some test attrs on flowfile
        Map<String, String> attributes = new HashMap<>();
        attributes.put("TestAttr1", "testVal1");
        attributes.put("TestAttr2", "testVal2");

        testRunner.enqueue("", attributes);
    }

    @AfterEach
    public void cleanUp() throws SQLException {
        try {
            DriverManager.getConnection("jdbc:derby:" + DB_LOCATION + ";shutdown=true");
        } catch (SQLNonTransientConnectionException e) {
            // Do nothing, this is what happens at Derby shutdown
        }
        final File dbLocation = new File(DB_LOCATION);
        try {
            FileUtils.deleteFile(dbLocation, true);
        } catch (IOException ioe) {
            // Do nothing, may not have existed
        }
    }

    @Test
    public void testWriteAllInCsv() throws Exception {
        initBdTestDataFirstSet();
        testRunner.run();

        List<MockFlowFile> successFlowFiles = getListFlowFileFrom(REL_SUCCESS);

        assertEquals(1, successFlowFiles.size());
        successFlowFiles.get(0).assertAttributeEquals("TestAttr1", "testVal1");
        successFlowFiles.get(0).assertAttributeEquals("TestAttr2", "testVal2");

        String actContent = successFlowFiles.get(0).getContent();
        actContent = actContent.replaceAll("(\r\n)", "\n");

        compareFileContents(actContent, Paths.get(getClass().getResource("queryToCSVOutput.csv").toURI()));
    }

    @Test
    public void testWriteInCsvInBatch() throws Exception {

        initBdTestDataFirstSet();
        testRunner.setProperty(BATCH_SIZE, "1");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = getListFlowFileFrom(REL_SUCCESS);

        assertEquals(4, successFlowFiles.size());
        for (int i = 0; i < successFlowFiles.size(); i++) {
            successFlowFiles.get(i).assertAttributeEquals("TestAttr1", "testVal1");
            successFlowFiles.get(i).assertAttributeEquals("TestAttr2", "testVal2");
        }
    }

    @Test
    public void testInvalidSqlQuery() {
        testRunner.setProperty(CUSTOM_QUERY, "invalid string for test");
        testRunner.run();

        List<MockFlowFile> failedFlowFiles = getListFlowFileFrom(REL_FAILURE);
        assertEquals(1, failedFlowFiles.size());
        failedFlowFiles.get(0).assertAttributeEquals("TestAttr1", "testVal1");
        failedFlowFiles.get(0).assertAttributeEquals("TestAttr2", "testVal2");
        compareErrorAttributes(failedFlowFiles.get(0),
                "extraction.error", "java.sql.SQLSyntaxErrorException");
    }

    @Test
    public void testInvalidSqlQueryWithBatch() {
        testRunner.setProperty(CUSTOM_QUERY, "select invalid sql");
        testRunner.setProperty(WRITE_BY_BATCH, "true");
        testRunner.run();

        List<MockFlowFile> failedFlowFiles = getListFlowFileFrom(REL_FAILURE);

        assertEquals(1, failedFlowFiles.size());
        failedFlowFiles.get(0).assertAttributeEquals("TestAttr1", "testVal1");
        failedFlowFiles.get(0).assertAttributeEquals("TestAttr2", "testVal2");

        compareErrorAttributes(failedFlowFiles.get(0),
                "extraction.error", "java.sql.SQLSyntaxErrorException");
    }

    @Test
    public void testWriteAllInCsvWithComplexTypes() throws Exception {
        //includes varchar, clob, blob
        initDBDataWithComplexTypes();
        testRunner.setProperty(CUSTOM_QUERY, "SELECT * FROM " + TABLE_NAME2 + " order by id");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = getListFlowFileFrom(REL_SUCCESS);

        assertEquals(1, successFlowFiles.size());
        successFlowFiles.get(0).assertAttributeEquals("TestAttr1", "testVal1");
        successFlowFiles.get(0).assertAttributeEquals("TestAttr2", "testVal2");

        String actContent = successFlowFiles.get(0).getContent();
        actContent = actContent.replaceAll("(\r\n)", "\n");

        compareFileContents(actContent, Paths.get(getClass().getResource("queryToCSVwithTextTypes.csv").toURI()));
    }

    private void compareErrorAttributes(MockFlowFile ff, String key, String value) {
        Map<String, String> attr = ff.getAttributes();
        String actualValue = attr.get(key);
        Assert.assertThat(actualValue, CoreMatchers.containsString(value));
    }

    private void initBdTestDataFirstSet() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table " + TABLE_NAME
                    + " (id integer not null, val integer, val2 integer, constraint my_pk4 primary key (id))");

            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (4, 400, 404)");
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (2, 200, 202)");
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (3, 300, 303)");
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (1, 100, 101)");
        }
    }

    private void initDBDataWithComplexTypes() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table " + TABLE_NAME2
                    + " (id integer not null, val1 integer, "
                    + "textval1 varchar(127), textval2 clob(32k), binval1 blob(32k))");
        }

        try (PreparedStatement prSt = connection.prepareStatement("INSERT INTO " + TABLE_NAME2
                + "(id, val1, textval1, textval2, binval1)" + " VALUES (?, ?, ?, ?, ?)")) {
            for (int cnt = 1; cnt <= 4; cnt++) {
                prSt.setInt(1, cnt);
                prSt.setInt(2, cnt * 100);
                prSt.setString(3, "Some short text value " + cnt);
                prSt.setString(4, "Some long text value " + cnt);
                String blobValue = "Some blob value " + cnt;
                try (InputStream in = new ByteArrayInputStream(blobValue.getBytes(StandardCharsets.UTF_8))) {
                    prSt.setBinaryStream(5, in);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                prSt.executeUpdate();
            }
        }
    }

    private void compareFileContents(String actContent, Path expFile) throws IOException {
        StringBuilder expContent = new StringBuilder();
        try (BufferedReader expReader = new BufferedReader(new FileReader(expFile.toFile()))) {
            String line = "";
            while ((line = expReader.readLine()) != null) {
                expContent.append(line).append("\n");
            }
            assertEquals(expContent.toString(), actContent);
        }
    }

    private List<MockFlowFile> getListFlowFileFrom(Relationship relationship) {
        return testRunner.getFlowFilesForRelationship(relationship);
    }

    private final class DBCPServiceSimpleImpl extends AbstractControllerService implements DBCPService {
        @Override
        public String getIdentifier() {
            return "dbcp";
        }

        @Override
        public Connection getConnection() throws ProcessException {
            try {
                return connection;
            } catch (final Exception e) {
                throw new ProcessException("getConnection failed: " + e);
            }
        }
    }
}
