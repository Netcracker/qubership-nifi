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

import org.qubership.nifi.JsonUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.MalformedRecordException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.record.DataType;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordSchema;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.sql.PreparedStatement;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;

import static org.apache.nifi.serialization.record.RecordFieldType.ARRAY;
import static org.apache.nifi.serialization.record.RecordFieldType.CHOICE;
import static org.apache.nifi.serialization.record.RecordFieldType.MAP;
import static org.apache.nifi.serialization.record.RecordFieldType.RECORD;

@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"DBCP", "Record", "SQL"})
@CapabilityDescription("Executes given SQL statement using data from input records. All records within single  \n"
        + "FlowFile are processed within single transaction.")
@WritesAttributes({
        @WritesAttribute(attribute = "putsql.error", description = "If execution resulted in error, this attribute \n"
                + " is populated with error message"),
        @WritesAttribute(attribute = "parse.error", description = "If execution resulted in error while parsing \n"
                + "and reading the input, this attribute is populated with error message")
})
public class PutSQLRecord extends AbstractProcessor {

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successfully processed FlowFile")
            .build();

    public static final Relationship REL_RETRY = new Relationship.Builder()
            .name("retry")
            .description("A FlowFile is routed to this relationship, if DB query failed with recoverable error")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("A FlowFile is routed to this relationship, if DB query failed with non-recoverable error")
            .build();

    public static final PropertyDescriptor RECORD_READER = new PropertyDescriptor.Builder()
            .name("record-reader")
            .displayName("Record Reader")
            .description("Record Reader")
            .required(true)
            .addValidator(Validator.VALID)
            .identifiesControllerService(RecordReaderFactory.class)
            .build();

