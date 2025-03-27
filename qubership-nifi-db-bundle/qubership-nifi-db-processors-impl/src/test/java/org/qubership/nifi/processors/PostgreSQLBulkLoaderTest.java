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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Slf4j
@Tag("DockerBased")
@Testcontainers
public class PostgreSQLBulkLoaderTest {
    private static final String POSTGRES_IMAGE = "postgres:16.8";
    private static final String DB_NAME = "test123";
    private static final String USER = "postgres";
    private static final String PWD = "password";

    private static String dbUrl;
    private static DBCPService dbcp;
    @Container
    private static JdbcDatabaseContainer postgresContainer;

    static {
        postgresContainer = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(DB_NAME)
                .withUsername(USER)
                .withPassword(PWD);
        postgresContainer.start();
        dbUrl = "jdbc:postgresql://" + postgresContainer.getContainerIpAddress()
                + ":" + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + DB_NAME;
    }

    @BeforeAll
    public static void setUp() throws SQLException {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(dbUrl);
        hikariConfig.setUsername(USER);
        hikariConfig.setPassword(PWD);
        hikariConfig.setMinimumIdle(50);
        hikariConfig.setMaximumPoolSize(100);
        hikariConfig.setLeakDetectionThreshold(4000);
        HikariDataSource hikariDataSource = new HikariDataSource(hikariConfig);
        dbcp = new MockDBCPService(hikariDataSource);

        try (Connection con = dbcp.getConnection();
             PreparedStatement prSt = con.prepareStatement("create table NC_PARAMS "
                     + "(col1 varchar(255), col2 varchar(255))");
        ) {
            prSt.executeUpdate();
        }
    }

    @BeforeEach
    public void clearDB() throws SQLException {
        try (Connection con = dbcp.getConnection();
             PreparedStatement prSt = con.prepareStatement("truncate table NC_PARAMS");
        ) {
            prSt.executeUpdate();
        }
    }

    @Test
    public void testCopyModeToFromFS() throws InitializationException, SQLException {
        TestRunner runner = TestRunners.newTestRunner(PostgreSQLBulkLoader.class);
        runner.addControllerService("dbcp", dbcp);
        runner.enableControllerService(dbcp);
        runner.assertValid(dbcp);

        runner.setProperty(PostgreSQLBulkLoader.DBCP_SERVICE, "dbcp");
        runner.setProperty(PostgreSQLBulkLoader.READ_FROM, PostgreSQLBulkLoader.FILE_SYSTEM.getValue());
        runner.setProperty(PostgreSQLBulkLoader.COPY_MODE, PostgreSQLBulkLoader.FROM.getValue());
        runner.setProperty(PostgreSQLBulkLoader.SQL_QUERY, "COPY NC_PARAMS FROM STDIN WITH CSV");
        runner.setProperty(PostgreSQLBulkLoader.FILE_PATH, getFilePath("PostgreSQLBulkLoaderTest.csv"));
        runner.setProperty(PostgreSQLBulkLoader.BUFFER_SIZE, "65536");

        runner.enqueue("");
        runner.run();

        try (Connection con = dbcp.getConnection();
             PreparedStatement prSt = con.prepareStatement("select count(*) from NC_PARAMS");
             ResultSet rs =  prSt.executeQuery()
        ) {
            Assertions.assertTrue(rs.next(), "Expected 1 row, actual - nothing found");
            Assertions.assertEquals(2, rs.getInt(1));
        }

        final List<MockFlowFile> results = runner.getFlowFilesForRelationship(
                PostgreSQLBulkLoader.REL_SUCCESS.getName());
        Assertions.assertEquals(1, results.size());
    }

    @Test
    public void testCopyModeToFromContent() throws InitializationException, SQLException {
        TestRunner runner = TestRunners.newTestRunner(PostgreSQLBulkLoader.class);
        runner.addControllerService("dbcp", dbcp);
        runner.enableControllerService(dbcp);
        runner.assertValid(dbcp);

        runner.setProperty(PostgreSQLBulkLoader.DBCP_SERVICE, "dbcp");
        runner.setProperty(PostgreSQLBulkLoader.READ_FROM, PostgreSQLBulkLoader.CONTENT.getValue());
        runner.setProperty(PostgreSQLBulkLoader.COPY_MODE, PostgreSQLBulkLoader.FROM.getValue());
        runner.setProperty(PostgreSQLBulkLoader.SQL_QUERY, "COPY NC_PARAMS FROM STDIN WITH CSV");
        runner.setProperty(PostgreSQLBulkLoader.BUFFER_SIZE, "65536");

        runner.enqueue("\"1\",\"Test value 1\"\n"
                + "\"2\",\"Test value 2\"");
        runner.run();

        try (Connection con = dbcp.getConnection();
             PreparedStatement prSt = con.prepareStatement("select count(*) from NC_PARAMS");
             ResultSet rs =  prSt.executeQuery()
        ) {
            Assertions.assertTrue(rs.next(), "Expected 1 row, actual - nothing found");
            Assertions.assertEquals(2, rs.getInt(1));
        }

        final List<MockFlowFile> results = runner.getFlowFilesForRelationship(
                PostgreSQLBulkLoader.REL_SUCCESS.getName());
        Assertions.assertEquals(1, results.size());
    }

