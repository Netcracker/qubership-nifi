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

/**
 * JSON documents and schemas shared by the {@link JsonValidationHandler} and ValidateJson tests.
 */
public final class JsonTestData {
    /**
     * Customer that matches {@link #TEST_JSON_SCHEMA_1}.
     */
    public static final String VALID_TEST_JSON_BY_SHEMA1 =
            """
            {
                        "_sourceId": "CUST#223456789",
                        "_businessEntityType": "Customer",
                        "_sourceTimeStamp": "2019-01-31T21:31:50.795Z",
                        "id": "",
                        "name": "George Smith",
                        "externalId": "CUST#223456789",
                        "customerCategoryId": "Residential",
                        "customerNumber": "223456789",
                        "engagedPartyId": "ORG#123456789000",
                        "engagedPartyRefType": "Organization",
                        "extendedAttributes": {}
                    }""";

    /**
     * Customer that fails {@link #TEST_JSON_SCHEMA_1}: {@code customerNumber} is missing, and
     * {@code customerCategoryId} and {@code engagedPartyRefType} are numbers.
     */
    public static final String INVALID_TEST_JSON_BY_SHEMA1 =
            """
            {
                        "_sourceId": "CUST#123456789",
                        "_businessEntityType": "Customer",
                        "_sourceTimeStamp": "2019-01-31T21:30:50.795Z",
                        "id": "",
                        "name": "John Smith",
                        "externalId": "CUST#123456789",
                        "customerCategoryId": 123,
                        "engagedPartyId": "ORG#123456789000",
                        "engagedPartyRefType": 123,
                        "extendedAttributes": {}
            }""";

    /**
     * Customer with a nested {@code inner} object that matches {@link #TEST_JSON_SCHEMA_2}.
     */
    public static final String VALID_TEST_JSON_BY_SHEMA2 =
            """
            {
              "_sourceId": "CUST#123456789",
              "_businessEntityType": "Customer",
              "_sourceTimeStamp": "2019-01-31T21:30:50.795Z",
              "id": "",
              "name": "John Smith",
              "externalId": "CUST#123456789",
              "customerCategoryId": "Residential",
              "customerNumber": "123456789",
              "engagedPartyId": "ORG#123456789000",
              "engagedPartyRefType": "Organization",
              "extendedAttributes": {},
              "inner": {
                "key1": "value1",
                "key2": "value2",
                "key3": "value3",
                "key31": {
                  "key4": "value4",
                  "key5": [
                    {
                      "key6": "value6"
                    },
                    {
                      "key7": "value7"
                    }
                  ]
                }
              }
            }""";

    /**
     * Customer that fails {@link #TEST_JSON_SCHEMA_2}: {@code inner.key31.key5[0].key6} is a number.
     */
    public static final String INVALID_TEST_JSON_BY_SHEMA2 =
            """
            {
                        "_sourceId": "CUST#123456789",
                        "_businessEntityType": "Customer",
                        "_sourceTimeStamp": "2019-01-31T21:30:50.795Z",
                        "id": "",
                        "name": "John Smith",
                        "externalId": "CUST#123456789",
                        "customerCategoryId": "Residential",
                        "customerNumber": "123456789",
                        "engagedPartyId": "ORG#123456789000",
                        "engagedPartyRefType": "Organization",
                        "extendedAttributes": {},
                        "inner": {
                          "key1" : "value1",
                          "key2" : "value2",
                          "key3" : "value3",
                          "key31" : {
                          "key4" : "value",
                          "key5" : [
                          {
                           "key6" : 6
                              },
                              {
                                "key7" : "value7"
                              }
                            ]
                           }
                        }
            }""";

    /**
     * Customer that fails {@link #TEST_JSON_SCHEMA_3}: {@code inner.key31.key5[1]} has no {@code key6}.
     */
    public static final String INVALID_TEST_JSON_BY_SHEMA3 =
            """
            {
                        "_sourceId": "CUST#123456789",
                        "_businessEntityType": "Customer",
                        "_sourceTimeStamp": "2019-01-31T21:30:50.795Z",
                        "id": "",
                        "name": "John Smith",
                        "externalId": "CUST#123456789",
                        "customerCategoryId": "Residential",
                        "customerNumber": "123456789",
                        "engagedPartyId": "ORG#123456789000",
                        "engagedPartyRefType": "Organization",
                        "extendedAttributes": {},
                        "inner": {
                          "key1" : "value1",
                          "key2" : "value2",
                          "key3" : "value3",
                          "key31" : {
                          "key4" : "value4",
                          "key5" : [
                          {
                           "key6" : "value6"
                              },
                              {
                                "key7" : "value7"
                              }
                            ]
                           }
                        }
            }""";

