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

import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.nifi.processors.FetchTableToJson.DBCP_SERVICE;
import static org.qubership.nifi.processors.FetchTableToJson.BATCH_SIZE;
import static org.qubership.nifi.processors.FetchTableToJson.FETCH_SIZE;
import static org.qubership.nifi.processors.FetchTableToJson.COLUMN_NAMES;
import static org.qubership.nifi.processors.FetchTableToJson.TABLE;
import static org.qubership.nifi.processors.FetchTableToJson.CUSTOM_QUERY;
import static org.qubership.nifi.processors.FetchTableToJson.WRITE_BY_BATCH;
import static org.qubership.nifi.processors.FetchTableToJson.REL_SUCCESS;
import static org.qubership.nifi.processors.FetchTableToJson.REL_FAILURE;
import static org.qubership.nifi.processors.FetchTableToJson.REL_TOTAL_COUNT;

public class FetchTableToJsonTest extends IDBDockerBase {
    private TestRunner testRunner;
    private String tableName = "IDB_TEST_TABLE_2";

    /**
     * Method for initializing the FetchTableToJson test processor.
     *
     * @throws InitializationException
     */
    @BeforeEach
    public void init() throws InitializationException {
        testRunner = TestRunners.newTestRunner(FetchTableToJson.class);

        testRunner.addControllerService("dbcp", getDbcp());
        testRunner.setProperty(DBCP_SERVICE, "dbcp");
        testRunner.setProperty(TABLE, tableName);

        testRunner.enableControllerService(getDbcp());
        testRunner.setValidateExpressionUsage(false);
    }

    /**
     * Getting data from table IDB_TEST_TABLE_2 in 1 batch using query.
     *
     * @throws Exception
     */
    @Test
    public void testQueryWriteAllInOneBatchSuccess() throws Exception {
        testRunner.setProperty(CUSTOM_QUERY, "select VAL1 from " + tableName);
        testRunner.setProperty(BATCH_SIZE, "6");
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        List<MockFlowFile> countFlowFiles = testRunner.getFlowFilesForRelationship(REL_TOTAL_COUNT);
        checkSuccessResult(
                successFlowFiles.size(),
                countFlowFiles.size(),
                countFlowFiles.get(0).getAttribute("rows.count")
        );
    }

    /**
     * Getting data from table IDB_TEST_TABLE_2 into 6 batches using column name.
     *
     * @throws Exception
     */
    @Test
    public void testColumnWriteAllInOneBatchSuccess() throws Exception {
        final int expectedResult = 6;
        testRunner.setProperty(COLUMN_NAMES, "VAL1");
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        assertEquals(expectedResult, successFlowFiles.size());
    }

    /**
     * Getting data from table IDB_TEST_TABLE_2 in 3 batches using query.
     *
     * @throws Exception
     */
    @Test
    public void testQueryWriteAllInSeveralBatchSuccess() throws Exception {
        final int expectedResult = 3;

        testRunner.setProperty(CUSTOM_QUERY, "select VAL1 from " + tableName);
        testRunner.setProperty(BATCH_SIZE, "2");
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        assertEquals(expectedResult, successFlowFiles.size());

    }

    /**
     * Getting data from non-existent table in 3 batches using query.
     *
     * @throws Exception
     */
    @Test
    public void testWriteAllInOneBatchError() throws Exception {
        testRunner.setProperty(CUSTOM_QUERY, "select VAL1 from " + tableName + "Q");
        testRunner.setProperty(BATCH_SIZE, "1");
        testRunner.enqueue("");
        testRunner.run();

        List<MockFlowFile> failFlowFiles = testRunner.getFlowFilesForRelationship(REL_FAILURE);
        assertEquals(1, failFlowFiles.size());
    }


    /**
     * Getting data from table IDB_TEST_TABLE_2 in 1 batch using query without incoming connection.
     *
     * @throws Exception
     */
    @Test
    public void testQueryWriteAllInOneBatchWithoutIncomingConnection() throws Exception {
        testRunner.setProperty(CUSTOM_QUERY, "select VAL1 from " + tableName);
        testRunner.setProperty(BATCH_SIZE, "6");

        testRunner.setIncomingConnection(false);
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        List<MockFlowFile> countFlowFiles = testRunner.getFlowFilesForRelationship(REL_TOTAL_COUNT);
        checkSuccessResult(
                successFlowFiles.size(),
                countFlowFiles.size(),
                countFlowFiles.get(0).getAttribute("rows.count")
        );
    }

    /**
     * @param successFlowFilesSize
     * @param countFlowFilesSize
     * @param attrValue
     */
    public void checkSuccessResult(int successFlowFilesSize, int countFlowFilesSize, String attrValue) {
        int expectSize = 1;
        String expectAttrValue = "6";

        assertEquals(expectSize, successFlowFilesSize);
        assertEquals(expectSize, countFlowFilesSize);
        assertEquals(expectAttrValue, attrValue);
    }

    /**
     * Getting data from table IDB_TEST_TABLE_2 in 1 batch using query in WRITE_BY_BATCH mode.
     *
     * @throws Exception
     */
    @Test
    public void testQueryWriteByBatchSuccess() throws Exception {
        testRunner.setProperty(CUSTOM_QUERY, "select VAL1 from " + tableName);
        testRunner.setProperty(BATCH_SIZE, "3");
        testRunner.setProperty(FETCH_SIZE, "3");
        testRunner.setProperty(WRITE_BY_BATCH, "true");

        Map<String, String> attributes = new HashMap<>();
        attributes.put("TestAttr1", "testVal1");

        testRunner.enqueue("", attributes);
        testRunner.setAllowSynchronousSessionCommits(true);
        testRunner.run();

        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(REL_SUCCESS);
        assertEquals(2, successFlowFiles.size());
    }

}
