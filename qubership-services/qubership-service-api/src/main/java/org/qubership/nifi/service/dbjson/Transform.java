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

package org.qubership.nifi.service.dbjson;

/**
 * Interface for transforming JSON coordinates (JSON path or JSON attribute) to DB coordinates (column and table name) and back
 * @param <SourceType> source type
 * @param <TargetType> target type
 */
public interface Transform<SourceType, TargetType> {

    /**
     * Get jsonPath by DB table and column and back
     * @param sourceObject jsonPath (or DB table and column)
     * @return DB table and column (or jsonPath)
     * @throws Exception there is no such object
     */
    TargetType transform(SourceType sourceObject) throws Exception;

    /**
     * Conversion from attributeName to columnName and back
     * @param value attribute (or column) name
     * @return column (or attribute) name
     */
    String convertColumn(String value);
}
