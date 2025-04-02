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

import org.qubership.nifi.service.PostgresPreparedStatementWithArrayProvider;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.sql.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.DBCP_SERVICE;
import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.PS_PROVIDER_SERVICE;
import static org.qubership.nifi.processors.AbstractQueryDatabaseToJson.REL_SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.nifi.processors.QueryIdsAndFetchTableToJson.*;

@Tag("DockerBased")
public class QueryIdsAndFetchTableToJsonTest extends IDBDockerBase {
    private static final String ATTR_FETCH_COUNT = "fetch.count";
    private static final String ATTR_ROWS_COUNT = "rows.count";
    private TestRunner testRunner;
    private Connection connection;
    private Statement statement;
    private String tableName = "IDB_TEST_TABLE";


    @BeforeEach
    public void init() throws InitializationException, ClassNotFoundException, SQLException {
        testRunner = TestRunners.newTestRunner(QueryIdsAndFetchTableToJson.class);

        testRunner.addControllerService("dbcp", getDbcp());
        testRunner.setProperty(DBCP_SERVICE, "dbcp");
        testRunner.setProperty(IDS_DBCP_SERVICE, "dbcp");

        ControllerService preparedStatementControllerService = new PostgresPreparedStatementWithArrayProvider();
        testRunner.addControllerService("PostgresPreparedStatement", preparedStatementControllerService);
        testRunner.setProperty(PS_PROVIDER_SERVICE, "PostgresPreparedStatement");

        testRunner.enableControllerService(getDbcp());
        testRunner.enableControllerService(preparedStatementControllerService);

        testRunner.setValidateExpressionUsage(false);
    }


    @Test
    public void testWriteAllInOneBatch() throws Exception{
        testRunner.setProperty(BATCH_SIZE, "10");
        testRunner.setProperty(CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName);
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        List<MockFlowFile> countFlowFiles = testRunner.getFlowFilesForRelationship(REL_TOTAL_COUNT);
        assertEquals(1, successFlowFiles.size());
        assertEquals(1, countFlowFiles.size());
        assertEquals("6", countFlowFiles.get(0).getAttribute(ATTR_ROWS_COUNT));
        assertEquals("1", countFlowFiles.get(0).getAttribute(ATTR_FETCH_COUNT));
    }


    @Test
    public void testWriteInTwoBatch() throws Exception{
        testRunner.setProperty(BATCH_SIZE, "3");
        testRunner.setProperty(CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName);
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        List<MockFlowFile> countFlowFiles = testRunner.getFlowFilesForRelationship(REL_TOTAL_COUNT);
        assertEquals(2, successFlowFiles.size());
        assertEquals(1, countFlowFiles.size());
        assertEquals("6", countFlowFiles.get(0).getAttribute(ATTR_ROWS_COUNT));
        assertEquals("2", countFlowFiles.get(0).getAttribute(ATTR_FETCH_COUNT));
    }



    @Test
    public void testWriteAllInOneBatchWithCondition() throws Exception{
        testRunner.setProperty(BATCH_SIZE, "10");
        testRunner.setProperty(CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName + " WHERE " + tableName + ".SOURCE_ID IN ('TEST_ID#000001','TEST_ID#000002')");
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        List<MockFlowFile> countFlowFiles = testRunner.getFlowFilesForRelationship(REL_TOTAL_COUNT);
        assertEquals(1, successFlowFiles.size());
        assertEquals(1, countFlowFiles.size());
        assertEquals("2", countFlowFiles.get(0).getAttribute(ATTR_ROWS_COUNT));
        assertEquals("1", countFlowFiles.get(0).getAttribute(ATTR_FETCH_COUNT));
    }


    @Test
    public void testWriteAllInOneBatchWithEmptyIdsQuery() throws Exception{
        testRunner.setProperty(BATCH_SIZE, "10");
        testRunner.setProperty(IDS_BATCH_SIZE, "10");
        testRunner.setProperty(IDS_CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName + " WHERE CODE = 'TEST-CODE-0003'");
        testRunner.setProperty(CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName + " WHERE " + tableName + ".SOURCE_ID IN (#SOURCE_IDS#)");
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        List<MockFlowFile> countFlowFiles = testRunner.getFlowFilesForRelationship(REL_TOTAL_COUNT);
        assertEquals(0, successFlowFiles.size());
        assertEquals(1, countFlowFiles.size());
        assertEquals("0", countFlowFiles.get(0).getAttribute(ATTR_ROWS_COUNT));
        assertEquals("0", countFlowFiles.get(0).getAttribute(ATTR_FETCH_COUNT));
    }

    @Test
    public void testWriteAllInOneBatchWithIdsQuery() throws Exception{
        testRunner.setProperty(BATCH_SIZE, "10");
        testRunner.setProperty(IDS_BATCH_SIZE, "10");
        testRunner.setProperty(IDS_CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName + " WHERE CODE = 'TEST-CODE-0001'");
        testRunner.setProperty(CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName + " WHERE SOURCE_ID IN (#SOURCE_IDS#)");
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        List<MockFlowFile> countFlowFiles = testRunner.getFlowFilesForRelationship(REL_TOTAL_COUNT);
        assertEquals(1, successFlowFiles.size());
        assertEquals(1, countFlowFiles.size());
        assertEquals("3", countFlowFiles.get(0).getAttribute(ATTR_ROWS_COUNT));
        assertEquals("1", countFlowFiles.get(0).getAttribute(ATTR_FETCH_COUNT));
        assertEquals("application/json", successFlowFiles.get(0).getAttribute(CoreAttributes.MIME_TYPE.key()));
        assertEquals("application/json", countFlowFiles.get(0).getAttribute(CoreAttributes.MIME_TYPE.key()));
    }

    @Test
    public void testWithErroneousQuery() {
        testRunner.setProperty(IDS_CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName + "L WHERE CODE = 'TEST-CODE-0001'");
        testRunner.setProperty(CUSTOM_QUERY, "SELECT SOURCE_ID FROM " + tableName + "L WHERE SOURCE_ID IN (#SOURCE_IDS#)");
        testRunner.setProperty(WRITE_BY_BATCH, "true");

        // set some test attrs on flowfile
        Map<String, String> attributes =  new HashMap<>();
        attributes.put("TestAttr1","testVal1");
        attributes.put("TestAttr2","testVal2");
        testRunner.enqueue("",attributes);

        testRunner.run();

        List<MockFlowFile> failFlowFiles = testRunner.getFlowFilesForRelationship(REL_FAILURE);
        assertEquals(1, failFlowFiles.size());
        failFlowFiles.get(0).assertAttributeEquals("TestAttr1","testVal1");
        failFlowFiles.get(0).assertAttributeEquals("TestAttr2","testVal2");
    }

}