    public static final PropertyDescriptor SQL_STATEMENT = new PropertyDescriptor.Builder()
            .name("sql-statement")
            .displayName("SQL Statement")
            .description("SQL statement that should be executed for each record. Statement must contains exactly"
                    + " the same number and types of binds as number and types of fields in RecordSchema.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor DBCP_SERVICE = new PropertyDescriptor.Builder()
            .name("dbcp-service")
            .displayName("Database Connection Pooling Service")
            .description("Database Connection Pooling Service to use for connecting to target Database")
            .required(true)
            .addValidator(Validator.VALID)
            .identifiesControllerService(DBCPService.class)
            .build();

    public static final PropertyDescriptor MAX_BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("max-batch-size")
            .displayName("Maximum Batch Size")
            .description("Maximum number of records to include into DB batch")
            .required(false)
            .defaultValue("100")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();

    public static final PropertyDescriptor CONVERT_PAYLOAD = new PropertyDescriptor.Builder()
            .name("convert-payload")
            .displayName("Convert Payload")
            .description("When set to true, Map/Record/Array/Choice fields will be converted to JSON strings. "
                    + "Otherwise processor will throw exception, if Map/Record/Array/Choice fields are present"
                    + " in the input Record. By default is set to false.")
            .required(false)
            .defaultValue("false")
            .allowableValues("true", "false")
            .addValidator(Validator.VALID)
            .build();

    protected static final String ERROR_MSG_ATTR = "putsql.error";
    protected static final String PARSING_ERROR_MSG_ATTR = "parse.error";

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    private DBCPService dbcp;
    private RecordReaderFactory recordReaderFactory;
    private int maxBatchSize;
    private boolean convertPayload;

    /**
     * Initializes the processor by setting up shared resources and configuration needed for creating
     * sessions during data processing. This method is called once by the framework when the processor
     * is first instantiated or loaded, and is responsible for performing one-time initialization tasks.
     *
     * @param context the initialization context providing access to controller services, configuration
     *  properties, and utility methods
     */
    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptorsList = new ArrayList<>();
        descriptorsList.add(RECORD_READER);
        descriptorsList.add(SQL_STATEMENT);
        descriptorsList.add(DBCP_SERVICE);
        descriptorsList.add(MAX_BATCH_SIZE);
        descriptorsList.add(CONVERT_PAYLOAD);
        this.descriptors = Collections.unmodifiableList(descriptorsList);

        final Set<Relationship> relationshipList = new HashSet<>();
        relationshipList.add(REL_SUCCESS);
        relationshipList.add(REL_RETRY);
        relationshipList.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationshipList);
    }

    /**
     * Returns:
     * Set of all relationships this processor expects to transfer a flow file to.
     * An empty set indicates this processor does not have any destination relationships.
     * Guaranteed non-null.
     *
     */
    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    /**
     * Returns a List of all PropertyDescriptors that this component supports.
     * Returns:
     * PropertyDescriptor objects this component currently supports
     *
     */
    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    /**
     * This method will be called before any onTrigger calls and will be called once each time the Processor
     * is scheduled to run. This happens in one of two ways: either the user clicks to schedule the component to run,
     * or NiFi restarts with the "auto-resume state" configuration set to true (the default) and the component
     * is already running.
     *
     * @param context
     */
    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        this.dbcp = context.getProperty(DBCP_SERVICE).asControllerService(DBCPService.class);
        this.recordReaderFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);
        this.maxBatchSize = context.getProperty(MAX_BATCH_SIZE).asInteger();
        this.convertPayload = context.getProperty(CONVERT_PAYLOAD).asBoolean() != null
                && context.getProperty(CONVERT_PAYLOAD).asBoolean();
    }

    private int getSqlType(DataType dt) {
        switch (dt.getFieldType()) {
            case ARRAY:
            case RECORD:
            case CHOICE:
            case MAP:
                if (convertPayload) {
                    return Types.VARCHAR;
                } else {
                    throw new UnsupportedOperationException("Record contains unsupported type = " + dt.getFieldType());
                }
            case BIGINT:
                return Types.NUMERIC;
            case BYTE:
                return Types.TINYINT;
            case INT:
                return Types.INTEGER;
            case LONG:
                return Types.BIGINT;
            case SHORT:
                return Types.SMALLINT;
            case STRING:
                return Types.VARCHAR;
            case DOUBLE:
                return Types.DOUBLE;
            case CHAR:
                return Types.CHAR;
            case FLOAT:
                return Types.FLOAT;
            case BOOLEAN:
                return Types.BOOLEAN;
            case DATE:
                return Types.DATE;
            case TIME:
                return Types.TIME;
            case TIMESTAMP:
                return Types.TIMESTAMP;
            default:
                throw new UnsupportedOperationException("Record contains unknown type = " + dt.getFieldType());
        }
    }

    private void setFieldParameter(PreparedStatement ps, Object value, DataType dt, int i)
            throws SQLException {
        if (value == null) {
            ps.setNull(i, getSqlType(dt));
            return;
        }
        switch (dt.getFieldType()) {
            case ARRAY:
            case RECORD:
            case CHOICE:
            case MAP:
                if (convertPayload) {
                    ps.setString(i, (String) value);
                } else {
                    throw new UnsupportedOperationException("Record contains unsupported type = " + dt.getFieldType());
                }
                break;
            case BIGINT:
                ps.setBigDecimal(i, new BigDecimal((BigInteger) value));
                break;
            case BYTE:
                ps.setByte(i, (Byte) value);
                break;
            case INT:
                ps.setInt(i, (Integer) value);
                break;
            case LONG:
                ps.setLong(i, (Long) value);
                break;
            case SHORT:
                ps.setShort(i, (Short) value);
                break;
            case STRING:
                ps.setString(i, (String) value);
                break;
            case DOUBLE:
                ps.setDouble(i, (Double) value);
                break;
            case CHAR:
                ps.setString(i, String.valueOf((Character) value));
                break;
            case FLOAT:
                ps.setFloat(i, (Float) value);
                break;
            case BOOLEAN:
                ps.setBoolean(i, (Boolean) value);
                break;
            case DATE:
                ps.setDate(i, (java.sql.Date) value);
                break;
            case TIME:
                ps.setTime(i, (java.sql.Time) value);
                break;
            case TIMESTAMP:
                ps.setTimestamp(i, (java.sql.Timestamp) value);
                break;
            default:
                throw new UnsupportedOperationException("Record contains unknown type = " + dt.getFieldType());
        }
    }

    private void validateSchemaAndSQL(RecordSchema schema, PreparedStatement ps) throws SQLException {
        ParameterMetaData pmd = ps.getParameterMetaData();
        if (schema.getFieldCount() != pmd.getParameterCount()) {
            throw new IllegalArgumentException("Number of fields in record (" + schema.getFieldCount()
                    + ") does not match number of parameters (" + pmd.getParameterCount() + ")");
        }
        for (int i = 0; i < schema.getFieldCount(); i++) {
            RecordField field = schema.getField(i);
            //check type:
            int schemaSqlType = getSqlType(field.getDataType());
        }
    }

    private Map<String, Object> convert(Object value) {
        Map<String, Object> payload = null;
        if (value instanceof org.apache.nifi.serialization.record.Record) {
            payload = new HashMap<>(((org.apache.nifi.serialization.record.Record) value).toMap());
            for (Map.Entry<String, Object> ob : payload.entrySet()) {
                if (ob.getValue() instanceof org.apache.nifi.serialization.record.Record) {
                    ob.setValue(convert(ob.getValue()));
                }
            }
        }
        return payload;
    }

    private void processRecords(ProcessSession session, FlowFile ff, PreparedStatement ps)
            throws SQLException, IOException, MalformedRecordException, SchemaNotFoundException {
        try (InputStream in = session.read(ff);
            RecordReader rr = recordReaderFactory.createRecordReader(ff, in, getLogger());) {
            RecordSchema schema = rr.getSchema();
            validateSchemaAndSQL(schema, ps);
            org.apache.nifi.serialization.record.Record rec = null;
            int batchSize = 0;
            while ((rec = rr.nextRecord()) != null) {
                Object[] values = rec.getValues();
                for (int i = 0; i < values.length; i++) {
                    DataType dt = schema.getField(i).getDataType();
                    Object value = values[i];
                    if (Boolean.TRUE.equals(convertPayload)) {
                        if (dt.getFieldType() == ARRAY || dt.getFieldType() == CHOICE || dt.getFieldType() == MAP) {
                            value = JsonUtils.MAPPER.writeValueAsString(value);
                        }
                        if (dt.getFieldType() == RECORD) {
                            value = JsonUtils.MAPPER.writeValueAsString(convert(value));
                        }
                    }
                    setFieldParameter(ps, value, dt, i + 1);
                }
                ps.addBatch();
                batchSize++;
                if (batchSize >= maxBatchSize) {
                    //execute:
                    ps.executeBatch();
                    ps.clearBatch();
                    ps.clearParameters();
                    batchSize = 0;
                }
            }
            if (batchSize > 0) {
                //execute remaining:
                ps.executeBatch();
            }
        }
    }

    /**
     * The method called when this processor is triggered to operate by the controller.
     * When this method is called depends on how this processor is configured within a controller
     * to be triggered (timing or event based).
     * Params:
     * context – provides access to convenience methods for obtaining property values, delaying the scheduling of the
     *           processor, provides access to Controller Services, etc.
     * session – provides access to a ProcessSession, which can be used for accessing FlowFiles, etc.
     */
    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile ff = session.get();
        if (ff == null) {
            return;
        }
        String sqlStatement = context.getProperty(SQL_STATEMENT).evaluateAttributeExpressions(ff).getValue();
        try (Connection con = dbcp.getConnection(ff.getAttributes());) {

            boolean originalAutoCommit = con.getAutoCommit();
            //set to manual - commit after all records:
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sqlStatement);) {
                processRecords(session, ff, ps);
                //commit after all work is done:
                con.commit();
                session.transfer(ff, REL_SUCCESS);
            } catch (IOException ex) {
                getLogger().error("Failed to read input records", ex);
                con.rollback();
                session.putAttribute(ff, ERROR_MSG_ATTR, ex.getMessage());
                session.putAttribute(ff, PARSING_ERROR_MSG_ATTR, ex.getMessage());
                session.transfer(ff, REL_FAILURE);
            } catch (MalformedRecordException ex) {
                getLogger().error("Failed to read input records", ex);
                con.rollback();
                session.putAttribute(ff, ERROR_MSG_ATTR, ex.getMessage());
                session.putAttribute(ff, PARSING_ERROR_MSG_ATTR, ex.getMessage());
                session.transfer(ff, REL_FAILURE);
            } catch (UnsupportedOperationException | IllegalArgumentException ex) {
                session.putAttribute(ff, PARSING_ERROR_MSG_ATTR, ex.getMessage());
                throw ex;
            }  catch (SchemaNotFoundException ex) {
                getLogger().error("Failed to read input records schema", ex);
                con.rollback();
                session.putAttribute(ff, ERROR_MSG_ATTR, ex.getMessage());
                session.putAttribute(ff, PARSING_ERROR_MSG_ATTR, ex.getMessage());
                session.transfer(ff, REL_FAILURE);
            } catch (SQLRecoverableException ex) {
                getLogger().error("Failed to execute sqlStatement = {} for incoming records",
                        new Object[]{sqlStatement}, ex);
                con.rollback();
                session.putAttribute(ff, ERROR_MSG_ATTR, ex.getMessage());
                session.transfer(ff, REL_RETRY);
            } catch (SQLException ex) {
                getLogger().error("Failed to execute sqlStatement = {} for incoming records",
                        new Object[]{sqlStatement}, ex);
                con.rollback();
                session.putAttribute(ff, ERROR_MSG_ATTR, ex.getMessage());
                session.transfer(ff, REL_FAILURE);
            } finally {
                con.setAutoCommit(originalAutoCommit);
            }

            session.getProvenanceReporter().send(ff, con.getMetaData().getURL());
        } catch (Exception ex) {
            getLogger().error("Failed to execute PutSQLRecord service actions", ex);
            throw new ProcessException("Failed to execute PutSQLRecord service actions", ex);
        }
    }

}
