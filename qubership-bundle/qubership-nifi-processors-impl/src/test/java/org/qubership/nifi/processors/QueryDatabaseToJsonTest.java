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
import org.qubership.nifi.service.H2PreparedStatement;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.DBCP_SERVICE;
import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.PS_PROVIDER_SERVICE;
import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.REL_FAILURE;
import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.REL_SUCCESS;
import static org.qubership.nifi.processors.AbstractSingleQueryDatabaseToJson.BATCH_SIZE;
import static org.qubership.nifi.processors.QueryDatabaseToJson.PATH;
import static org.qubership.nifi.processors.QueryDatabaseToJson.SQL_QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class QueryDatabaseToJsonTest {
    private static final String DB_NAME = "db_ldt_query_json";
    private static final String DEFAULT_PATH = "$[*]";
    private TestRunner testRunner;
    private Connection connection;
    private Statement statement;
    private ObjectMapper mapper = new ObjectMapper();
    private String testJson = "[\"1\", \"2\", \"3\", \"4\"]";
    private String tableName = "TEST_TABLE";

    @BeforeEach
    public void init() throws InitializationException, ClassNotFoundException, SQLException {
        final DBCPService dbcp = new DBCPServiceSimpleImpl();
        final Map<String, String> dbcpProperties = new HashMap<>();
        ControllerService preparedStatementControllerService = new H2PreparedStatement();

        testRunner = TestRunners.newTestRunner(QueryDatabaseToJson.class);
        testRunner.setValidateExpressionUsage(false);

        testRunner.setProperty(SQL_QUERY, "SELECT * FROM " + tableName + " WHERE id IN(?,?,?,?)");
        testRunner.setProperty(DBCP_SERVICE, "dbcp");
        testRunner.setProperty(PS_PROVIDER_SERVICE, "H2PreparedStatement");
        testRunner.setProperty(PATH, DEFAULT_PATH);

        testRunner.addControllerService("dbcp", dbcp, dbcpProperties);
        testRunner.addControllerService("H2PreparedStatement", preparedStatementControllerService);

        testRunner.enableControllerService(dbcp);
        testRunner.enableControllerService(preparedStatementControllerService);
        Class.forName("org.h2.Driver");
        connection = DriverManager.getConnection("jdbc:h2:mem:" + DB_NAME);
    }

    @AfterEach
    public void cleanUp() throws SQLException {
        connection.close();
    }


    @Test
    public void testWriteAllInOneBatch() throws Exception {
        initBdTestDataFirstSet();
        testRunner.run();

        List<MockFlowFile> successFlowFiles = getListFlowFileFrom(REL_SUCCESS);

        assertEquals(1, successFlowFiles.size());

        JsonNode node = mapper.readTree(successFlowFiles.get(0).toByteArray());
        assertEquals(4, countNumValues("ID", node));
        assertEquals(4, countNumValues("VAL", node));
        assertEquals(4, countNumValues("VAL2", node));
    }


    @Test
    public void testWriteTwoNodeInBatch() throws SQLException, IOException {
        initBdTestDataFirstSet();
        testRunner.setProperty(BATCH_SIZE, "2");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = getListFlowFileFrom(REL_SUCCESS);

        assertEquals(2, successFlowFiles.size());

        JsonNode firstNode = mapper.readTree(successFlowFiles.get(0).toByteArray());
        JsonNode secondBatch = mapper.readTree(successFlowFiles.get(1).toByteArray());

        assertEquals(2, countNumValues("ID", firstNode));
        assertEquals(2, countNumValues("VAL", firstNode));
        assertEquals(2, countNumValues("VAL2", firstNode));

        assertEquals(2, countNumValues("VAL2", secondBatch));
        assertEquals(2, countNumValues("VAL2", secondBatch));
        assertEquals(2, countNumValues("VAL2", secondBatch));


    }

    @Test
    public void testWriteThreeNodeInBatch() throws SQLException, IOException {
        initBdTestDataFirstSet();
        testRunner.setProperty(BATCH_SIZE, "3");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = getListFlowFileFrom(REL_SUCCESS);
        assertEquals(2, successFlowFiles.size());

        JsonNode firstBatch = mapper.readTree(successFlowFiles.get(0).toByteArray());
        JsonNode secondBatch = mapper.readTree(successFlowFiles.get(1).toByteArray());

        assertEquals(3, countNumValues("ID", firstBatch));
        assertEquals(3, countNumValues("VAL", firstBatch));
        assertEquals(3, countNumValues("VAL2", firstBatch));

        assertEquals(1, countNumValues("VAL2", secondBatch));
        assertEquals(1, countNumValues("VAL2", secondBatch));
        assertEquals(1, countNumValues("VAL2", secondBatch));

        assertEquals("application/json", successFlowFiles.get(0).getAttribute(CoreAttributes.MIME_TYPE.key()));
        assertEquals("application/json", successFlowFiles.get(1).getAttribute(CoreAttributes.MIME_TYPE.key()));
    }


    private void initBdTestDataFirstSet() throws SQLException {
        this.statement = connection.createStatement();
        statement.execute("create table " + tableName
                + " (id integer not null, val integer, val2 integer, constraint my_pk4 primary key (id))");

        insertInBd(new String[]{"ID", "VAL", "VAL2"}, new String[]{"4", "400", "404"}, tableName);
        insertInBd(new String[]{"ID", "VAL", "VAL2"}, new String[]{"2", "200", "202"}, tableName);
        insertInBd(new String[]{"ID", "VAL", "VAL2"}, new String[]{"3", "300", "303"}, tableName);
        insertInBd(new String[]{"ID", "VAL", "VAL2"}, new String[]{"1", "100", "101"}, tableName);

        testRunner.enqueue(testJson);
    }

    @Test
    public void testInvalidSqlQuery() {
        testRunner.setProperty(SQL_QUERY, "invalid string for test");

        testRunner.enqueue(testJson);
        testRunner.run();

        List<MockFlowFile> failedFlowFiles = getListFlowFileFrom(REL_FAILURE);
        assertEquals(1, failedFlowFiles.size());
    }

    @Test
    public void testIfIdNotFoundInBd() throws SQLException {

        String invalidId = "[\"11\", \"12\", \"13\", \"14\"]";
        this.statement = connection.createStatement();
        statement.execute("create table " + tableName
                + " (id integer not null, val integer, val2 integer, constraint my_pk4 primary key (id))");

        insertInBd(new String[]{"ID", "VAL", "VAL2"}, new String[]{"2", "200", "204"}, tableName);
        insertInBd(new String[]{"ID", "VAL", "VAL2"}, new String[]{"4", "400", "404"}, tableName);

        testRunner.enqueue(invalidId);
        testRunner.run();

        List<MockFlowFile> sFlowFiles = getListFlowFileFrom(REL_SUCCESS);
        List<MockFlowFile> fFlowFiles = getListFlowFileFrom(REL_FAILURE);

        assertTrue(sFlowFiles.isEmpty() && fFlowFiles.isEmpty());
    }


    @Test
    public void testInvalidInputJson() throws SQLException {
        String testInvalidJson = "[\"\", \"\", \"\" ,\"\"]";
        this.statement = connection.createStatement();
        statement.execute("create table " + tableName
                + " (id integer not null, val integer, val2 integer, constraint my_pk4 primary key (id))");

        insertInBd(new String[]{"ID", "VAL", "VAL2"}, new String[]{"2", "200", "204"}, tableName);
        insertInBd(new String[]{"ID", "VAL", "VAL2"}, new String[]{"4", "400", "404"}, tableName);

        testRunner.enqueue(testInvalidJson);
        testRunner.run();

        List<MockFlowFile> fFlowFiles = getListFlowFileFrom(REL_FAILURE);
        assertEquals(1, fFlowFiles.size());

    }

    private List<MockFlowFile> getListFlowFileFrom(Relationship relationship) {
        return testRunner.getFlowFilesForRelationship(relationship);
    }

    private int countNumValues(String attribute, JsonNode node) {
        return node.findValues(attribute).size();
    }

    private void insertInBd(String[] keys, String[] values, String bdName) throws SQLException {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("the number of parameters is not equal to the number of values");
        }

        StringBuilder keysInBracket = buildValuesInBrackets(keys);
        StringBuilder valuesInBracket = buildValuesInBrackets(values);
        statement.execute("INSERT INTO " + bdName + keysInBracket + "VALUES" + valuesInBracket);
    }

    private StringBuilder buildValuesInBrackets(String[] values) {
        StringBuilder sb = new StringBuilder();
        sb.append(" (");
        for (String value : values) {
            sb.append(value).append(",");
        }
        sb.delete(sb.length() - 1, sb.length());
        sb.append(") ");
        return sb;
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