    @Test
    public void testCopyModeFromToContent() throws InitializationException, SQLException {
        TestRunner runner = TestRunners.newTestRunner(PostgreSQLBulkLoader.class);
        runner.addControllerService("dbcp", dbcp);
        runner.enableControllerService(dbcp);
        runner.assertValid(dbcp);

        runner.setProperty(PostgreSQLBulkLoader.DBCP_SERVICE, "dbcp");
        runner.setProperty(PostgreSQLBulkLoader.COPY_MODE, PostgreSQLBulkLoader.TO.getValue());
        runner.setProperty(PostgreSQLBulkLoader.SQL_QUERY, "COPY NC_PARAMS TO STDOUT WITH CSV");

        try (Connection con = dbcp.getConnection();
             PreparedStatement prSt = con.prepareStatement("INSERT INTO NC_PARAMS VALUES ('1', 'Test Value 1')");
        ) {
            prSt.execute();
        }

        runner.enqueue("");
        runner.run();

        runner.assertAllFlowFilesTransferred(PostgreSQLBulkLoader.REL_SUCCESS, 1);
        final List<MockFlowFile> results = runner.getFlowFilesForRelationship(
                PostgreSQLBulkLoader.REL_SUCCESS.getName());
        Assertions.assertEquals(1, results.size());
        results.get(0).assertContentEquals("1,Test Value 1\n");
    }

    @Test
    public void testCannotReadFile() throws InitializationException, SQLException {
        TestRunner runner = TestRunners.newTestRunner(PostgreSQLBulkLoader.class);
        runner.addControllerService("dbcp", dbcp);
        runner.enableControllerService(dbcp);
        runner.assertValid(dbcp);

        runner.setProperty(PostgreSQLBulkLoader.DBCP_SERVICE, "dbcp");
        runner.setProperty(PostgreSQLBulkLoader.READ_FROM, PostgreSQLBulkLoader.FILE_SYSTEM.getValue());
        runner.setProperty(PostgreSQLBulkLoader.COPY_MODE, PostgreSQLBulkLoader.FROM.getValue());
        runner.setProperty(PostgreSQLBulkLoader.SQL_QUERY, "COPY NC_PARAMS FROM STDIN WITH CSV");
        runner.setProperty(PostgreSQLBulkLoader.FILE_PATH, "/org/qubership/cloud/nifi/processors/Error.csv");
        runner.setProperty(PostgreSQLBulkLoader.BUFFER_SIZE, "65536");

        runner.enqueue("");
        runner.run();

        List<MockFlowFile> failureFF = runner.getFlowFilesForRelationship(PostgreSQLBulkLoader.REL_FAILURE);
        Assertions.assertEquals(1, failureFF.size());
        String errorAttr = failureFF.get(0).getAttribute("bulk.load.error");
        Assertions.assertTrue(StringUtils.isNotEmpty(errorAttr));
        Assertions.assertTrue(errorAttr.matches(".*Error.csv.*"));
    }

    @Test
    public void testCannotExecuteSQL() throws InitializationException, SQLException {
        TestRunner runner = TestRunners.newTestRunner(PostgreSQLBulkLoader.class);
        runner.addControllerService("dbcp", dbcp);
        runner.enableControllerService(dbcp);
        runner.assertValid(dbcp);

        runner.setProperty(PostgreSQLBulkLoader.DBCP_SERVICE, "dbcp");
        runner.setProperty(PostgreSQLBulkLoader.READ_FROM, PostgreSQLBulkLoader.FILE_SYSTEM.getValue());
        runner.setProperty(PostgreSQLBulkLoader.COPY_MODE, PostgreSQLBulkLoader.TO.getValue());
        runner.setProperty(PostgreSQLBulkLoader.SQL_QUERY, "COPY NC_PARAM FROM STDIN WITH CSV");
        runner.setProperty(PostgreSQLBulkLoader.FILE_PATH, getFilePath("PostgreSQLBulkLoaderTest.csv"));
        runner.setProperty(PostgreSQLBulkLoader.BUFFER_SIZE, "65536");

        runner.enqueue("");
        runner.run();

        List<MockFlowFile> failureFF = runner.getFlowFilesForRelationship(PostgreSQLBulkLoader.REL_FAILURE);
        Assertions.assertEquals(1, failureFF.size());
        failureFF.get(0).assertAttributeEquals("bulk.load.error", "ERROR: relation \"nc_param\" does not exist");
    }

    @AfterAll
    public static void tearDown() {
        postgresContainer.stop();
    }

    public String getFilePath(String fileName) {
        String path = null;
        try {
            path = Paths.get(getClass().getResource(fileName).toURI()).toUri().getPath();
        } catch (URISyntaxException e) {
            log.error("Path cannot be parsed as URI", e);
        }
        return path;
    }

    public static class MockDBCPService extends AbstractControllerService implements DBCPService {
        private final DataSource ds;

        public MockDBCPService(final DataSource newDs) {
            this.ds = newDs;
        }

        @Override
        public String getIdentifier() {
            return "dbcp";
        }

        @Override
        public Connection getConnection() throws ProcessException {
            try {
                return ds.getConnection();
            } catch (final Exception e) {
                throw new ProcessException("getConnection failed: " + e);
            }
        }
    }
}