    /**
     * Entity that fails {@link #TEST_JSON_SCHEMA_4}: {@code _sourceTimeStamp} is on February 31.
     */
    public static final String INVALID_TEST1_JSON_BY_SHEMA4 =
            """
            {
               "_sourceId":"devapp995.qubership.org_SAMPLE_CUST#00000002",
               "_businessEntityType":"AllCustomerProducts",
               "_sourceTimeStamp":"2018-02-31T11:27:38.795Z"
            }""";

    /**
     * Entity that fails {@link #TEST_JSON_SCHEMA_4}: the hour of {@code _sourceTimeStamp} is 56.
     */
    public static final String INVALID_TEST2_JSON_BY_SHEMA4 =
            """
            {
               "_sourceId":"devapp995.qubership.org_SAMPLE_CUST#00000002",
               "_businessEntityType":"AllCustomerProducts",
               "_sourceTimeStamp":"2018-02-11T56:27:38.795Z"
            }""";

    /**
     * Entity that matches {@link #TEST_JSON_SCHEMA_4}.
     */
    public static final String VALID_TEST_JSON_BY_SHEMA4 =
            """
            {
               "_sourceId":"devapp995.qubership.org_SAMPLE_CUST#00000002",
               "_businessEntityType":"AllCustomerProducts",
               "_sourceTimeStamp":"2018-02-11T11:27:38.795Z"
            }""";

    /**
     * Draft 4 schema of a flat customer that requires every property.
     */
    public static final String TEST_JSON_SCHEMA_1 =
            """
            {
              "definitions": {},
              "$schema": "http://json-schema.org/draft-04/schema#",
              "$id": "http://example.com/root.json",
              "type": "object",
              "title": "The Root Schema",
              "required": [
                "_sourceId",
                "_businessEntityType",
                "_sourceTimeStamp",
                "id",
                "name",
                "externalId",
                "customerCategoryId",
                "customerNumber",
                "engagedPartyId",
                "engagedPartyRefType",
                "extendedAttributes"
              ],
              "properties": {
                "_sourceId": {
                  "$id": "#_sourceId",
                  "type": "string",
                  "title": "The _sourceid Schema",
                  "default": "",
                  "examples": [
                    "CUST#123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "_businessEntityType": {
                  "$id": "#_businessEntityType",
                  "type": "string",
                  "title": "The _businessentitytype Schema",
                  "default": "",
                  "examples": [
                    "Customer"
                  ],
                  "pattern": "^(.*)$"
                },
                "_sourceTimeStamp": {
                  "$id": "#_sourceTimeStamp",
                  "type": "string",
                  "title": "The _sourcetimestamp Schema",
                  "default": "",
                  "examples": [
                    "2019-01-31T21:30:50.795Z"
                  ],
                  "pattern": "^(.*)$"
                },
                "id": {
                  "$id": "#id",
                  "type": "string",
                  "title": "The Id Schema",
                  "default": "",
                  "examples": [
                    ""
                  ],
                  "pattern": "^(.*)$"
                },
                "name": {
                  "$id": "#name",
                  "type": "string",
                  "title": "The Name Schema",
                  "default": "",
                  "examples": [
                    "John Smith"
                  ],
                  "pattern": "^(.*)$"
                },
                "externalId": {
                  "$id": "#externalId",
                  "type": "string",
                  "title": "The Externalid Schema",
                  "default": "",
                  "examples": [
                    "CUST#123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "customerCategoryId": {
                  "$id": "#customerCategoryId",
                  "type": "string",
                  "title": "The Customercategoryid Schema",
                  "default": "",
                  "examples": [
                    "Residential"
                  ],
                  "pattern": "^(.*)$"
                },
                "customerNumber": {
                  "$id": "#customerNumber",
                  "type": "string",
                  "title": "The Customernumber Schema",
                  "default": "",
                  "examples": [
                    "123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "engagedPartyId": {
                  "$id": "#engagedPartyId",
                  "type": "string",
                  "title": "The Engagedpartyid Schema",
                  "default": "",
                  "examples": [
                    "ORG#123456789000"
                  ],
                  "pattern": "^(.*)$"
                },
                "engagedPartyRefType": {
                  "$id": "#engagedPartyRefType",
                  "type": "string",
                  "title": "The Engagedpartyreftype Schema",
                  "default": "",
                  "examples": [
                    "Organization"
                  ],
                  "pattern": "^(.*)$"
                },
                "extendedAttributes": {
                  "$id": "#extendedAttributes",
                  "type": "object",
                  "title": "The Extendedattributes Schema"
                }
              }
            }""";

