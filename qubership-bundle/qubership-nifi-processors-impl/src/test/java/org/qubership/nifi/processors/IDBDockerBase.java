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

import org.qubership.nifi.processors.mapping.AbstractDockerBasedTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.dbcp.DBCPService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;

@Tag("DockerBased")
@Testcontainers
public class IDBDockerBase extends AbstractDockerBasedTest {

    protected static final String DB_NAME = "idb";
    protected static final String USER = "postgres";
    protected static final String PWD = "password";

    private static DBCPService dbcp;
    @Container
    private static JdbcDatabaseContainer postgresContainer;

    static {
        String initScriptPath = StringUtils.replaceChars(
                IDBDockerBase.class.getPackage().getName(),
                ".",
                File.separator) + File.separator + "db_for_test.sql";
        postgresContainer = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(DB_NAME)
                .withInitScript(initScriptPath)
                .withUsername(USER)
                .withPassword(PWD);
        postgresContainer.start();
    }

    @BeforeAll
    public static void setUp() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:postgresql://" + postgresContainer.getContainerIpAddress()
                + ":" + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + DB_NAME);
        hikariConfig.setUsername(USER);
        hikariConfig.setPassword(PWD);
        hikariConfig.setMinimumIdle(50);
        hikariConfig.setMaximumPoolSize(100);
        hikariConfig.setLeakDetectionThreshold(4000);
        HikariDataSource hikariDataSource = new HikariDataSource(hikariConfig);
        dbcp = new MockDBCPService(hikariDataSource);
    }
    @AfterAll
    public static void tearDown() {
        postgresContainer.stop();
    }

    public static DBCPService getDbcp() {
        return dbcp;
    }
}
