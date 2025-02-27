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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.processor.ProcessContext;

/**
 * Abstract controller service providing PreparedStatement and setting parameters in it.
 */
public abstract class AbstractPreparedStatementProvider 
        extends AbstractControllerService 
        implements PreparedStatementProvider {
    
    protected String charArrayType;
    protected String numArrayType;

    /**
     * Create instance of PreparedStatementProvider
     */
    public AbstractPreparedStatementProvider() {
    }

    protected Object[] convertArray(Collection<String> ids, DBElementType type) {
        List<Object> res = new ArrayList<>();
        for (String id : ids) {
            if (StringUtils.isNotEmpty(id) && !"null".equalsIgnoreCase(id)) {
                if (type == DBElementType.NUMERIC) {
                    res.add(new BigDecimal(id));
                } else {
                    res.add(id);
                }
            }
        }
        return res.toArray();
    }
    
    protected String getArrayType(DBElementType type) {
        if (type == DBElementType.NUMERIC) {
            return this.numArrayType;
        } else if (type == DBElementType.CHAR) {
            return this.charArrayType;
        }
        return this.charArrayType;
    }
    
    @Override
    public PreparedStatement createPreparedStatement(String query, ProcessContext context, Collection<String> ids, Connection con, int numberOfBinds, int bindsOffset) throws SQLException {
        return createPreparedStatement(query, context, ids, con, DBElementType.CHAR, numberOfBinds, bindsOffset);
    }

    @Override
    public PreparedStatement createPreparedStatement(String query, ProcessContext context, Collection<String> ids, Connection con) throws SQLException {
        return createPreparedStatement(query, context, ids, con, 1, 0);
    }

    @Override
    public PreparedStatement createPreparedStatement(String query, ProcessContext context, Collection<String> ids, Connection con, DBElementType type) throws SQLException {
        return createPreparedStatement(query, context, ids, con, type, 1, 0);
    }   
    
}