    /**
     * Draft 4 schema of a customer with a nested {@code inner} object, where every {@code key5} item
     * requires {@code key6}.
     */
    public static final String TEST_JSON_SCHEMA_3 =
            """
            {
              "definitions": {},
              "$schema": "http://json-schema.org/draft-04/schema#",
              "$id": "http://example.com/root.json",
              "type": "object",
              "title": "The Root Schema",
              "required": [
                "_sourceId",
                "_businessEntityType",
                "_sourceTimeStamp",
                "id",
                "name",
                "externalId",
                "customerCategoryId",
                "customerNumber",
                "engagedPartyId",
                "engagedPartyRefType",
                "extendedAttributes",
                "inner"
              ],
              "properties": {
                "_sourceId": {
                  "$id": "#/properties/_sourceId",
                  "type": "string",
                  "title": "The _sourceid Schema",
                  "default": "",
                  "examples": [
                    "CUST#123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "_businessEntityType": {
                  "$id": "#/properties/_businessEntityType",
                  "type": "string",
                  "title": "The _businessentitytype Schema",
                  "default": "",
                  "examples": [
                    "Customer"
                  ],
                  "pattern": "^(.*)$"
                },
                "_sourceTimeStamp": {
                  "$id": "#/properties/_sourceTimeStamp",
                  "type": "string",
                  "title": "The _sourcetimestamp Schema",
                  "default": "",
                  "examples": [
                    "2019-01-31T21:30:50.795Z"
                  ],
                  "pattern": "^(.*)$"
                },
                "id": {
                  "$id": "#/properties/id",
                  "type": "string",
                  "title": "The Id Schema",
                  "default": "",
                  "examples": [
                    ""
                  ],
                  "pattern": "^(.*)$"
                },
                "name": {
                  "$id": "#/properties/name",
                  "type": "string",
                  "title": "The Name Schema",
                  "default": "",
                  "examples": [
                    "John Smith"
                  ],
                  "pattern": "^(.*)$"
                },
                "externalId": {
                  "$id": "#/properties/externalId",
                  "type": "string",
                  "title": "The Externalid Schema",
                  "default": "",
                  "examples": [
                    "CUST#123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "customerCategoryId": {
                  "$id": "#/properties/customerCategoryId",
                  "type": "string",
                  "title": "The Customercategoryid Schema",
                  "default": "",
                  "examples": [
                    "Residential"
                  ],
                  "pattern": "^(.*)$"
                },
                "customerNumber": {
                  "$id": "#/properties/customerNumber",
                  "type": "string",
                  "title": "The Customernumber Schema",
                  "default": "",
                  "examples": [
                    "123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "engagedPartyId": {
                  "$id": "#/properties/engagedPartyId",
                  "type": "string",
                  "title": "The Engagedpartyid Schema",
                  "default": "",
                  "examples": [
                    "ORG#123456789000"
                  ],
                  "pattern": "^(.*)$"
                },
                "engagedPartyRefType": {
                  "$id": "#/properties/engagedPartyRefType",
                  "type": "string",
                  "title": "The Engagedpartyreftype Schema",
                  "default": "",
                  "examples": [
                    "Organization"
                  ],
                  "pattern": "^(.*)$"
                },
                "extendedAttributes": {
                  "$id": "#/properties/extendedAttributes",
                  "type": "object",
                  "title": "The Extendedattributes Schema"
                },
                "inner": {
                  "$id": "#/properties/inner",
                  "type": "object",
                  "title": "The Inner Schema",
                  "required": [
                    "key1",
                    "key2",
                    "key3",
                    "key31"
                  ],
                  "properties": {
                    "key1": {
                      "$id": "#/properties/inner/properties/key1",
                      "type": "string",
                      "title": "The Key1 Schema",
                      "default": "",
                      "examples": [
                        "value1"
                      ],
                      "pattern": "^(.*)$"
                    },
                    "key2": {
                      "$id": "#/properties/inner/properties/key2",
                      "type": "string",
                      "title": "The Key2 Schema",
                      "default": "",
                      "examples": [
                        "value2"
                      ],
                      "pattern": "^(.*)$"
                    },
                    "key3": {
                      "$id": "#/properties/inner/properties/key3",
                      "type": "string",
                      "title": "The Key3 Schema",
                      "default": "",
                      "examples": [
                        "value3"
                      ],
                      "pattern": "^(.*)$"
                    },
                    "key31": {
                      "$id": "#/properties/inner/properties/key31",
                      "type": "object",
                      "title": "The Key31 Schema",
                      "required": [
                        "key4",
                        "key5"
                      ],
                      "properties": {
                        "key4": {
                          "$id": "#/properties/inner/properties/key31/properties/key4",
                          "type": "string",
                          "title": "The Key4 Schema",
                          "default": "",
                          "examples": [
                            "value4"
                          ],
                          "pattern": "^(.*)$"
                        },
                        "key5": {
                          "$id": "#/properties/inner/properties/key31/properties/key5",
                          "type": "array",
                          "title": "The Key5 Schema",
                          "items": {
                            "$id": "#/properties/inner/properties/key31/properties/key5/items",
                            "type": "object",
                            "title": "The Items Schema",
                            "required": [ "key6"
                                              ],
                            "properties": {
                              "key6": {
                                "$id": "#/properties/inner/properties/key31/properties/key5/items/properties/key6",
                                "type": "string",
                                "title": "The Key6 Schema",
                                "default": "",
                                "examples": [
                                  "value6"
                                ],
                                "pattern": "^(.*)$"
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }""";

