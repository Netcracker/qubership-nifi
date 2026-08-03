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
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.processor.ProcessContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;

@Tags({"properties"})
@CapabilityDescription("Provides a prepared statement service.")
public class H2PreparedStatement extends AbstractControllerService implements PreparedStatementProvider {

    @Override
    public PreparedStatement createPreparedStatement(String query,
                                                     ProcessContext context,
                                                     Collection<String> ids,
                                                     Connection con,
                                                     int numberOfBinds,
                                                     int bindsOffset) throws SQLException {
        PreparedStatement result = con.prepareStatement(query);

        int i = bindsOffset + 1;
        for (int cnt = bindsOffset + 1; cnt < bindsOffset + numberOfBinds + 1; cnt++) {
            for (String id : ids) {
                int a = Integer.parseInt(id);
                result.setInt(i, a);
                i++;
            }
        }

        return result;
    }

    @Override
    public PreparedStatement createPreparedStatement(String query,
                                                     ProcessContext context,
                                                     Collection<String> ids,
                                                     Connection con) throws SQLException {
        return createPreparedStatement(query, context, ids, con, 1, 0);
    }

    @Override
    public PreparedStatement createPreparedStatement(String query,
                                                     ProcessContext context,
                                                     Collection<String> ids,
                                                     Connection con, DBElementType type) throws SQLException {
        return createPreparedStatement(query, context, ids, con);
    }

    @Override
    public PreparedStatement createPreparedStatement(String query,
                                                     ProcessContext context,
                                                     Collection<String> ids,
                                                     Connection con, DBElementType type,
                                                     int numberOfBinds, int bindsOffset) throws SQLException {
        return createPreparedStatement(query, context, ids, con, numberOfBinds, bindsOffset);
    }
}
