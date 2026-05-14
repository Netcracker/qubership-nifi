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

import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.processor.ProcessContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;

/**
 * Controller service providing PreparedStatement and setting parameters in it.
 */
public interface PreparedStatementProvider extends ControllerService {

    /**
     * Creates PreparedStatement with specified query and sets ids as string array parameter.
     * @param query SQL query
     * @param context NiFI ProcessContext to use
     * @param ids a collection of ids
     * @param con Connection to DB
     * @return PreparedStatement
     * @throws SQLException
     */
    PreparedStatement createPreparedStatement(
            String query,
            ProcessContext context,
            Collection<String> ids,
            Connection con
    ) throws SQLException;

    /**
     * Creates PreparedStatement with specified query and sets ids as array parameter with specified element type.
     * @param query SQL query
     * @param context NiFI ProcessContext to use
     * @param ids a collection of ids
     * @param con Connection to DB
     * @param type type of array element to convert to
     * @return PreparedStatement
     * @throws SQLException
     */
    PreparedStatement createPreparedStatement(
            String query,
            ProcessContext context,
            Collection<String> ids,
            Connection con,
            DBElementType type
    ) throws SQLException;


    /**
     * Creates PreparedStatement with specified query and sets ids as string array parameter specified number of times.
     * @param query SQL query
     * @param context NiFI ProcessContext to use
     * @param ids a collection of ids
     * @param con Connection to DB
     * @param numberOfBinds number of binds to add
     * @param bindsOffset offset for binds indexes
     * @return PreparedStatement
     * @throws SQLException
     */
    PreparedStatement createPreparedStatement(
            String query,
            ProcessContext context,
            Collection<String> ids,
            Connection con,
            int numberOfBinds,
            int bindsOffset
    ) throws SQLException;

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
    PreparedStatement createPreparedStatement(
            String query,
            ProcessContext context,
            Collection<String> ids,
            Connection con,
            DBElementType type,
            int numberOfBinds,
            int bindsOffset
    ) throws SQLException;
}