    /**
     * {@link #TEST_JSON_SCHEMA_3} with {@code key6} optional in {@code key5} items.
     */
    public static final String TEST_JSON_SCHEMA_2 =
            """
            {
              "definitions": {},
              "$schema": "http://json-schema.org/draft-04/schema#",
              "$id": "http://example.com/root.json",
              "type": "object",
              "title": "The Root Schema",
              "required": [
                "_sourceId",
                "_businessEntityType",
                "_sourceTimeStamp",
                "id",
                "name",
                "externalId",
                "customerCategoryId",
                "customerNumber",
                "engagedPartyId",
                "engagedPartyRefType",
                "extendedAttributes",
                "inner"
              ],
              "properties": {
                "_sourceId": {
                  "$id": "#/properties/_sourceId",
                  "type": "string",
                  "title": "The _sourceid Schema",
                  "default": "",
                  "examples": [
                    "CUST#123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "_businessEntityType": {
                  "$id": "#/properties/_businessEntityType",
                  "type": "string",
                  "title": "The _businessentitytype Schema",
                  "default": "",
                  "examples": [
                    "Customer"
                  ],
                  "pattern": "^(.*)$"
                },
                "_sourceTimeStamp": {
                  "$id": "#/properties/_sourceTimeStamp",
                  "type": "string",
                  "title": "The _sourcetimestamp Schema",
                  "default": "",
                  "examples": [
                    "2019-01-31T21:30:50.795Z"
                  ],
                  "pattern": "^(.*)$"
                },
                "id": {
                  "$id": "#/properties/id",
                  "type": "string",
                  "title": "The Id Schema",
                  "default": "",
                  "examples": [
                    ""
                  ],
                  "pattern": "^(.*)$"
                },
                "name": {
                  "$id": "#/properties/name",
                  "type": "string",
                  "title": "The Name Schema",
                  "default": "",
                  "examples": [
                    "John Smith"
                  ],
                  "pattern": "^(.*)$"
                },
                "externalId": {
                  "$id": "#/properties/externalId",
                  "type": "string",
                  "title": "The Externalid Schema",
                  "default": "",
                  "examples": [
                    "CUST#123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "customerCategoryId": {
                  "$id": "#/properties/customerCategoryId",
                  "type": "string",
                  "title": "The Customercategoryid Schema",
                  "default": "",
                  "examples": [
                    "Residential"
                  ],
                  "pattern": "^(.*)$"
                },
                "customerNumber": {
                  "$id": "#/properties/customerNumber",
                  "type": "string",
                  "title": "The Customernumber Schema",
                  "default": "",
                  "examples": [
                    "123456789"
                  ],
                  "pattern": "^(.*)$"
                },
                "engagedPartyId": {
                  "$id": "#/properties/engagedPartyId",
                  "type": "string",
                  "title": "The Engagedpartyid Schema",
                  "default": "",
                  "examples": [
                    "ORG#123456789000"
                  ],
                  "pattern": "^(.*)$"
                },
                "engagedPartyRefType": {
                  "$id": "#/properties/engagedPartyRefType",
                  "type": "string",
                  "title": "The Engagedpartyreftype Schema",
                  "default": "",
                  "examples": [
                    "Organization"
                  ],
                  "pattern": "^(.*)$"
                },
                "extendedAttributes": {
                  "$id": "#/properties/extendedAttributes",
                  "type": "object",
                  "title": "The Extendedattributes Schema"
                },
                "inner": {
                  "$id": "#/properties/inner",
                  "type": "object",
                  "title": "The Inner Schema",
                  "required": [
                    "key1",
                    "key2",
                    "key3",
                    "key31"
                  ],
                  "properties": {
                    "key1": {
                      "$id": "#/properties/inner/properties/key1",
                      "type": "string",
                      "title": "The Key1 Schema",
                      "default": "",
                      "examples": [
                        "value1"
                      ],
                      "pattern": "^(.*)$"
                    },
                    "key2": {
                      "$id": "#/properties/inner/properties/key2",
                      "type": "string",
                      "title": "The Key2 Schema",
                      "default": "",
                      "examples": [
                        "value2"
                      ],
                      "pattern": "^(.*)$"
                    },
                    "key3": {
                      "$id": "#/properties/inner/properties/key3",
                      "type": "string",
                      "title": "The Key3 Schema",
                      "default": "",
                      "examples": [
                        "value3"
                      ],
                      "pattern": "^(.*)$"
                    },
                    "key31": {
                      "$id": "#/properties/inner/properties/key31",
                      "type": "object",
                      "title": "The Key31 Schema",
                      "required": [
                        "key4",
                        "key5"
                      ],
                      "properties": {
                        "key4": {
                          "$id": "#/properties/inner/properties/key31/properties/key4",
                          "type": "string",
                          "title": "The Key4 Schema",
                          "default": "",
                          "examples": [
                            "value4"
                          ],
                          "pattern": "^(.*)$"
                        },
                        "key5": {
                          "$id": "#/properties/inner/properties/key31/properties/key5",
                          "type": "array",
                          "title": "The Key5 Schema",
                          "items": {
                            "$id": "#/properties/inner/properties/key31/properties/key5/items",
                            "type": "object",
                            "title": "The Items Schema",
                            "required": [
                             \s
                            ],
                            "properties": {
                              "key6": {
                                "$id": "#/properties/inner/properties/key31/properties/key5/items/properties/key6",
                                "type": "string",
                                "title": "The Key6 Schema",
                                "default": "",
                                "examples": [
                                  "value6"
                                ],
                                "pattern": "^(.*)$"
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }""";

