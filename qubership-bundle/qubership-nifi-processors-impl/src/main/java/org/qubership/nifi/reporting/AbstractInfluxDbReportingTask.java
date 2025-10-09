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

package org.qubership.nifi.reporting;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.AbstractReportingTask;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.InfluxDBIOException;

public abstract class AbstractInfluxDbReportingTask
        extends AbstractReportingTask {
    /**
     * Character Set property descriptor.
     */
    public static final PropertyDescriptor CHARSET = new PropertyDescriptor.Builder()
            .name("influxdb-charset")
            .displayName("Character Set")
            .description("Specifies the character set of the document data.")
            .required(true)
            .defaultValue("UTF-8")
            .addValidator(StandardValidators.CHARACTER_SET_VALIDATOR)
            .build();

    /**
     * Influx DB URL property descriptor.
     */
    public static final PropertyDescriptor INFLUX_DB_URL = new PropertyDescriptor.Builder()
            .name("influxdb-url")
            .displayName("InfluxDB URL")
            .description("InfluxDB URL to connect to. For example, http://influxdb:8086")
            .defaultValue("http://localhost:8086")
            .required(true)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    /**
     * Connection timeout property descriptor.
     */
    public static final PropertyDescriptor INFLUX_DB_CONNECTION_TIMEOUT = new PropertyDescriptor.Builder()
            .name("Connection timeout")
            .description("The maximum time for establishing connection to the InfluxDB")
            .defaultValue("0 seconds")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .sensitive(false)
            .build();

    /**
     * Database Name property descriptor.
     */
    public static final PropertyDescriptor DB_NAME = new PropertyDescriptor.Builder()
            .name("influxdb-dbname")
            .displayName("Database Name")
            .description("InfluxDB database")
            .defaultValue("monitoring_server")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Username property descriptor.
     */
    public static final PropertyDescriptor USERNAME = new PropertyDescriptor.Builder()
            .name("influxdb-username")
            .displayName("Username")
            .required(false)
            .description("Username for accessing InfluxDB")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Password property descriptor.
     */
    public static final PropertyDescriptor PASSWORD = new PropertyDescriptor.Builder()
            .name("influxdb-password")
            .displayName("Password")
            .required(false)
            .description("Password for accessing InfluxDB")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();

    /**
     * Max size of records property descriptor.
     */
    public static final PropertyDescriptor MAX_RECORDS_SIZE = new PropertyDescriptor.Builder()
            .name("influxdb-max-records-size")
            .displayName("Max size of records")
            .description("Maximum size of records allowed to be posted in one batch")
            .defaultValue("1 MB")
            .required(true)
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .build();

    /**
     * Retention Policy property descriptor.
     */
    public static final PropertyDescriptor RETENTION_POLICY = new PropertyDescriptor.Builder()
            .name("influxdb-retention-policy")
            .displayName("Retention Policy")
            .description("Retention policy for the saving the records")
            .defaultValue("monitor")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Consistency level All.
     */
    public static final AllowableValue CONSISTENCY_LEVEL_ALL = new AllowableValue("ALL",
            "All", "Return success when all nodes have responded with write success");
    /**
     * Consistency level Any.
     */
    public static final AllowableValue CONSISTENCY_LEVEL_ANY = new AllowableValue("ANY",
            "Any", "Return success when any nodes have responded with write success");
    /**
     * Consistency level One.
     */
    public static final AllowableValue CONSISTENCY_LEVEL_ONE = new AllowableValue("ONE",
            "One", "Return success when one node has responded with write success");
    /**
     * Consistency level Quorum.
     */
    public static final AllowableValue CONSISTENCY_LEVEL_QUORUM = new AllowableValue("QUORUM",
            "Quorum", "Return success when a majority of nodes have responded with write success");

    /**
     * Consistency Level property descriptor.
     */
    public static final PropertyDescriptor CONSISTENCY_LEVEL = new PropertyDescriptor.Builder()
            .name("influxdb-consistency-level")
            .displayName("Consistency Level")
            .description("InfluxDB consistency level")
            .required(true)
            .defaultValue(CONSISTENCY_LEVEL_ONE.getValue())
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .allowableValues(CONSISTENCY_LEVEL_ONE, CONSISTENCY_LEVEL_ANY,
                    CONSISTENCY_LEVEL_ALL, CONSISTENCY_LEVEL_QUORUM)
            .build();

    /**
     * InfluxDB instance.
     */
    protected InfluxDB influxDB;
    /**
     * Consistency level.
     */
    protected String consistencyLevel;
    /**
     * Database name.
     */
    protected String database;
    /**
     * Retention policy.
     */
    protected String retentionPolicy;
    /**
     * K8s namespace.
     */
    protected String namespace;
    /**
     * NiFi hostname.
     */
    protected String hostname;
    /**
     * List of all supported property descriptors.
     */
    protected List<PropertyDescriptor> propertyDescriptors;


    /**
     * Initializes list of property descriptors supported by this reporting task.
     * @return list of property descriptors
     */
    protected List<PropertyDescriptor> initProperties() {
        final List<PropertyDescriptor> prop = new ArrayList<>();
        prop.add(DB_NAME);
        prop.add(INFLUX_DB_URL);
        prop.add(INFLUX_DB_CONNECTION_TIMEOUT);
        prop.add(USERNAME);
        prop.add(PASSWORD);
        prop.add(CHARSET);
        prop.add(CONSISTENCY_LEVEL);
        prop.add(RETENTION_POLICY);
        prop.add(MAX_RECORDS_SIZE);
        return prop;
    }

    /**
     * Initializes reporting task's property descriptors.
     */
    @Override
    protected void init(ReportingInitializationContext config) {
        final List<PropertyDescriptor> prop = initProperties();
        propertyDescriptors = Collections.unmodifiableList(prop);
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    /**
     * Initializes reporting task before it's started.
     * @param context reporting context
     */
    @OnScheduled
    @SuppressWarnings(value = "unchecked")
    public void onScheduled(final ConfigurationContext context) {
        consistencyLevel = context.getProperty(CONSISTENCY_LEVEL).getValue();
        database = context.getProperty(DB_NAME).getValue();
        retentionPolicy = context.getProperty(RETENTION_POLICY).getValue();
        namespace = System.getenv("NAMESPACE");
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            getLogger().warn("Error while getting host name {}", new Object[]{ex.getLocalizedMessage()}, ex);
            hostname = "cloud-data-migration-nifi";
        }
        String username = context.getProperty(USERNAME).evaluateAttributeExpressions().getValue();
        String password = context.getProperty(PASSWORD).evaluateAttributeExpressions().getValue();
        String influxDbUrl = context.getProperty(INFLUX_DB_URL).evaluateAttributeExpressions().getValue();
        long connectionTimeout = context.getProperty(INFLUX_DB_CONNECTION_TIMEOUT).asTimePeriod(TimeUnit.SECONDS);
        try {
            this.influxDB = makeConnection(username, password, influxDbUrl, connectionTimeout);
        } catch (Exception e) {
            getLogger().error("Error while getting connection {}", new Object[]{e.getLocalizedMessage()}, e);
            throw new ProcessException("Error while getting connection " + e.getLocalizedMessage(), e);
        }
        getLogger().info("InfluxDB connection created for host {}", new Object[]{influxDbUrl});
    }

    /**
     * Creating a connection to Influx.
     *
     * @param username
     * @param password
     * @param influxDbUrl
     * @param connectionTimeout
     * @return InfluxDB object
     */
    protected InfluxDB makeConnection(String username, String password, String influxDbUrl, long connectionTimeout) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectTimeout(connectionTimeout, TimeUnit.SECONDS);
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            return InfluxDBFactory.connect(influxDbUrl, builder);
        } else {
            return InfluxDBFactory.connect(influxDbUrl, username, password, builder);
        }
    }

    /**
     * Stops Influx server and releases all resources.
     */
    @OnStopped
    public void close() {
        if (getLogger().isDebugEnabled()) {
            getLogger().info("Closing connection");
        }
        if (influxDB != null) {
            influxDB.close();
            influxDB = null;
        }
    }

    /**
     * This method is periodically called to update metrics in meter registry.
     * @param context  reporting context
     */
    @Override
    public void onTrigger(ReportingContext context) {
        try {
            String influxDbMessage = createInfluxMessage(context);
            if (influxDbMessage.isEmpty()) {
                return;
            }
            writeToInfluxDB(consistencyLevel, database, retentionPolicy, influxDbMessage);
        } catch (InfluxDBIOException exception) {
            if (exception.getCause() instanceof SocketTimeoutException) {
                getLogger().error("Failed to insert into influxDB due SocketTimeoutException to {} and retrying",
                        new Object[]{exception.getLocalizedMessage()}, exception);
            } else {
                getLogger().error("Failed to insert into influxDB due to {}",
                        new Object[]{exception.getLocalizedMessage()}, exception);
            }
        } catch (Exception exception) {
            getLogger().error("Failed to insert into influxDB due to {}",
                    new Object[]{exception.getLocalizedMessage()}, exception);
        }
    }

    /**
     * Create Influx message.
     *
     * @param context
     * @return Influx message
     */
    public abstract String createInfluxMessage(ReportingContext context);

    /**
     * Method for escaping tag value.
     *
     * @param str
     * @return tag value
     */
    protected String escapeTagValue(String str) {
        return escapeKeysOrTagValue(str);
    }

    /**
     * Method for escaping keys.
     *
     * @param str
     * @return key
     */
    protected String escapeKey(String str) {
        return escapeKeysOrTagValue(str);
    }

    /**
     * Method for escaping keys or tag value.
     *
     * @param str
     * @return keys or tag value
     */
    protected String escapeKeysOrTagValue(String str) {
        if (str == null) {
            return null;
        }
        //In tag keys, tag values, and field keys, you must escape: space, comma, equal siqn:
        return str.replaceAll(" ", "\\\\ ").replaceAll("=", "\\\\=").replaceAll(",", "\\\\,");
    }

    /**
     * Method for escaping field value.
     *
     * @param str
     * @return field value
     */
    protected String escapeFieldValue(String str) {
        if (str == null) {
            return null;
        }
        //In field values you must escape: backslash, double quotes:
        return str.replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"");
    }

    /**
     * Write message to Influx.
     *
     * @param consistencyLevelValue
     * @param databaseValue
     * @param retentionPolicyValue
     * @param records
     */
    protected void writeToInfluxDB(
            String consistencyLevelValue,
            String databaseValue,
            String retentionPolicyValue,
            String records
    ) {
        influxDB.write(
                databaseValue,
                retentionPolicyValue,
                InfluxDB.ConsistencyLevel.valueOf(consistencyLevelValue),
                records
        );
    }

}
