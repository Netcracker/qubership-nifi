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

package org.qubership.nifi.service;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.nifi.util.NoOpProcessor;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Binds the id array through the {@code OracleConnection} class defined by the classloader of the connection passed in.
 *
 * <p>In NiFi the connection pool loads the Oracle driver from its "Database Driver Locations" in a classloader of its
 * own, where this service cannot see it. Each test imitates that pool with an isolated classloader over the ojdbc jar,
 * which defines a second {@code oracle.jdbc.OracleConnection}, distinct from the one on the test classpath.</p>
 */
public class OraclePreparedStatementWithArrayProviderTest {

    private static final String ORACLE_CONNECTION = "oracle.jdbc.OracleConnection";

    private final List<URLClassLoader> loaders = new ArrayList<>();
    private OraclePreparedStatementWithArrayProvider provider;

    @BeforeEach
    public void setUp() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(NoOpProcessor.class);
        provider = new OraclePreparedStatementWithArrayProvider();
        runner.addControllerService("provider", provider);
        runner.enableControllerService(provider);
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (URLClassLoader loader : loaders) {
            loader.close();
        }
    }

    @Test
    public void arrayIsBoundThroughDriverOfConnectionClassLoader() throws Exception {
        FakeOracleConnection con = new FakeOracleConnection(newDriverClassLoader());

        provider.createPreparedStatement("select 1", null, List.of("a", "b"), con.proxy,
                DBElementType.CHAR, 2, 1);

        Assertions.assertAll(
                () -> Assertions.assertEquals("ARRAYOFSTRINGS", con.createdTypeName, "createOracleArray type name"),
                () -> Assertions.assertArrayEquals(new Object[]{"a", "b"}, (Object[]) con.createdElements,
                        "createOracleArray elements"),
                () -> Assertions.assertEquals(Map.of(2, con.array, 3, con.array), con.boundArrays,
                        "setArray calls by parameter index"));
    }

    @Test
    public void connectionFromAnotherClassLoaderGetsItsOwnDriverClass() throws Exception {
        FakeOracleConnection cachedLoaderCon = new FakeOracleConnection(newDriverClassLoader());
        FakeOracleConnection otherLoaderCon = new FakeOracleConnection(newDriverClassLoader());

        provider.createPreparedStatement("select 1", null, List.of("a"), cachedLoaderCon.proxy,
                DBElementType.CHAR, 1, 0);
        provider.createPreparedStatement("select 1", null, List.of("b"), otherLoaderCon.proxy,
                DBElementType.CHAR, 1, 0);

        Assertions.assertEquals(Map.of(1, otherLoaderCon.array), otherLoaderCon.boundArrays,
                "setArray calls on the connection from the other classloader");
    }

    @Test
    public void connectionFromServiceClassLoaderIsSupported() throws Exception {
        FakeOracleConnection con = new FakeOracleConnection(getClass().getClassLoader());

        provider.createPreparedStatement("select 1", null, List.of("a"), con.proxy, DBElementType.CHAR, 1, 0);

        Assertions.assertEquals(Map.of(1, con.array), con.boundArrays, "setArray calls by parameter index");
    }

    @Test
    public void connectionWithoutDriverOnItsClassLoaderIsRejected() {
        URLClassLoader noDriver = register(new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader()));
        Connection con = (Connection) Proxy.newProxyInstance(noDriver, new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });

        RuntimeException e = Assertions.assertThrows(RuntimeException.class, () -> provider.createPreparedStatement(
                "select 1", null, List.of("a"), con, DBElementType.CHAR, 1, 0));
        Assertions.assertTrue(String.valueOf(e.getMessage()).contains("Database Driver Locations"),
                () -> "exception message names the remedy: " + e);
    }

    private URLClassLoader newDriverClassLoader() throws ClassNotFoundException {
        URL ojdbcJar = Class.forName(ORACLE_CONNECTION).getProtectionDomain().getCodeSource().getLocation();
        return register(new URLClassLoader(new URL[]{ojdbcJar}, ClassLoader.getPlatformClassLoader()));
    }

    private URLClassLoader register(final URLClassLoader loader) {
        loaders.add(loader);
        return loader;
    }

    /**
     * An {@code OracleConnection} proxy defined in the given classloader that records the arrays it creates and binds.
     */
    private static final class FakeOracleConnection {
        private final Connection proxy;
        private final Array array;
        private final Map<Integer, Array> boundArrays = new TreeMap<>();
        private String createdTypeName;
        private Object createdElements;

        FakeOracleConnection(final ClassLoader loader) throws ClassNotFoundException {
            Class<?> oracleConnection = Class.forName(ORACLE_CONNECTION, false, loader);
            this.array = (Array) Proxy.newProxyInstance(loader, new Class<?>[]{Array.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "equals" -> p == args[0];
                        case "hashCode" -> System.identityHashCode(p);
                        case "toString" -> "Array@" + Integer.toHexString(System.identityHashCode(p));
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(loader,
                    new Class<?>[]{PreparedStatement.class},
                    (p, method, args) -> {
                        if (method.getName().equals("setArray")) {
                            boundArrays.put((Integer) args[0], (Array) args[1]);
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            this.proxy = (Connection) Proxy.newProxyInstance(loader, new Class<?>[]{oracleConnection},
                    (p, method, args) -> switch (method.getName()) {
                        case "unwrap" -> {
                            Class<?> iface = (Class<?>) args[0];
                            if (!iface.isInstance(p)) {
                                throw new SQLException("Not a wrapper for " + iface);
                            }
                            yield p;
                        }
                        case "prepareStatement" -> statement;
                        case "createOracleArray" -> {
                            createdTypeName = (String) args[0];
                            createdElements = args[1];
                            yield array;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