    /**
     * Schema of an entity with an enumerated {@code _businessEntityType} and a {@code date-time}
     * {@code _sourceTimeStamp}.
     */
    public static final String TEST_JSON_SCHEMA_4 =
            """
            {
               "type":"object",
               "properties":{
                  "_sourceId":{
                     "type":"string"
                  },
                  "_businessEntityType":{
                     "type":"string",
                      "enum":[
                                 "Customer",
                                 "Organization",
                                 "Individual",
                                 "PartyRole",
                                 "PartyInteraction",
                                 "ProductHierarchy",
                                 "AllCustomerProducts"
                              ]
                  },
                  "_sourceTimeStamp":{
                     "type": "string",
                     "format":"date-time"
                  }
               },
               "required":[
                  "_sourceId",
                  "_businessEntityType",
                  "_sourceTimeStamp"
               ],
               "title":"schema",
               "description":"Check Input Request"
            }""";

    /**
     * {@link #TEST_JSON_SCHEMA_4} with a comma missing in {@code required}, so the text is not valid JSON.
     */
    public static final String TEST_INVALID_JSON_SCHEMA_4 =
            """
            {
               "type":"object",
               "properties":{
                  "_sourceId":{
                     "type":"string"
                  },
                  "_businessEntityType":{
                     "type":"string",
                      "enum":[
                                 "Customer",
                                 "Organization",
                                 "Individual",
                                 "PartyRole",
                                 "PartyInteraction",
                                 "ProductHierarchy",
                                 "AllCustomerProducts"
                              ]
                  },
                  "_sourceTimeStamp":{
                     "type": "string",
                     "format":"date-time"
                  }
               },
               "required":[
                  "_sourceId"
                  "_businessEntityType",
                  "_sourceTimeStamp"
               ],
               "title":"schema",
               "description":"Check Input Request"
            }""";

