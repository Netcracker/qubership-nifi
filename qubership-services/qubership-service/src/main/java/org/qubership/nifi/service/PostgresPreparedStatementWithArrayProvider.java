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


/**
 * Controller service providing PreparedStatement and setting parameters in it for PostgreSQL DB.
 */
@Tags({"properties"})
@CapabilityDescription("Provides a prepared statement service.")
public class PostgresPreparedStatementWithArrayProvider
        extends AbstractPreparedStatementProvider
        implements PreparedStatementProvider {

    /**
     * Char Array Type Property Descriptor.
     */
    public static final PropertyDescriptor CHAR_ARRAY_TYPE = new PropertyDescriptor.Builder()
            .name("array-type")
            .displayName("Char Array Type")
            .description("Character array base type.")
            .defaultValue("text")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(false)
            .build();

    /**
     * Numeric Array Type Property Descriptor.
     */
    public static final PropertyDescriptor NUMERIC_ARRAY_TYPE = new PropertyDescriptor.Builder()
            .name("numeric-array-type")
            .displayName("Numeric Array Type")
            .description("Numeric array base type.")
            .defaultValue("numeric")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(false)
            .build();

    private List<PropertyDescriptor> propDescriptors;

    /**
     * Default constructor.
     */
    public PostgresPreparedStatementWithArrayProvider() {
        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(CHAR_ARRAY_TYPE);
        pds.add(NUMERIC_ARRAY_TYPE);

        propDescriptors = Collections.unmodifiableList(pds);
    }

    /**
     * Gets list of supported property descriptors.
     * @return a list of property descriptors.
     */
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propDescriptors;
    }

    /**
     * Initializes controller service: reads properties from context before use.
     * @param context configuration context
     */
    @OnEnabled
    public void onEnable(ConfigurationContext context) {
        this.charArrayType = context.getProperty(CHAR_ARRAY_TYPE).getValue();
        this.numArrayType = context.getProperty(NUMERIC_ARRAY_TYPE).getValue();
    }

    /**
     * Creates PreparedStatement with specified query and sets ids as array parameter
     * with specified element type specified number of times.
     * @param query SQL query
     * @param context NiFI ProcessContext to use
     * @param ids a collection of ids
     * @param con Connection to DB
     * @param type type of array element to convert to
     * @param numberOfBinds number of binds to add
     * @param bindsOffset offset for binds indexes
     * @return PreparedStatement
     * @throws SQLException
     */
    @SuppressWarnings("java:S2095")
    @Override
    public PreparedStatement createPreparedStatement(String query, ProcessContext context, Collection<String> ids,
                                                     Connection con, DBElementType type, int numberOfBinds,
                                                     int bindsOffset) throws SQLException {
        PreparedStatement result = con.prepareStatement(query);
        String arrayType = getArrayType(type);
        Object[] idArray = convertArray(ids, type);

        for (int cnt = bindsOffset + 1; cnt < bindsOffset + numberOfBinds + 1; cnt++) {
            result.setArray(cnt, con.createArrayOf(arrayType, idArray));
        }
        return result;
    }
}
