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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Array;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.util.StandardValidators;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.expression.ExpressionLanguageScope;

/**
 * Controller service providing PreparedStatement and setting parameters in it for Oracle DB.
 */
@Tags({"properties"})
@CapabilityDescription("Provides a prepared statement service.")
public class OraclePreparedStatementWithArrayProvider
        extends AbstractPreparedStatementProvider
        implements PreparedStatementProvider {

    public static final PropertyDescriptor SCHEMA = new PropertyDescriptor.Builder()
            .name("dbSchema")
            .displayName("Schema Name")
            .description("Owner of the array type")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor CHAR_ARRAY_TYPE = new PropertyDescriptor.Builder()
            .name("array-type")
            .displayName("Char Array Type")
            .description("Character-based array type.")
            .defaultValue("ARRAYOFSTRINGS")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .required(false)
            .build();

    public static final PropertyDescriptor NUM_ARRAY_TYPE = new PropertyDescriptor.Builder()
            .name("num-array-type")
            .displayName("Numeric Array Type")
            .description("Numeric array type.")
            .defaultValue("ARRAYOFNUMBERS")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .required(false)
            .build();

    private static final String DELIMITER = ".";

    private List<PropertyDescriptor> propDescriptors;

    private String dbSchema;
    private String fullCharTypeName;
    private String fullNumTypeName;
    private Class oracleConnection;
    private Method createArrayMethod;

    public OraclePreparedStatementWithArrayProvider() {
        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(SCHEMA);
        pds.add(CHAR_ARRAY_TYPE);
        pds.add(NUM_ARRAY_TYPE);

        propDescriptors = Collections.unmodifiableList(pds);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propDescriptors;
    }

    @OnEnabled
    public void onEnable(ConfigurationContext context) {
        this.dbSchema = context.getProperty(SCHEMA).evaluateAttributeExpressions().getValue();
        this.charArrayType = context.getProperty(CHAR_ARRAY_TYPE).evaluateAttributeExpressions().getValue();
        this.numArrayType = context.getProperty(NUM_ARRAY_TYPE).evaluateAttributeExpressions().getValue();
        if (StringUtils.isNotEmpty(this.dbSchema)) {
            this.fullCharTypeName = this.dbSchema + DELIMITER + this.charArrayType;
            this.fullNumTypeName = this.dbSchema + DELIMITER + this.numArrayType;
        }
        else {
            //use local name
            this.fullCharTypeName = this.charArrayType;
            this.fullNumTypeName = this.numArrayType;
        }
        try {
            this.oracleConnection = Class.forName("oracle.jdbc.OracleConnection");
        } catch (ClassNotFoundException ex) {
            getLogger().error("Unable to find OracleConnection", ex);
            throw new RuntimeException("Oracle JDBC driver classes not found. Check NiFi classpath for presence of ojdbc jar");
        }
        try {
            this.createArrayMethod = this.oracleConnection.getMethod("createOracleArray", new Class[]{String.class, Object.class});
        } catch (NoSuchMethodException | SecurityException ex) {
            getLogger().error("Unable to find createOracleArray method", ex);
            throw new RuntimeException("Oracle JDBC driver connection doesn't have necessary method createOracleArray. Check ojdbc jar version");
        }
    }

    @Override
    public PreparedStatement createPreparedStatement(String query, ProcessContext context, Collection<String> ids, Connection con, DBElementType type, int numberOfBinds, int bindsOffset) throws SQLException {
        Object oraCon = con.unwrap(this.oracleConnection);
        PreparedStatement result = con.prepareStatement(query);
        String arrayType = getArrayType(type);
        Object[] idArray = convertArray(ids, type);

        for (int cnt = bindsOffset+1; cnt < bindsOffset+numberOfBinds+1; cnt++) {
            try {
                result.setArray(cnt, (Array)this.createArrayMethod.invoke(oraCon, new Object[]{arrayType, idArray}));
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                getLogger().error("Failed to invoke createOracleArray method", ex);
                throw new RuntimeException("Failed to invoke createOracleArray method while creating prepared statement");
            }
        }

        return result;
    }
}
