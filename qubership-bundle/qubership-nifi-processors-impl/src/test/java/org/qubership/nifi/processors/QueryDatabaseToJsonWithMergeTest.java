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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.nifi.service.DerbyPreparedStatement;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.apache.nifi.util.file.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.DBCP_SERVICE;
import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.PS_PROVIDER_SERVICE;
import static org.qubership.nifi.processors.QueryDatabaseToJson.SQL_QUERY;
import static junit.framework.TestCase.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.qubership.nifi.processors.QueryDatabaseToJsonWithMerge.*;

public class QueryDatabaseToJsonWithMergeTest {

    private final static String DB_LOCATION = "target/db_ldt";
    private TestRunner testRunner;
    private Connection connection;
    private static final String TABLE_NAME = "TEST_TABLE";
    private ObjectMapper mapper = new ObjectMapper();
    @BeforeEach
    public void init() throws InitializationException, ClassNotFoundException, SQLException {
        final DBCPService dbcp = new QueryDatabaseToJsonWithMergeTest.DBCPServiceSimpleImpl();
        final Map<String, String> dbcpProperties = new HashMap<>();
        ControllerService preparedStatementControllerService = new DerbyPreparedStatement();

        testRunner = TestRunners.newTestRunner(QueryDatabaseToJsonWithMerge.class);
        testRunner.setValidateExpressionUsage(false);

        testRunner.setProperty(DBCP_SERVICE, "dbcp");
        testRunner.setProperty(PS_PROVIDER_SERVICE, "DerbyPreparedStatement");

        testRunner.addControllerService("dbcp", dbcp, dbcpProperties);
        testRunner.addControllerService("DerbyPreparedStatement", preparedStatementControllerService);

        testRunner.enableControllerService(dbcp);
        testRunner.enableControllerService(preparedStatementControllerService);

        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        connection = DriverManager.getConnection("jdbc:derby:" + DB_LOCATION + ";create=true");
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

    private class DBCPServiceSimpleImpl extends AbstractControllerService implements DBCPService {
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
    private void initBdTestDataFirstSet() throws SQLException {
        try (Statement statement = connection.createStatement()){
            statement.execute("create table " + TABLE_NAME + " (id integer not null, val integer, val2 integer, constraint my_pk4 primary key (id))");

            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (4, 400, 404)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (2, 200, 202)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (3, 300, 303)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (1, 100, 101)" );

            testRunner.setProperty(PATH,"$.[*]");
            testRunner.setProperty(JOIN_KEY_PARENT_WITH_CHILD,"id");  // this key should match with node key from json file. case-sensitive
            testRunner.setProperty(JOIN_KEY_CHILD_WITH_PARENT,"ID");  // this key should match with column name from SQL_QUERY. case-sensitive
            testRunner.setProperty(KEY_TO_INSERT,"data");
        }
    }
    private void initBdBatchTestDataSecondSet() throws SQLException {
        try (Statement statement = connection.createStatement()){
            statement.execute("create table " + TABLE_NAME + " (id integer not null, val integer, val2 integer)");

            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (4, 400, 404)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (2, 200, 202)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (3, 300, 303)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (1, 100, 101)" );


            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (4, 4004, 40004)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (2, 2002, 20002)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (2, 2001, 20003)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (3, 3003, 30003)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (1, 1001, 10001)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (1, 1002, 10002)" );
            statement.execute("INSERT INTO " + TABLE_NAME + "(ID, VAL, VAL2)" + " VALUES (1, 1003, 10003)" );

            testRunner.setProperty(PATH,"$.[*]");
            testRunner.setProperty(JOIN_KEY_PARENT_WITH_CHILD,"id");  // this key should match with node key from json file. case-sensitive
            testRunner.setProperty(JOIN_KEY_CHILD_WITH_PARENT,"ID");  // this key should match with column name from SQL_QUERY. case-sensitive
            testRunner.setProperty(KEY_TO_INSERT,"data");
        }
    }

    @Test
    public void testValidSQL() throws IOException,SQLException {
        initBdTestDataFirstSet();
        testRunner.setProperty(SQL_QUERY,"SELECT *  " +
                "FROM  test_table t " +
                "WHERE id in (?,?,?,?)");

        putInQueue("test_table_input.json", Collections.emptyMap());
        testRunner.run();

        assertTrue(testRunner.getFlowFilesForRelationship(REL_FAILURE).isEmpty());
        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        assertEquals(1, successFlowFiles.size());

        JsonNode node = mapper.readTree(successFlowFiles.get(0).toByteArray());
        assertEquals(4, countNumValues("ID", node));
        assertEquals(4, countNumValues("VAL", node));
        assertEquals(4, countNumValues("VAL2", node));
        assertEquals(4, countNumValues("data", node));
    }
    @Test
    public void testInvalidSqlQuery() throws SQLException {
        initBdTestDataFirstSet();
        testRunner.setProperty(SQL_QUERY, "invalid string for test");

        putInQueue("test_table_input.json", Collections.emptyMap());
        try {
            testRunner.run();
        }catch (AssertionError e){
            assertTrue(e.getMessage().contains("SQLException") &&
                    e.getMessage().contains("An exception occurs during the QueryDatabaseToJsonWithMerge processing"));
        }
        List<MockFlowFile> failedFlowFiles = testRunner.getFlowFilesForRelationship(REL_FAILURE);
        assertEquals(1, failedFlowFiles.size());
    }
    @Test
    public void testIfIdNotFoundInBd() throws IOException,SQLException {

        initBdTestDataFirstSet();
        testRunner.setProperty(SQL_QUERY,"SELECT *  " +
                "FROM  test_table t " +
                "WHERE id in (?,?,?,?)");

        putInQueue("test_table_input_wrong_id.json", Collections.emptyMap());
        testRunner.run();

        assertTrue(testRunner.getFlowFilesForRelationship(REL_FAILURE).isEmpty());
        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        List<MockFlowFile> failFlowFiles = testRunner.getFlowFilesForRelationship(REL_FAILURE);
        assertEquals(1, successFlowFiles.size());

        JsonNode node = mapper.readTree(successFlowFiles.get(0).toByteArray());
        assertEquals(4, countNumValues("id", node));
        assertEquals(0, countNumValues("ID", node));
        assertEquals(0, countNumValues("VAL", node));
        assertEquals(0, countNumValues("VAL2", node));
        assertEquals(0, countNumValues("data", node));

        Assertions.assertTrue(!successFlowFiles.isEmpty() && failFlowFiles.isEmpty());
    }
    @Test
    public void testInvalidInputJson() throws IOException,SQLException {
        initBdTestDataFirstSet();
        testRunner.setProperty(SQL_QUERY,"SELECT *  " +
                "FROM  test_table t " +
                "WHERE id in (?,?,?,?)");

        putInQueue("test_table_input_invalid_json.json", Collections.emptyMap());

        Assertions.assertThrows(AssertionError.class,()->testRunner.run());
        try {
            testRunner.run();
        }catch (AssertionError e){
            assertTrue(e.getMessage().contains("JsonParseException") &&
                    e.getMessage().contains("expecting comma to separate Array entries"));
        }
        List<MockFlowFile> failedFlowFiles = testRunner.getFlowFilesForRelationship(REL_FAILURE);
        assertEquals(0, failedFlowFiles.size());
    }
    @Test
    public void testValidSQLwithTwoBatch() throws IOException,SQLException {
        initBdBatchTestDataSecondSet();
        testRunner.setProperty(SQL_QUERY, "SELECT *  " +
                "FROM  test_table t " +
                "WHERE id in (?,?,?,?)");
        testRunner.setProperty(BATCH_SIZE, "2");
        putInQueue("test_table_input.json", Collections.emptyMap());
        testRunner.run();

        assertTrue(testRunner.getFlowFilesForRelationship(REL_FAILURE).isEmpty());
        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        assertEquals(1, successFlowFiles.size());

        JsonNode node = mapper.readTree(successFlowFiles.get(0).toByteArray());
        assertEquals(true,  isNodeElementArray("/data",node));
    }
    @Test
    public void testValidSQLwithOneBatch() throws IOException,SQLException {
        initBdBatchTestDataSecondSet();
        testRunner.setProperty(SQL_QUERY, "SELECT *  " +
                "FROM  test_table t " +
                "WHERE id in (?,?,?,?)");
        testRunner.setProperty(BATCH_SIZE, "1");
        putInQueue("test_table_input.json", Collections.emptyMap());
        testRunner.run();

        assertTrue(testRunner.getFlowFilesForRelationship(REL_FAILURE).isEmpty());
        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        assertEquals(1, successFlowFiles.size());

        JsonNode node = mapper.readTree(successFlowFiles.get(0).toByteArray());
        assertEquals(false,  isNodeElementArray("/data",node));
    }
    @Test
    public void testValidSQLwithDefaultBatch() throws IOException,SQLException {
        initBdBatchTestDataSecondSet();
        testRunner.setProperty(SQL_QUERY, "SELECT *  " +
                "FROM  test_table t " +
                "WHERE id in (?,?,?,?)");

        putInQueue("test_table_input.json", Collections.emptyMap());
        testRunner.run();

        assertTrue(testRunner.getFlowFilesForRelationship(REL_FAILURE).isEmpty());
        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        assertEquals(1, successFlowFiles.size());

        JsonNode node = mapper.readTree(successFlowFiles.get(0).toByteArray());
        assertEquals(true,  isNodeElementArray("/data",node));
    }
    private boolean isNodeElementArray(String attribute, JsonNode arrayNode) {
        for(JsonNode jsonNode : arrayNode){
            if( jsonNode.at(attribute).isArray()) return true;
        }
        return false;
    }
    private int countNumValues(String attribute, JsonNode node) {
        return node.findValues(attribute).size();
    }
    private void putInQueue(String contentFileName, Map<String, String> attributes) {
        try {
            Path path = Paths.get(getClass().getResource(contentFileName).toURI());
            byte[] fileBytes = Files.readAllBytes(path);
            String ffContent = new String(fileBytes);
            testRunner.enqueue(ffContent, attributes);
        } catch (URISyntaxException | IOException e) {
            fail("Couldn't read a config file: " + contentFileName);
        }
    }
}