    /**
     * Schema of an {@code AllCustomerProducts} request whose {@code _items} carry their own
     * {@code _sourceId} and {@code relatedPartyRef} entries.
     */
    public static final String CUSTOMER_PRODUCT_SCHEMA =
            """
            {
                "type": "object",
                "required": [
                    "_sourceId",
                    "_businessEntityType",
                    "_items"
                ],
                "properties": {
                    "_sourceId": {
                        "type": "string"
                    },
                    "_businessEntityType": {
                        "type": "string",
                        "enum": [
                            "ProductHierarchy",
                            "AllCustomerProducts"
                        ]
                    },
                    "_sourceTimeStamp": {
                        "type": "string",
                        "format": "date-time"
                    },
                    "_items": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "required": [
                                "_sourceId",
                                "_sourceCustomerId",
                                "customerName",
                                "type",
                                "name",
                                "state",
                                "marketId",
                                "distributionChannelId",
                                "customerCategoryId",
                                "offeringId",
                                "overrideMode",
                                "quantity"
                            ],
                            "properties": {
                                "_sourceId": {
                                    "type": "string"
                                },
                                "_sourceParentId": {
                                    "type": [
                                        "string",
                                        "null"
                                    ]
                                },
                                "relatedPartyRef": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "name": {
                                                "type": "string"
                                            },
                                            "role": {
                                                "type": "string"
                                            },
                                            "referredType": {
                                                "type": "string"
                                            },
                                            "refId": {
                                                "type": "string"
                                            },
                                            "_sourceRefId": {
                                                "type": "string"
                                            }
                                        },
                                        "required": [
                                            "referredType",
                                            "_sourceRefId"
                                        ]
                                    }
                                }
                            }
                        }
                    }
                }
            }""";

    /**
     * Request that fails {@link #CUSTOMER_PRODUCT_SCHEMA}: {@code _items[0]} has no {@code state}, and its
     * second {@code relatedPartyRef} has no {@code referredType}.
     */
    public static final String ALL_CUSTOMER_PRODUCT_REQUEST =
            """
            {
                "_sourceId": "CUST#00000002",
                "_businessEntityType": "AllCustomerProducts",
                "_items": [
                    {
                        "id": null,
                        "_sourceId": "PI#TLO_01_CUST#00000002",
                        "offeringId": "9154607896813991382",
                        "placeRefId": "PLACE#TLO_01_CUST#00000002",
                        "_sourceParentId": null,
                        "rootId": null,
                        "type": "PRODUCT",
                        "name": "TLO_01(CUST#00000002)",
                        "quoteId": null,
                        "description": "Some Good Product Instance",
                        "_sourceCustomerId": "CUST#00000002",
                        "customerName": "Customer 002",
                        "extendedEligibility": "{\\"elig1\\":\\"value1\\",\\"elig2\\":\\"value2\\"}",
                        "startDate": "2019-01-11T19:02:24.000Z",
                        "terminationDate": "2029-01-11T19:02:24.000Z",
                        "brandId": null,
                        "marketId": "9136000105613113827",
                        "customerCategoryId": "9136019180013129386",
                        "distributionChannelId": "9142905964913606451",
                        "businessGroupId": null,
                        "overrideMode": "NET",
                        "contractedDate": "2019-08-11T19:02:24.000Z",
                        "quantity": "1",
                        "externalId": null,
                        "numberOfInstallment": null,
                        "productOrderId": null,
                        "disconnectionReason": null,
                        "disconnectionReasonDescription": null,
                        "sourceQuoteItemId": null,
                        "placeRef": {
                            "_sourceRefId": "REF#33982226",
                            "id": null,
                            "_sourceId": "PLACE#TLO_01_CUST#00000002",
                            "refId": "REF#33982226",
                            "refType": "REF_TYPE_01",
                            "name": "PLACE TLO_01_CUST#00000002",
                            "role": "Some reference role"
                        },
                        "relatedPartyRef": [
                            {
                                "_sourceRefId": "IND#00000002",
                                "refId": "IND#00000002",
                                "referredType": "Individual",
                                "role": "UserOfService",
                                "name": "User Of Service TLO_01"
                            },
                            {
                                "_sourceRefId": "CUST#00000002",
                                "refId": "CUST#00000002",
                                "role": "Customer",
                                "name": "User Of Service TLO_01"
                            }
                        ],
                        "accountRef": [
                            {
                                "_sourceRefId": "ACC#001_CUST#00000002",
                                "refId": "ACC#001_CUST#00000002",
                                "refType": "PartyAccount",
                                "name": "ACCOUNT FOR TLO_01"
                            }
                        ],
                        "agreementRef": [
                            {
                                "_sourceRefId": "AGR#001_CUST#00000002",
                                "refId": "AGR#001_CUST#00000002",
                                "refType": "Agreement",
                                "name": "AGREEMENT FOR TLO_01"
                            }
                        ]
                    }
                ]
            }""";

