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

public class JsonTestData {
    public static final String VALID_TEST_JSON_BY_SHEMA1 = "{\n" +
            "            \"_sourceId\": \"CUST#223456789\",\n" +
            "            \"_businessEntityType\": \"Customer\",\n" +
            "            \"_sourceTimeStamp\": \"2019-01-31T21:31:50.795Z\",\n" +
            "            \"id\": \"\",\n" +
            "            \"name\": \"George Smith\",\n" +
            "            \"externalId\": \"CUST#223456789\",\n" +
            "            \"customerCategoryId\": \"Residential\",\n" +
            "            \"customerNumber\": \"223456789\",\n" +
            "            \"engagedPartyId\": \"ORG#123456789000\",\n" +
            "            \"engagedPartyRefType\": \"Organization\",\n" +
            "            \"extendedAttributes\": {}\n" +
            "        }";
    public static final String INVALID_TEST_JSON_BY_SHEMA1 = "{\n" +
            "            \"_sourceId\": \"CUST#123456789\",\n" +
            "            \"_businessEntityType\": \"Customer\",\n" +
            "            \"_sourceTimeStamp\": \"2019-01-31T21:30:50.795Z\",\n" +
            "            \"id\": \"\",\n" +
            "            \"name\": \"John Smith\",\n" +
            "            \"externalId\": \"CUST#123456789\",\n" +
            "            \"customerCategoryId\": 123,\n" +
            "            \"engagedPartyId\": \"ORG#123456789000\",\n" +
            "            \"engagedPartyRefType\": 123,\n" +
            "            \"extendedAttributes\": {}\n" +
            "}";
    public static final String VALID_TEST_JSON_BY_SHEMA2 = "{\n" +
            "  \"_sourceId\": \"CUST#123456789\",\n" +
            "  \"_businessEntityType\": \"Customer\",\n" +
            "  \"_sourceTimeStamp\": \"2019-01-31T21:30:50.795Z\",\n" +
            "  \"id\": \"\",\n" +
            "  \"name\": \"John Smith\",\n" +
            "  \"externalId\": \"CUST#123456789\",\n" +
            "  \"customerCategoryId\": \"Residential\",\n" +
            "  \"customerNumber\": \"123456789\",\n" +
            "  \"engagedPartyId\": \"ORG#123456789000\",\n" +
            "  \"engagedPartyRefType\": \"Organization\",\n" +
            "  \"extendedAttributes\": {},\n" +
            "  \"inner\": {\n" +
            "    \"key1\": \"value1\",\n" +
            "    \"key2\": \"value2\",\n" +
            "    \"key3\": \"value3\",\n" +
            "    \"key31\": {\n" +
            "      \"key4\": \"value4\",\n" +
            "      \"key5\": [\n" +
            "        {\n" +
            "          \"key6\": \"value6\"\n" +
            "        },\n" +
            "        {\n" +
            "          \"key7\": \"value7\"\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "  }\n" +
            "}";
    public static final String INVALID_TEST_JSON_BY_SHEMA2 = "{\n" +
            "            \"_sourceId\": \"CUST#123456789\",\n" +
            "            \"_businessEntityType\": \"Customer\",\n" +
            "            \"_sourceTimeStamp\": \"2019-01-31T21:30:50.795Z\",\n" +
            "            \"id\": \"\",\n" +
            "            \"name\": \"John Smith\",\n" +
            "            \"externalId\": \"CUST#123456789\",\n" +
            "            \"customerCategoryId\": \"Residential\",\n" +
            "            \"customerNumber\": \"123456789\",\n" +
            "            \"engagedPartyId\": \"ORG#123456789000\",\n" +
            "            \"engagedPartyRefType\": \"Organization\",\n" +
            "            \"extendedAttributes\": {},\n" +
            "            \"inner\": {\n" +
            "              \"key1\" : \"value1\",\n" +
            "              \"key2\" : \"value2\",\n" +
            "              \"key3\" : \"value3\",\n" +
            "              \"key31\" : {\n" +
            "              \"key4\" : \"value\",\n" +
            "              \"key5\" : [\n" +
            "              {\n" +
            "               \"key6\" : 6\n" +
            "                  },\n" +
            "\t\t\t\t  {\n" +
            "\t\t\t\t    \"key7\" : \"value7\"\n" +
            "                  }\n" +
            "                ]\n" +
            "               }\n" +
            "            }\n" +
            "}";
    public static final String INVALID_TEST_JSON_BY_SHEMA3 = "{\n" +
            "            \"_sourceId\": \"CUST#123456789\",\n" +
            "            \"_businessEntityType\": \"Customer\",\n" +
            "            \"_sourceTimeStamp\": \"2019-01-31T21:30:50.795Z\",\n" +
            "            \"id\": \"\",\n" +
            "            \"name\": \"John Smith\",\n" +
            "            \"externalId\": \"CUST#123456789\",\n" +
            "            \"customerCategoryId\": \"Residential\",\n" +
            "            \"customerNumber\": \"123456789\",\n" +
            "            \"engagedPartyId\": \"ORG#123456789000\",\n" +
            "            \"engagedPartyRefType\": \"Organization\",\n" +
            "            \"extendedAttributes\": {},\n" +
            "            \"inner\": {\n" +
            "              \"key1\" : \"value1\",\n" +
            "              \"key2\" : \"value2\",\n" +
            "              \"key3\" : \"value3\",\n" +
            "              \"key31\" : {\n" +
            "              \"key4\" : \"value4\",\n" +
            "              \"key5\" : [\n" +
            "              {\n" +
            "               \"key6\" : \"value6\"\n" +
            "                  },\n" +
            "\t\t\t\t  {\n" +
            "\t\t\t\t    \"key7\" : \"value7\"\n" +
            "                  }\n" +
            "                ]\n" +
            "               }\n" +
            "            }\n" +
            "}";
    public static final String INVALID_TEST1_JSON_BY_SHEMA4 = "{\n" +
            "   \"_sourceId\":\"devapp995.qubership.org_SAMPLE_CUST#00000002\",\n" +
            "   \"_businessEntityType\":\"AllCustomerProducts\",\n" +
            "   \"_sourceTimeStamp\":\"2018-02-31T11:27:38.795Z\"\n" +
            "}";
    public static final String INVALID_TEST2_JSON_BY_SHEMA4 = "{\n" +
            "   \"_sourceId\":\"devapp995.qubership.org_SAMPLE_CUST#00000002\",\n" +
            "   \"_businessEntityType\":\"AllCustomerProducts\",\n" +
            "   \"_sourceTimeStamp\":\"2018-02-11T56:27:38.795Z\"\n" +
            "}";
    public static final String VALID_TEST_JSON_BY_SHEMA4 = "{\n" +
            "   \"_sourceId\":\"devapp995.qubership.org_SAMPLE_CUST#00000002\",\n" +
            "   \"_businessEntityType\":\"AllCustomerProducts\",\n" +
            "   \"_sourceTimeStamp\":\"2018-02-11T11:27:38.795Z\"\n" +
            "}";
    public static final String TEST_JSON_SCHEMA_1 = "{\n" +
            "  \"definitions\": {},\n" +
            "  \"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" +
            "  \"$id\": \"http://example.com/root.json\",\n" +
            "  \"type\": \"object\",\n" +
            "  \"title\": \"The Root Schema\",\n" +
            "  \"required\": [\n" +
            "    \"_sourceId\",\n" +
            "    \"_businessEntityType\",\n" +
            "    \"_sourceTimeStamp\",\n" +
            "    \"id\",\n" +
            "    \"name\",\n" +
            "    \"externalId\",\n" +
            "    \"customerCategoryId\",\n" +
            "    \"customerNumber\",\n" +
            "    \"engagedPartyId\",\n" +
            "    \"engagedPartyRefType\",\n" +
            "    \"extendedAttributes\"\n" +
            "  ],\n" +
            "  \"properties\": {\n" +
            "    \"_sourceId\": {\n" +
            "      \"$id\": \"#_sourceId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _sourceid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"CUST#123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"_businessEntityType\": {\n" +
            "      \"$id\": \"#_businessEntityType\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _businessentitytype Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Customer\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"_sourceTimeStamp\": {\n" +
            "      \"$id\": \"#_sourceTimeStamp\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _sourcetimestamp Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"2019-01-31T21:30:50.795Z\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"id\": {\n" +
            "      \"$id\": \"#id\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Id Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"name\": {\n" +
            "      \"$id\": \"#name\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Name Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"John Smith\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"externalId\": {\n" +
            "      \"$id\": \"#externalId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Externalid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"CUST#123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"customerCategoryId\": {\n" +
            "      \"$id\": \"#customerCategoryId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Customercategoryid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Residential\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"customerNumber\": {\n" +
            "      \"$id\": \"#customerNumber\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Customernumber Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"engagedPartyId\": {\n" +
            "      \"$id\": \"#engagedPartyId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Engagedpartyid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"ORG#123456789000\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"engagedPartyRefType\": {\n" +
            "      \"$id\": \"#engagedPartyRefType\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Engagedpartyreftype Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Organization\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"extendedAttributes\": {\n" +
            "      \"$id\": \"#extendedAttributes\",\n" +
            "      \"type\": \"object\",\n" +
            "      \"title\": \"The Extendedattributes Schema\"\n" +
            "    }\n" +
            "  }\n" +
            "}";
    public static final String TEST_JSON_SCHEMA_3 = "{\n" +
            "  \"definitions\": {},\n" +
            "  \"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" +
            "  \"$id\": \"http://example.com/root.json\",\n" +
            "  \"type\": \"object\",\n" +
            "  \"title\": \"The Root Schema\",\n" +
            "  \"required\": [\n" +
            "    \"_sourceId\",\n" +
            "    \"_businessEntityType\",\n" +
            "    \"_sourceTimeStamp\",\n" +
            "    \"id\",\n" +
            "    \"name\",\n" +
            "    \"externalId\",\n" +
            "    \"customerCategoryId\",\n" +
            "    \"customerNumber\",\n" +
            "    \"engagedPartyId\",\n" +
            "    \"engagedPartyRefType\",\n" +
            "    \"extendedAttributes\",\n" +
            "    \"inner\"\n" +
            "  ],\n" +
            "  \"properties\": {\n" +
            "    \"_sourceId\": {\n" +
            "      \"$id\": \"#/properties/_sourceId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _sourceid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"CUST#123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"_businessEntityType\": {\n" +
            "      \"$id\": \"#/properties/_businessEntityType\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _businessentitytype Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Customer\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"_sourceTimeStamp\": {\n" +
            "      \"$id\": \"#/properties/_sourceTimeStamp\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _sourcetimestamp Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"2019-01-31T21:30:50.795Z\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"id\": {\n" +
            "      \"$id\": \"#/properties/id\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Id Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"name\": {\n" +
            "      \"$id\": \"#/properties/name\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Name Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"John Smith\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"externalId\": {\n" +
            "      \"$id\": \"#/properties/externalId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Externalid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"CUST#123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"customerCategoryId\": {\n" +
            "      \"$id\": \"#/properties/customerCategoryId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Customercategoryid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Residential\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"customerNumber\": {\n" +
            "      \"$id\": \"#/properties/customerNumber\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Customernumber Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"engagedPartyId\": {\n" +
            "      \"$id\": \"#/properties/engagedPartyId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Engagedpartyid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"ORG#123456789000\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"engagedPartyRefType\": {\n" +
            "      \"$id\": \"#/properties/engagedPartyRefType\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Engagedpartyreftype Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Organization\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"extendedAttributes\": {\n" +
            "      \"$id\": \"#/properties/extendedAttributes\",\n" +
            "      \"type\": \"object\",\n" +
            "      \"title\": \"The Extendedattributes Schema\"\n" +
            "    },\n" +
            "    \"inner\": {\n" +
            "      \"$id\": \"#/properties/inner\",\n" +
            "      \"type\": \"object\",\n" +
            "      \"title\": \"The Inner Schema\",\n" +
            "      \"required\": [\n" +
            "        \"key1\",\n" +
            "        \"key2\",\n" +
            "        \"key3\",\n" +
            "        \"key31\"\n" +
            "      ],\n" +
            "      \"properties\": {\n" +
            "        \"key1\": {\n" +
            "          \"$id\": \"#/properties/inner/properties/key1\",\n" +
            "          \"type\": \"string\",\n" +
            "          \"title\": \"The Key1 Schema\",\n" +
            "          \"default\": \"\",\n" +
            "          \"examples\": [\n" +
            "            \"value1\"\n" +
            "          ],\n" +
            "          \"pattern\": \"^(.*)$\"\n" +
            "        },\n" +
            "        \"key2\": {\n" +
            "          \"$id\": \"#/properties/inner/properties/key2\",\n" +
            "          \"type\": \"string\",\n" +
            "          \"title\": \"The Key2 Schema\",\n" +
            "          \"default\": \"\",\n" +
            "          \"examples\": [\n" +
            "            \"value2\"\n" +
            "          ],\n" +
            "          \"pattern\": \"^(.*)$\"\n" +
            "        },\n" +
            "        \"key3\": {\n" +
            "          \"$id\": \"#/properties/inner/properties/key3\",\n" +
            "          \"type\": \"string\",\n" +
            "          \"title\": \"The Key3 Schema\",\n" +
            "          \"default\": \"\",\n" +
            "          \"examples\": [\n" +
            "            \"value3\"\n" +
            "          ],\n" +
            "          \"pattern\": \"^(.*)$\"\n" +
            "        },\n" +
            "        \"key31\": {\n" +
            "          \"$id\": \"#/properties/inner/properties/key31\",\n" +
            "          \"type\": \"object\",\n" +
            "          \"title\": \"The Key31 Schema\",\n" +
            "          \"required\": [\n" +
            "            \"key4\",\n" +
            "            \"key5\"\n" +
            "          ],\n" +
            "          \"properties\": {\n" +
            "            \"key4\": {\n" +
            "              \"$id\": \"#/properties/inner/properties/key31/properties/key4\",\n" +
            "              \"type\": \"string\",\n" +
            "              \"title\": \"The Key4 Schema\",\n" +
            "              \"default\": \"\",\n" +
            "              \"examples\": [\n" +
            "                \"value4\"\n" +
            "              ],\n" +
            "              \"pattern\": \"^(.*)$\"\n" +
            "            },\n" +
            "            \"key5\": {\n" +
            "              \"$id\": \"#/properties/inner/properties/key31/properties/key5\",\n" +
            "              \"type\": \"array\",\n" +
            "              \"title\": \"The Key5 Schema\",\n" +
            "              \"items\": {\n" +
            "                \"$id\": \"#/properties/inner/properties/key31/properties/key5/items\",\n" +
            "                \"type\": \"object\",\n" +
            "                \"title\": \"The Items Schema\",\n" +
            "                \"required\": [ \"key6\"\n" +
            "                                  ],\n" +
            "                \"properties\": {\n" +
            "                  \"key6\": {\n" +
            "                    \"$id\": \"#/properties/inner/properties/key31/properties/key5/items/properties/key6\",\n" +
            "                    \"type\": \"string\",\n" +
            "                    \"title\": \"The Key6 Schema\",\n" +
            "                    \"default\": \"\",\n" +
            "                    \"examples\": [\n" +
            "                      \"value6\"\n" +
            "                    ],\n" +
            "                    \"pattern\": \"^(.*)$\"\n" +
            "                  }\n" +
            "                }\n" +
            "              }\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
    public static final String TEST_JSON_SCHEMA_2 = "{\n" +
            "  \"definitions\": {},\n" +
            "  \"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" +
            "  \"$id\": \"http://example.com/root.json\",\n" +
            "  \"type\": \"object\",\n" +
            "  \"title\": \"The Root Schema\",\n" +
            "  \"required\": [\n" +
            "    \"_sourceId\",\n" +
            "    \"_businessEntityType\",\n" +
            "    \"_sourceTimeStamp\",\n" +
            "    \"id\",\n" +
            "    \"name\",\n" +
            "    \"externalId\",\n" +
            "    \"customerCategoryId\",\n" +
            "    \"customerNumber\",\n" +
            "    \"engagedPartyId\",\n" +
            "    \"engagedPartyRefType\",\n" +
            "    \"extendedAttributes\",\n" +
            "    \"inner\"\n" +
            "  ],\n" +
            "  \"properties\": {\n" +
            "    \"_sourceId\": {\n" +
            "      \"$id\": \"#/properties/_sourceId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _sourceid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"CUST#123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"_businessEntityType\": {\n" +
            "      \"$id\": \"#/properties/_businessEntityType\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _businessentitytype Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Customer\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"_sourceTimeStamp\": {\n" +
            "      \"$id\": \"#/properties/_sourceTimeStamp\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The _sourcetimestamp Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"2019-01-31T21:30:50.795Z\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"id\": {\n" +
            "      \"$id\": \"#/properties/id\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Id Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"name\": {\n" +
            "      \"$id\": \"#/properties/name\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Name Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"John Smith\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"externalId\": {\n" +
            "      \"$id\": \"#/properties/externalId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Externalid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"CUST#123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"customerCategoryId\": {\n" +
            "      \"$id\": \"#/properties/customerCategoryId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Customercategoryid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Residential\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"customerNumber\": {\n" +
            "      \"$id\": \"#/properties/customerNumber\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Customernumber Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"123456789\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"engagedPartyId\": {\n" +
            "      \"$id\": \"#/properties/engagedPartyId\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Engagedpartyid Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"ORG#123456789000\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"engagedPartyRefType\": {\n" +
            "      \"$id\": \"#/properties/engagedPartyRefType\",\n" +
            "      \"type\": \"string\",\n" +
            "      \"title\": \"The Engagedpartyreftype Schema\",\n" +
            "      \"default\": \"\",\n" +
            "      \"examples\": [\n" +
            "        \"Organization\"\n" +
            "      ],\n" +
            "      \"pattern\": \"^(.*)$\"\n" +
            "    },\n" +
            "    \"extendedAttributes\": {\n" +
            "      \"$id\": \"#/properties/extendedAttributes\",\n" +
            "      \"type\": \"object\",\n" +
            "      \"title\": \"The Extendedattributes Schema\"\n" +
            "    },\n" +
            "    \"inner\": {\n" +
            "      \"$id\": \"#/properties/inner\",\n" +
            "      \"type\": \"object\",\n" +
            "      \"title\": \"The Inner Schema\",\n" +
            "      \"required\": [\n" +
            "        \"key1\",\n" +
            "        \"key2\",\n" +
            "        \"key3\",\n" +
            "        \"key31\"\n" +
            "      ],\n" +
            "      \"properties\": {\n" +
            "        \"key1\": {\n" +
            "          \"$id\": \"#/properties/inner/properties/key1\",\n" +
            "          \"type\": \"string\",\n" +
            "          \"title\": \"The Key1 Schema\",\n" +
            "          \"default\": \"\",\n" +
            "          \"examples\": [\n" +
            "            \"value1\"\n" +
            "          ],\n" +
            "          \"pattern\": \"^(.*)$\"\n" +
            "        },\n" +
            "        \"key2\": {\n" +
            "          \"$id\": \"#/properties/inner/properties/key2\",\n" +
            "          \"type\": \"string\",\n" +
            "          \"title\": \"The Key2 Schema\",\n" +
            "          \"default\": \"\",\n" +
            "          \"examples\": [\n" +
            "            \"value2\"\n" +
            "          ],\n" +
            "          \"pattern\": \"^(.*)$\"\n" +
            "        },\n" +
            "        \"key3\": {\n" +
            "          \"$id\": \"#/properties/inner/properties/key3\",\n" +
            "          \"type\": \"string\",\n" +
            "          \"title\": \"The Key3 Schema\",\n" +
            "          \"default\": \"\",\n" +
            "          \"examples\": [\n" +
            "            \"value3\"\n" +
            "          ],\n" +
            "          \"pattern\": \"^(.*)$\"\n" +
            "        },\n" +
            "        \"key31\": {\n" +
            "          \"$id\": \"#/properties/inner/properties/key31\",\n" +
            "          \"type\": \"object\",\n" +
            "          \"title\": \"The Key31 Schema\",\n" +
            "          \"required\": [\n" +
            "            \"key4\",\n" +
            "            \"key5\"\n" +
            "          ],\n" +
            "          \"properties\": {\n" +
            "            \"key4\": {\n" +
            "              \"$id\": \"#/properties/inner/properties/key31/properties/key4\",\n" +
            "              \"type\": \"string\",\n" +
            "              \"title\": \"The Key4 Schema\",\n" +
            "              \"default\": \"\",\n" +
            "              \"examples\": [\n" +
            "                \"value4\"\n" +
            "              ],\n" +
            "              \"pattern\": \"^(.*)$\"\n" +
            "            },\n" +
            "            \"key5\": {\n" +
            "              \"$id\": \"#/properties/inner/properties/key31/properties/key5\",\n" +
            "              \"type\": \"array\",\n" +
            "              \"title\": \"The Key5 Schema\",\n" +
            "              \"items\": {\n" +
            "                \"$id\": \"#/properties/inner/properties/key31/properties/key5/items\",\n" +
            "                \"type\": \"object\",\n" +
            "                \"title\": \"The Items Schema\",\n" +
            "                \"required\": [\n" +
            "                  \n" +
            "                ],\n" +
            "                \"properties\": {\n" +
            "                  \"key6\": {\n" +
            "                    \"$id\": \"#/properties/inner/properties/key31/properties/key5/items/properties/key6\",\n" +
            "                    \"type\": \"string\",\n" +
            "                    \"title\": \"The Key6 Schema\",\n" +
            "                    \"default\": \"\",\n" +
            "                    \"examples\": [\n" +
            "                      \"value6\"\n" +
            "                    ],\n" +
            "                    \"pattern\": \"^(.*)$\"\n" +
            "                  }\n" +
            "                }\n" +
            "              }\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
    public static final String TEST_JSON_SCHEMA_4 ="{\n" +
            "   \"type\":\"object\",\n" +
            "   \"properties\":{\n" +
            "      \"_sourceId\":{\n" +
            "         \"type\":\"string\"\n" +
            "      },\n" +
            "      \"_businessEntityType\":{\n" +
            "         \"type\":\"string\",\n" +
            "          \"enum\":[\n" +
            "                     \"Customer\",\n" +
            "                     \"Organization\",\n" +
            "                     \"Individual\",\n" +
            "                     \"PartyRole\",\n" +
            "                     \"PartyInteraction\",\n" +
            "                     \"ProductHierarchy\",\n" +
            "                     \"AllCustomerProducts\"\n" +
            "                  ]\n" +
            "      },\n" +
            "      \"_sourceTimeStamp\":{\n" +
            "         \"type\": \"string\",\n" +
            "         \"format\":\"date-time\"\n" +
            "      }\n" +
            "   },\n" +
            "   \"required\":[\n" +
            "      \"_sourceId\",\n" +
            "      \"_businessEntityType\",\n" +
            "      \"_sourceTimeStamp\"\n" +
            "   ],\n" +
            "   \"title\":\"schema\",\n" +
            "   \"description\":\"Check Input Request\"\n" +
            "}";


    public static final String TEST_INVALID_JSON_SCHEMA_4 ="{\n" +
            "   \"type\":\"object\",\n" +
            "   \"properties\":{\n" +
            "      \"_sourceId\":{\n" +
            "         \"type\":\"string\"\n" +
            "      },\n" +
            "      \"_businessEntityType\":{\n" +
            "         \"type\":\"string\",\n" +
            "          \"enum\":[\n" +
            "                     \"Customer\",\n" +
            "                     \"Organization\",\n" +
            "                     \"Individual\",\n" +
            "                     \"PartyRole\",\n" +
            "                     \"PartyInteraction\",\n" +
            "                     \"ProductHierarchy\",\n" +
            "                     \"AllCustomerProducts\"\n" +
            "                  ]\n" +
            "      },\n" +
            "      \"_sourceTimeStamp\":{\n" +
            "         \"type\": \"string\",\n" +
            "         \"format\":\"date-time\"\n" +
            "      }\n" +
            "   },\n" +
            "   \"required\":[\n" +
            "      \"_sourceId\"\n" +
          //  "      \"_sourceId\",\n" +
            "      \"_businessEntityType\",\n" +
            "      \"_sourceTimeStamp\"\n" +
            "   ],\n" +
            "   \"title\":\"schema\",\n" +
            "   \"description\":\"Check Input Request\"\n" +
            "}";




    public static String CUSTOMER_PRODUCT_SCHEMA = "{\n" +
            "\t\"type\": \"object\",\n" +
            "\t\"required\": [\n" +
            "\t\t\"_sourceId\",\n" +
            "\t\t\"_businessEntityType\",\n" +
            "\t\t\"_items\"\n" +
            "\t],\n" +
            "\t\"properties\": {\n" +
            "\t\t\"_sourceId\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t},\n" +
            "\t\t\"_businessEntityType\": {\n" +
            "\t\t\t\"type\": \"string\",\n" +
            "\t\t\t\"enum\": [\n" +
            "\t\t\t\t\"ProductHierarchy\",\n" +
            "\t\t\t\t\"AllCustomerProducts\"\n" +
            "\t\t\t]\n" +
            "\t\t},\n" +
            "\t\t\"_sourceTimeStamp\": {\n" +
            "\t\t\t\"type\": \"string\",\n" +
            "\t\t\t\"format\": \"date-time\"\n" +
            "\t\t},\n" +
            "\t\t\"_items\": {\n" +
            "\t\t\t\"type\": \"array\",\n" +
            "\t\t\t\"items\": {\n" +
            "\t\t\t\t\"type\": \"object\",\n" +
            "\t\t\t\t\"required\": [\n" +
            "\t\t\t\t\t\"_sourceId\",\n" +
            "\t\t\t\t\t\"_sourceCustomerId\",\n" +
            "\t\t\t\t\t\"customerName\",\n" +
            "\t\t\t\t\t\"type\",\n" +
            "\t\t\t\t\t\"name\",\n" +
            "\t\t\t\t\t\"state\",\n" +
            "\t\t\t\t\t\"marketId\",\n" +
            "\t\t\t\t\t\"distributionChannelId\",\n" +
            "\t\t\t\t\t\"customerCategoryId\",\n" +
            "\t\t\t\t\t\"offeringId\",\n" +
            "\t\t\t\t\t\"overrideMode\",\n" +
            "\t\t\t\t\t\"quantity\"\n" +
            "\t\t\t\t],\n" +
            "\t\t\t\t\"properties\": {\n" +
            "\t\t\t\t\t\"_sourceId\": {\n" +
            "\t\t\t\t\t\t\"type\": \"string\"\n" +
            "\t\t\t\t\t},\n" +
            "\t\t\t\t\t\"_sourceParentId\": {\n" +
            "\t\t\t\t\t\t\"type\": [\n" +
            "\t\t\t\t\t\t\t\"string\",\n" +
            "\t\t\t\t\t\t\t\"null\"\n" +
            "\t\t\t\t\t\t]\n" +
            "\t\t\t\t\t},\t\t\t\t\t\t\t\t\n" +
            "\t\t\t\t\t\"relatedPartyRef\": {\n" +
            "\t\t\t\t\t\t\"type\": \"array\",\n" +
            "\t\t\t\t\t\t\"items\": {\n" +
            "\t\t\t\t\t\t\t\"type\": \"object\",\n" +
            "\t\t\t\t\t\t\t\"properties\": {\n" +
            "\t\t\t\t\t\t\t\t\"name\": {\n" +
            "\t\t\t\t\t\t\t\t\t\"type\": \"string\"\n" +
            "\t\t\t\t\t\t\t\t},\n" +
            "\t\t\t\t\t\t\t\t\"role\": {\n" +
            "\t\t\t\t\t\t\t\t\t\"type\": \"string\"\n" +
            "\t\t\t\t\t\t\t\t},\n" +
            "\t\t\t\t\t\t\t\t\"referredType\": {\n" +
            "\t\t\t\t\t\t\t\t\t\"type\": \"string\"\n" +
            "\t\t\t\t\t\t\t\t},\n" +
            "\t\t\t\t\t\t\t\t\"refId\": {\n" +
            "\t\t\t\t\t\t\t\t\t\"type\": \"string\"\n" +
            "\t\t\t\t\t\t\t\t},\n" +
            "\t\t\t\t\t\t\t\t\"_sourceRefId\": {\n" +
            "\t\t\t\t\t\t\t\t\t\"type\": \"string\"\n" +
            "\t\t\t\t\t\t\t\t}\n" +
            "\t\t\t\t\t\t\t},\n" +
            "\t\t\t\t\t\t\t\"required\": [\n" +
            "\t\t\t\t\t\t\t\t\"referredType\",\n" +
            "\t\t\t\t\t\t\t\t\"_sourceRefId\"\n" +
            "\t\t\t\t\t\t\t]\n" +
            "\t\t\t\t\t\t}\n" +
            "\t\t\t\t\t}\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "}";

    public static String ALL_CUSTOMER_PRODUCT_REQUEST = "{\n" +
            "\t\"_sourceId\": \"CUST#00000002\",\n" +
            "\t\"_businessEntityType\": \"AllCustomerProducts\",\n" +
            "\t\"_items\": [\n" +
            "\t\t{\n" +
            "\t\t\t\"id\": null,\n" +
            "\t\t\t\"_sourceId\": \"PI#TLO_01_CUST#00000002\",\n" +
            "\t\t\t\"offeringId\": \"9154607896813991382\",\n" +
            "\t\t\t\"placeRefId\": \"PLACE#TLO_01_CUST#00000002\",\n" +
            "\t\t\t\"_sourceParentId\": null,\n" +
            "\t\t\t\"rootId\": null,\n" +
            "\t\t\t\"type\": \"PRODUCT\",\n" +
            "\t\t\t\"name\": \"TLO_01(CUST#00000002)\",\n" +
         //   "\t\t\t\"state\": \"Active\",\n" +
            "\t\t\t\"quoteId\": null,\n" +
            "\t\t\t\"description\": \"Some Good Product Instance\",\n" +
            "\t\t\t\"_sourceCustomerId\": \"CUST#00000002\",\n" +
            "\t\t\t\"customerName\": \"Customer 002\",\n" +
            "\t\t\t\"extendedEligibility\": \"{\\\"elig1\\\":\\\"value1\\\",\\\"elig2\\\":\\\"value2\\\"}\",\n" +
            "\t\t\t\"startDate\": \"2019-01-11T19:02:24.000Z\",\n" +
            "\t\t\t\"terminationDate\": \"2029-01-11T19:02:24.000Z\",\n" +
            "\t\t\t\"brandId\": null,\n" +
            "\t\t\t\"marketId\": \"9136000105613113827\",\n" +
            "\t\t\t\"customerCategoryId\": \"9136019180013129386\",\n" +
            "\t\t\t\"distributionChannelId\": \"9142905964913606451\",\n" +
            "\t\t\t\"businessGroupId\": null,\n" +
            "\t\t\t\"overrideMode\": \"NET\",\n" +
            "\t\t\t\"contractedDate\": \"2019-08-11T19:02:24.000Z\",\n" +
            "\t\t\t\"quantity\": \"1\",\n" +
            "\t\t\t\"externalId\": null,\n" +
            "\t\t\t\"numberOfInstallment\": null,\n" +
            "\t\t\t\"productOrderId\": null,\n" +
            "\t\t\t\"disconnectionReason\": null,\n" +
            "\t\t\t\"disconnectionReasonDescription\": null,\n" +
            "\t\t\t\"sourceQuoteItemId\": null,\n" +
            "\t\t\t\"placeRef\": {\n" +
            "\t\t\t\t\"_sourceRefId\": \"REF#33982226\",\n" +
            "\t\t\t\t\"id\": null,\n" +
            "\t\t\t\t\"_sourceId\": \"PLACE#TLO_01_CUST#00000002\",\n" +
            "\t\t\t\t\"refId\": \"REF#33982226\",\n" +
            "\t\t\t\t\"refType\": \"REF_TYPE_01\",\n" +
            "\t\t\t\t\"name\": \"PLACE TLO_01_CUST#00000002\",\n" +
            "\t\t\t\t\"role\": \"Some reference role\"\n" +
            "\t\t\t},\n" +
            "\t\t\t\"relatedPartyRef\": [\n" +
            "\t\t\t\t{\n" +
            "\t\t\t\t\t\"_sourceRefId\": \"IND#00000002\",\n" +
            "\t\t\t\t\t\"refId\": \"IND#00000002\",\n" +
            "\t\t\t\t\t\"referredType\": \"Individual\",\n" +
            "\t\t\t\t\t\"role\": \"UserOfService\",\n" +
            "\t\t\t\t\t\"name\": \"User Of Service TLO_01\"\n" +
            "\t\t\t\t},\n" +
            "\t\t\t\t{\n" +
            "\t\t\t\t\t\"_sourceRefId\": \"CUST#00000002\",\n" +
            "\t\t\t\t\t\"refId\": \"CUST#00000002\",\n" +
         //   "\t\t\t\t\t\"referredType\": \"Customer\",\n" +
            "\t\t\t\t\t\"role\": \"Customer\",\n" +
            "\t\t\t\t\t\"name\": \"User Of Service TLO_01\"\n" +
            "\t\t\t\t}\n" +
            "\t\t\t],\n" +
            "\t\t\t\"accountRef\": [\n" +
            "\t\t\t\t{\n" +
            "\t\t\t\t\t\"_sourceRefId\": \"ACC#001_CUST#00000002\",\n" +
            "\t\t\t\t\t\"refId\": \"ACC#001_CUST#00000002\",\n" +
            "\t\t\t\t\t\"refType\": \"PartyAccount\",\n" +
            "\t\t\t\t\t\"name\": \"ACCOUNT FOR TLO_01\"\n" +
            "\t\t\t\t}\n" +
            "\t\t\t],\n" +
            "\t\t\t\"agreementRef\": [\n" +
            "\t\t\t\t{\n" +
            "\t\t\t\t\t\"_sourceRefId\": \"AGR#001_CUST#00000002\",\n" +
            "\t\t\t\t\t\"refId\": \"AGR#001_CUST#00000002\",\n" +
            "\t\t\t\t\t\"refType\": \"Agreement\",\n" +
            "\t\t\t\t\t\"name\": \"AGREEMENT FOR TLO_01\"\n" +
            "\t\t\t\t}\n" +
            "\t\t\t]\n" +
            "\t\t}\n" +
            "\t]\n" +
            "}";

    public static String MEDIUM_ACP_REQUEST_LOSTED_INNER_SOURCE_ID = "{\n" +
            "\t\"_sourceId\": \"CUST#00000002\",\n" +
            "\t\"_businessEntityType\": \"AllCustomerProducts\",\n" +
            "\t\"_items\": [\n" +
            "\t\t{\n" +
            "\t\t\t\"id\": null,\n" +
         //   "\t\t\t\"_sourceId\": \"PI#TLO_01_CUST#00000002\",\n" +
            "\t\t\t\"offeringId\": \"9154607896813991382\",\n" +
            "\t\t\t\"placeRefId\": \"PLACE#TLO_01_CUST#00000002\",\n" +
            "\t\t\t\"_sourceParentId\": null,\n" +
            "\t\t\t\"rootId\": null,\n" +
            "\t\t\t\"type\": \"PRODUCT\",\n" +
            "\t\t\t\"name\": \"TLO_01(CUST#00000002)\",\n" +
            //"\t\t\t\"state\": \"Active\",\n" +
            "\t\t\t\"quoteId\": null,\n" +
            "\t\t\t\"description\": \"Some Good Product Instance\",\n" +
            "\t\t\t\"_sourceCustomerId\": \"CUST#00000002\",\n" +
            "\t\t\t\"customerName\": \"Customer 002\",\n" +
            "\t\t\t\"extendedEligibility\": \"{\\\"elig1\\\":\\\"value1\\\",\\\"elig2\\\":\\\"value2\\\"}\",\n" +
            "\t\t\t\"startDate\": \"2019-01-11T19:02:24.000Z\",\n" +
            "\t\t\t\"terminationDate\": \"2029-01-11T19:02:24.000Z\",\n" +
            "\t\t\t\"brandId\": null,\n" +
            "\t\t\t\"marketId\": \"9136000105613113827\",\n" +
            "\t\t\t\"customerCategoryId\": \"9136019180013129386\",\n" +
            "\t\t\t\"distributionChannelId\": \"9142905964913606451\",\n" +
            "\t\t\t\"businessGroupId\": null,\n" +
            "\t\t\t\"overrideMode\": \"NET\",\n" +
            "\t\t\t\"contractedDate\": \"2019-08-11T19:02:24.000Z\",\n" +
            "\t\t\t\"quantity\": \"1\",\n" +
            "\t\t\t\"externalId\": null,\n" +
            "\t\t\t\"numberOfInstallment\": null,\n" +
            "\t\t\t\"productOrderId\": null,\n" +
            "\t\t\t\"disconnectionReason\": null,\n" +
            "\t\t\t\"disconnectionReasonDescription\": null,\n" +
            "\t\t\t\"sourceQuoteItemId\": null,\n" +
            "\t\t\t\"placeRef\": {\n" +
            "\t\t\t\t\"_sourceRefId\": \"REF#33982226\",\n" +
            "\t\t\t\t\"id\": null,\n" +
            "\t\t\t\t\"_sourceId\": \"PLACE#TLO_01_CUST#00000002\",\n" +
            "\t\t\t\t\"refId\": \"REF#33982226\",\n" +
            "\t\t\t\t\"refType\": \"REF_TYPE_01\",\n" +
            "\t\t\t\t\"name\": \"PLACE TLO_01_CUST#00000002\",\n" +
            "\t\t\t\t\"role\": \"Some reference role\"\n" +
            "\t\t\t},\n" +
            "\t\t\t\"relatedPartyRef\": [\n" +
            "\t\t\t\t{\n" +
            "\t\t\t\t\t\"_sourceRefId\": \"IND#00000002\",\n" +
            "\t\t\t\t\t\"refId\": \"IND#00000002\",\n" +
            "\t\t\t\t\t\"referredType\": \"Individual\",\n" +
            "\t\t\t\t\t\"role\": \"UserOfService\",\n" +
            "\t\t\t\t\t\"name\": \"User Of Service TLO_01\"\n" +
            "\t\t\t\t},\n" +
            "\t\t\t\t{\n" +
            "\t\t\t\t\t\"_sourceRefId\": \"CUST#00000002\",\n" +
            "\t\t\t\t\t\"refId\": \"CUST#00000002\",\n" +
            "\t\t\t\t\t\"referredType\": \"Customer\",\n" +
            "\t\t\t\t\t\"role\": \"Customer\",\n" +
            "\t\t\t\t\t\"name\": \"User Of Service TLO_01\"\n" +
            "\t\t\t\t}\n" +
            "\t\t\t],\n" +
            "\t\t\t\"accountRef\": [\n" +
            "\t\t\t\t{\n" +
            "\t\t\t\t\t\"_sourceRefId\": \"ACC#001_CUST#00000002\",\n" +
            "\t\t\t\t\t\"refId\": \"ACC#001_CUST#00000002\",\n" +
            "\t\t\t\t\t\"refType\": \"PartyAccount\",\n" +
            "\t\t\t\t\t\"name\": \"ACCOUNT FOR TLO_01\"\n" +
            "\t\t\t\t}\n" +
            "\t\t\t],\n" +
            "\t\t\t\"agreementRef\": [\n" +
            "\t\t\t\t{\n" +
            "\t\t\t\t\t\"_sourceRefId\": \"AGR#001_CUST#00000002\",\n" +
            "\t\t\t\t\t\"refId\": \"AGR#001_CUST#00000002\",\n" +
            "\t\t\t\t\t\"refType\": \"Agreement\",\n" +
            "\t\t\t\t\t\"name\": \"AGREEMENT FOR TLO_01\"\n" +
            "\t\t\t\t}\n" +
            "\t\t\t]\n" +
            "\t\t}\n" +
            "\t]\n" +
            "}";
}
