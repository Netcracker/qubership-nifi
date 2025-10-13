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

import org.qubership.nifi.service.dbjson.DBToJsonMappingTable;
import org.qubership.nifi.service.dbjson.JsonToDBGraph;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.Map;

public interface DbJsonMappingService extends ControllerService {

    /**
     * Business Entity type property descriptor.
     */
    PropertyDescriptor BE_TYPE = new PropertyDescriptor.Builder()
            .name("be-type")
            .displayName("Business Entity type")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Source System property descriptor.
     */
    PropertyDescriptor SOURCE_SYSTEM = new PropertyDescriptor.Builder()
            .name("source-system")
            .displayName("Source System")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .addValidator(Validator.VALID)
            .build();


    /**
     * Gets DB to JSON mapping table based on controller service parameters.
     * @return DB to JSON mapping table
     */
    DBToJsonMappingTable db2Json();
    /**
     * Looks up DB to JSON mapping table based on FlowFile attributes.
     * Attributes must contain be.type = Business Entity (BE) type.
     * @param attributes List of FlowFile attributes
     * @return DBToJsonMappingTable object
     */
    DBToJsonMappingTable db2Json(Map<String, String> attributes);


    /**
     * Gets JSON to DB mapping based on controller service parameters.
     * @return JsonToDBGraph object
     */
    JsonToDBGraph json2DB();

    /**
     * Looks up JSON to DB mapping based on FlowFile attributes.
     * Attributes must contain be.type = Business Entity (BE) type.
     * @param attributes List of FlowFile attributes
     * @return JsonToDBGraph object
     */
    JsonToDBGraph json2DB(Map<String, String> attributes);

    /**
     * Gets Business Entity (BE) type associated with this controller service.
     * @return Business Entity (BE) type
     */
    String getBeType();

    /**
     * Gets Source System associated with this controller service.
     * @return Source System
     */
    String getSourceSystem();

    /**
     * Used to check if controller service is lookup controller service or not.
     * @return true, if this controller service is lookup controller service.
     */
    boolean isLookup();

}
