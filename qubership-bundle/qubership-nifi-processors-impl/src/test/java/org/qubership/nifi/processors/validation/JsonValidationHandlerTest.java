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

package org.qubership.nifi.processors.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import org.apache.nifi.documentation.init.NopComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.qubership.nifi.processors.validation.ValidationErrorAssertions.assertErrors;
import static org.qubership.nifi.processors.validation.ValidationErrorAssertions.readTree;

/**
 * Pins the {@code _errors} array that {@link JsonValidationHandler} builds from the messages of the
 * json-schema-validator library, so that a library upgrade that changes a message, a path, or an error type fails
 * the test for that error.
 */
public class JsonValidationHandlerTest {
    private static final String BE_TYPE_PATH = "_businessEntityType";
    private static final String ID_PATH = "_sourceId";
    private static final String ERROR_CODE = "ME-JV-0002";
    private static final String ITEMS_WRAPPER_REGEX = "(\\$\\._items\\[[0-9]+]\\.)";

    private static ValidationContext process(String schema, String json, String beTypePath, String idPath,
                                             String errorCode, String wrapperRegex) {
        ProcessorProperty property = new ProcessorProperty(beTypePath, idPath, errorCode,
                JsonSchemaFactory.getInstance().getSchema(schema), wrapperRegex);
        return new JsonValidationHandler(readTree(json), property, new NopComponentLog()).process();
    }

    private static JsonNode errorsOf(String schema, String json, String idPath, String wrapperRegex) {
        return process(schema, json, BE_TYPE_PATH, idPath, ERROR_CODE, wrapperRegex).getValidatedJson().get("_errors");
    }

    private static JsonNode errorsOf(String schema, String json) {
        return errorsOf(schema, json, ID_PATH, null);
    }

    @Test
    public void validInputIsReturnedAsTheSameUnchangedNode() {
        JsonNode input = readTree(JsonTestData.VALID_TEST_JSON_BY_SHEMA2);

        ValidationContext context = new JsonValidationHandler(input,
                new ProcessorProperty(BE_TYPE_PATH, ID_PATH, ERROR_CODE,
                        JsonSchemaFactory.getInstance().getSchema(JsonTestData.TEST_JSON_SCHEMA_2), null),
                new NopComponentLog()).process();

        assertEquals(true, context.isValidJson(), "isValidJson()");
        assertSame(input, context.getValidatedJson());
        assertEquals(readTree(JsonTestData.VALID_TEST_JSON_BY_SHEMA2), context.getValidatedJson());
    }