    /**
     * Request that fails {@link #CUSTOMER_PRODUCT_SCHEMA}: {@code _items[0]} has neither
     * {@code _sourceId} nor {@code state}.
     */
    public static final String MEDIUM_ACP_REQUEST_LOSTED_INNER_SOURCE_ID =
            """
            {
                "_sourceId": "CUST#00000002",
                "_businessEntityType": "AllCustomerProducts",
                "_items": [
                    {
                        "id": null,
                        "offeringId": "9154607896813991382",
                        "placeRefId": "PLACE#TLO_01_CUST#00000002",
                        "_sourceParentId": null,
                        "rootId": null,
                        "type": "PRODUCT",
                        "name": "TLO_01(CUST#00000002)",
                        "quoteId": null,
                        "description": "Some Good Product Instance",
                        "_sourceCustomerId": "CUST#00000002",
                        "customerName": "Customer 002",
                        "extendedEligibility": "{\\"elig1\\":\\"value1\\",\\"elig2\\":\\"value2\\"}",
                        "startDate": "2019-01-11T19:02:24.000Z",
                        "terminationDate": "2029-01-11T19:02:24.000Z",
                        "brandId": null,
                        "marketId": "9136000105613113827",
                        "customerCategoryId": "9136019180013129386",
                        "distributionChannelId": "9142905964913606451",
                        "businessGroupId": null,
                        "overrideMode": "NET",
                        "contractedDate": "2019-08-11T19:02:24.000Z",
                        "quantity": "1",
                        "externalId": null,
                        "numberOfInstallment": null,
                        "productOrderId": null,
                        "disconnectionReason": null,
                        "disconnectionReasonDescription": null,
                        "sourceQuoteItemId": null,
                        "placeRef": {
                            "_sourceRefId": "REF#33982226",
                            "id": null,
                            "_sourceId": "PLACE#TLO_01_CUST#00000002",
                            "refId": "REF#33982226",
                            "refType": "REF_TYPE_01",
                            "name": "PLACE TLO_01_CUST#00000002",
                            "role": "Some reference role"
                        },
                        "relatedPartyRef": [
                            {
                                "_sourceRefId": "IND#00000002",
                                "refId": "IND#00000002",
                                "referredType": "Individual",
                                "role": "UserOfService",
                                "name": "User Of Service TLO_01"
                            },
                            {
                                "_sourceRefId": "CUST#00000002",
                                "refId": "CUST#00000002",
                                "referredType": "Customer",
                                "role": "Customer",
                                "name": "User Of Service TLO_01"
                            }
                        ],
                        "accountRef": [
                            {
                                "_sourceRefId": "ACC#001_CUST#00000002",
                                "refId": "ACC#001_CUST#00000002",
                                "refType": "PartyAccount",
                                "name": "ACCOUNT FOR TLO_01"
                            }
                        ],
                        "agreementRef": [
                            {
                                "_sourceRefId": "AGR#001_CUST#00000002",
                                "refId": "AGR#001_CUST#00000002",
                                "refType": "Agreement",
                                "name": "AGREEMENT FOR TLO_01"
                            }
                        ]
                    }
                ]
            }""";

    private JsonTestData() {
    }
}