    @Test
    public void typeErrorInAnArrayElementNamesTheElementPath() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity 'key5.key6'",
                  "details": "inner.key31.key5[0].key6: integer found, string expected",
                  "source": {"_sourceId": "CUST#123456789", "path": "inner.key31.key5[0].key6"}
                }]""",
                errorsOf(JsonTestData.TEST_JSON_SCHEMA_2, JsonTestData.INVALID_TEST_JSON_BY_SHEMA2));
    }

    @Test
    public void missingEntityTypeAndIdFallBackToObjectAndAnEmptyId() {
        ValidationContext context = process(JsonTestData.TEST_JSON_SCHEMA_2, JsonTestData.INVALID_TEST_JSON_BY_SHEMA2,
                "CustomBeType", "CustomSourceID", "CustomErrorCode", null);

        assertErrors("""
                [{
                  "code": "CustomErrorCode",
                  "title": "Validation error 'type' in 'object' Business Entity 'key5.key6'",
                  "details": "inner.key31.key5[0].key6: integer found, string expected",
                  "source": {"CustomSourceID": "", "path": "inner.key31.key5[0].key6"}
                }]""",
                context.getValidatedJson().get("_errors"));
    }

    @Test
    public void requiredAndTypeErrorsAtTheRootKeepTheDollarInTheTitle() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'Customer' Business Entity '$.customerNumber'",
                  "details": "customerNumber: is missing but it is required",
                  "source": {"_sourceId": "CUST#123456789", "path": "customerNumber"}
                }, {
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity '$.engagedPartyRefType'",
                  "details": "engagedPartyRefType: integer found, string expected",
                  "source": {"_sourceId": "CUST#123456789", "path": "engagedPartyRefType"}
                }, {
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity '$.customerCategoryId'",
                  "details": "customerCategoryId: integer found, string expected",
                  "source": {"_sourceId": "CUST#123456789", "path": "customerCategoryId"}
                }]""",
                errorsOf(JsonTestData.TEST_JSON_SCHEMA_1, JsonTestData.INVALID_TEST_JSON_BY_SHEMA1));
    }

    @Test
    public void missingPropertyInAnArrayElementNamesTheMissingProperty() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'Customer' Business Entity 'key5.key6'",
                  "details": "inner.key31.key5[1].key6: is missing but it is required",
                  "source": {"_sourceId": "CUST#123456789", "path": "inner.key31.key5[1].key6"}
                }]""",
                errorsOf(JsonTestData.TEST_JSON_SCHEMA_3, JsonTestData.INVALID_TEST_JSON_BY_SHEMA3));
    }

    @Test
    public void requiredPropertyMissingAtTheRootIsNamedInThePath() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'Customer' Business Entity '$.name'",
                  "details": "name: is missing but it is required",
                  "source": {"_sourceId": "S1", "path": "name"}
                }]""",
                errorsOf("""
                        {"required": ["_sourceId", "name"]}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer"}"""));
    }

    @Test
    public void requiredPropertyMissingInANestedObjectIsNamedInThePath() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'Customer' Business Entity 'address.city'",
                  "details": "address.city: is missing but it is required",
                  "source": {"_sourceId": "S1", "path": "address.city"}
                }]""",
                errorsOf("""
                        {"properties": {"address": {"required": ["city"]}}}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "address": {}}"""));
    }

    @Test
    public void valueOutsideTheEnumIsReported() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'enum' in 'Order' Business Entity '$._businessEntityType'",
                  "details": "_businessEntityType: does not have a value in the enumeration [Customer, Organization]",
                  "source": {"_sourceId": "S1", "path": "_businessEntityType"}
                }]""",
                errorsOf("""
                        {"properties": {"_businessEntityType": {"enum": ["Customer", "Organization"]}}}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Order"}"""));
    }

    @Test
    public void invalidDateIsReportedAsADateTimeError() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'date-time' in 'AllCustomerProducts' Business Entity '$._sourceTimeStamp'",
                  "details": "_sourceTimeStamp: 2018-02-31T11:27:38.795Z is an invalid date-time",
                  "source": {"_sourceId": "devapp995.qubership.org_SAMPLE_CUST#00000002", "path": "_sourceTimeStamp"}
                }]""",
                errorsOf(JsonTestData.TEST_JSON_SCHEMA_4, JsonTestData.INVALID_TEST1_JSON_BY_SHEMA4));
    }

    @Test
    public void invalidHourIsReportedAsADateTimeError() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'date-time' in 'AllCustomerProducts' Business Entity '$._sourceTimeStamp'",
                  "details": "_sourceTimeStamp: 2018-02-11T56:27:38.795Z is an invalid date-time",
                  "source": {"_sourceId": "devapp995.qubership.org_SAMPLE_CUST#00000002", "path": "_sourceTimeStamp"}
                }]""",
                errorsOf(JsonTestData.TEST_JSON_SCHEMA_4, JsonTestData.INVALID_TEST2_JSON_BY_SHEMA4));
    }

    @Test
    public void propertyNotAllowedByAdditionalPropertiesIsReported() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'additionalProperties' in 'Customer' Business Entity '$.extra'",
                  "details": "extra: is not defined in the schema and the schema does not allow additional properties",
                  "source": {"_sourceId": "S1", "path": "extra"}
                }]""",
                errorsOf("""
                        {
                          "properties": {"_sourceId": {}, "_businessEntityType": {}},
                          "additionalProperties": false
                        }""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "extra": 1}"""));
    }

    @Test
    public void stringNotMatchingThePatternIsReported() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'pattern' in 'Customer' Business Entity '$.code'",
                  "details": "code: does not match the regex pattern ^[0-9]+$",
                  "source": {"_sourceId": "S1", "path": "code"}
                }]""",
                errorsOf("""
                        {"properties": {"code": {"type": "string", "pattern": "^[0-9]+$"}}}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "code": "ab"}"""));
    }

    @Test
    public void stringShorterThanMinLengthIsReported() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'minLength' in 'Customer' Business Entity '$.name'",
                  "details": "name: must be at least 3 characters long",
                  "source": {"_sourceId": "S1", "path": "name"}
                }]""",
                errorsOf("""
                        {"properties": {"name": {"type": "string", "minLength": 3}}}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "name": "ab"}"""));
    }

    @Test
    public void numberBelowTheMinimumIsReported() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'minimum' in 'Customer' Business Entity '$.count'",
                  "details": "count: must have a minimum value of 10",
                  "source": {"_sourceId": "S1", "path": "count"}
                }]""",
                errorsOf("""
                        {"properties": {"count": {"type": "integer", "minimum": 10}}}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "count": 5}"""));
    }

    @Test
    public void errorDetailsStayInEnglishUnderAnotherDefaultLocale() {
        Locale defaultLocale = Locale.getDefault();
        Locale formatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale displayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        JsonNode errors;
        try {
            Locale.setDefault(Locale.GERMANY);
            errors = errorsOf("""
                    {"properties": {"name": {"type": "string", "minLength": 3}}}""", """
                    {"_sourceId": "S1", "_businessEntityType": "Customer", "name": "ab"}""");
        } finally {
            // Locale.setDefault(Locale) also overwrites both category defaults, so they are restored after it.
            Locale.setDefault(defaultLocale);
            Locale.setDefault(Locale.Category.FORMAT, formatLocale);
            Locale.setDefault(Locale.Category.DISPLAY, displayLocale);
        }

        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'minLength' in 'Customer' Business Entity '$.name'",
                  "details": "name: must be at least 3 characters long",
                  "source": {"_sourceId": "S1", "path": "name"}
                }]""",
                errors);
    }

    @Test
    public void typeErrorBehindARefIsReportedAtTheReferringProperty() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity '$.ref'",
                  "details": "ref: integer found, string expected",
                  "source": {"_sourceId": "S1", "path": "ref"}
                }]""",
                errorsOf("""
                        {
                          "definitions": {"id": {"type": "string"}},
                          "properties": {"ref": {"$ref": "#/definitions/id"}}
                        }""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "ref": 1}"""));
    }

    @Test
    public void valueMatchingNoOneOfBranchReportsTheTypeErrorOfEachBranch() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity '$.value'",
                  "details": "value: boolean found, integer expected",
                  "source": {"_sourceId": "S1", "path": "value"}
                }, {
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity '$.value'",
                  "details": "value: boolean found, string expected",
                  "source": {"_sourceId": "S1", "path": "value"}
                }]""",
                errorsOf("""
                        {"properties": {"value": {"oneOf": [{"type": "string"}, {"type": "integer"}]}}}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "value": true}"""));
    }

    @Test
    public void propertyNameWithADotIsTitledAsANestedPath() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity 'unit.price'",
                  "details": "unit.price: string found, number expected",
                  "source": {"_sourceId": "S1", "path": "unit.price"}
                }]""",
                errorsOf("""
                        {"properties": {"unit.price": {"type": "number"}}}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "unit.price": "x"}"""));
    }

    @Test
    public void schemaWithoutDollarSchemaIsReadAsDraft4() {
        // A boolean exclusiveMaximum is draft 4 syntax; draft 6 and later expect a number there.
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'maximum' in 'Customer' Business Entity '$.count'",
                  "details": "count: must have a maximum value of 10",
                  "source": {"_sourceId": "S1", "path": "count"}
                }]""",
                errorsOf("""
                        {"properties": {"count": {"maximum": 10, "exclusiveMaximum": true}}}""", """
                        {"_sourceId": "S1", "_businessEntityType": "Customer", "count": 10}"""));
    }

    @Test
    public void arrayRootWithErrorsIsRefused() {
        assertThrows(ProcessException.class, () -> process("""
                {"type": "array", "items": {"type": "string"}}""", "[1]",
                BE_TYPE_PATH, ID_PATH, ERROR_CODE, null));
    }

    @Test
    public void arrayRootWithoutErrorsIsValid() {
        ValidationContext context = process("""
                {"type": "array", "items": {"type": "string"}}""", "[\"a\"]",
                BE_TYPE_PATH, ID_PATH, ERROR_CODE, null);

        assertEquals(true, context.isValidJson(), "isValidJson() for [\"a\"]");
    }

    @Test
    public void wrapperRegexMovesThePathAndIdToTheWrappedItem() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' \
                Business Entity 'relatedPartyRef.referredType'",
                  "details": "_items[0].relatedPartyRef[1].referredType: is missing but it is required",
                  "source": {"_sourceId": "PI#TLO_01_CUST#00000002", "path": "relatedPartyRef[1].referredType"}
                }, {
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' Business Entity '_items.state'",
                  "details": "_items[0].state: is missing but it is required",
                  "source": {"_sourceId": "PI#TLO_01_CUST#00000002", "path": "state"}
                }]""",
                errorsOf(JsonTestData.CUSTOMER_PRODUCT_SCHEMA, JsonTestData.ALL_CUSTOMER_PRODUCT_REQUEST,
                        ID_PATH, ITEMS_WRAPPER_REGEX));
    }

    @Test
    public void wrapperRegexUnwrapsOnlyTheErrorsItMatches() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' \
                Business Entity 'relatedPartyRef.referredType'",
                  "details": "_items[0].relatedPartyRef[1].referredType: is missing but it is required",
                  "source": {"_sourceRefId": "CUST#00000002", "path": "referredType"}
                }, {
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' Business Entity '_items.state'",
                  "details": "_items[0].state: is missing but it is required",
                  "source": {"_sourceRefId": "", "path": "_items[0].state"}
                }]""",
                errorsOf(JsonTestData.CUSTOMER_PRODUCT_SCHEMA, JsonTestData.ALL_CUSTOMER_PRODUCT_REQUEST,
                        "_sourceRefId", "(\\$\\._items\\[[0-9]+\\]\\.relatedPartyRef\\[[0-9]+\\]\\.)"));
    }

    @Test
    public void wrapperRegexThatMatchesNothingKeepsTheRootPathAndId() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' \
                Business Entity 'relatedPartyRef.referredType'",
                  "details": "_items[0].relatedPartyRef[1].referredType: is missing but it is required",
                  "source": {"_sourceId": "CUST#00000002", "path": "_items[0].relatedPartyRef[1].referredType"}
                }, {
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' Business Entity '_items.state'",
                  "details": "_items[0].state: is missing but it is required",
                  "source": {"_sourceId": "CUST#00000002", "path": "_items[0].state"}
                }]""",
                errorsOf(JsonTestData.CUSTOMER_PRODUCT_SCHEMA, JsonTestData.ALL_CUSTOMER_PRODUCT_REQUEST,
                        ID_PATH, "(\\$\\.Invalid-regex\\[[0-9]+]\\.)"));
    }

    @Test
    public void wrapperRegexWithAnIdPathMissingEverywhereLeavesTheIdEmpty() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' \
                Business Entity 'relatedPartyRef.referredType'",
                  "details": "_items[0].relatedPartyRef[1].referredType: is missing but it is required",
                  "source": {"_sourceId-invalid": "", "path": "relatedPartyRef[1].referredType"}
                }, {
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' Business Entity '_items.state'",
                  "details": "_items[0].state: is missing but it is required",
                  "source": {"_sourceId-invalid": "", "path": "state"}
                }]""",
                errorsOf(JsonTestData.CUSTOMER_PRODUCT_SCHEMA, JsonTestData.ALL_CUSTOMER_PRODUCT_REQUEST,
                        "_sourceId-invalid", ITEMS_WRAPPER_REGEX));
    }

    @Test
    public void wrapperRegexWithTheItemIdMissingFallsBackToTheRootId() {
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' Business Entity '_items._sourceId'",
                  "details": "_items[0]._sourceId: is missing but it is required",
                  "source": {"_sourceId": "CUST#00000002", "path": "_sourceId"}
                }, {
                  "code": "ME-JV-0002",
                  "title": "Validation error 'required' in 'AllCustomerProducts' Business Entity '_items.state'",
                  "details": "_items[0].state: is missing but it is required",
                  "source": {"_sourceId": "CUST#00000002", "path": "state"}
                }]""",
                errorsOf(JsonTestData.CUSTOMER_PRODUCT_SCHEMA, JsonTestData.MEDIUM_ACP_REQUEST_LOSTED_INNER_SOURCE_ID,
                        ID_PATH, ITEMS_WRAPPER_REGEX));
    }
}
