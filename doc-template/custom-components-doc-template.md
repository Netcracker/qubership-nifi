# User Guide

Apache NiFi is scalable and configurable dataflow platform.
Sections below describe usage of additional components present only in qubership-nifi.
Refer to Apache NiFi [User Guide](https://nifi.apache.org/docs/nifi-docs/html/user-guide.html) for basic usage.

## Additional processors

Qubership-nifi contains additional processors compared with Apache NiFi.
Table below provides list of these processors with descriptions.
More information on their usage is available in Help (`Global Menu` -> `Help`) within qubership-nifi.

<!-- Table for additional processor. DO NOT REMOVE. -->

| Processor | NAR                 | Description        |
|------------------------|---------------------|--------------------|
| QueryDatabaseToJsonWithMerge | migration-nifi-processors-open | Executes custom query to fetch rows from table and merge them with the main JSON object which is in the content of incoming FlowFile.  The Path property supports JsonPath syntax to find source id attributes in the main object.  The main and queried objects are merged by join key properties.You can specify where exactly to insert queried objects and by what key with path  to insert and key to insert properties. |
| QueryDatabaseToJson | migration-nifi-processors-open | Fetches data from database table and transforms it to JSON.This processor gets incoming FlowFile and reads id attributes using Json Path. Found ids are passedin select query as an array. Obtained result set will be written into output FlowFile.Expects that content of an incoming FlowFile is array of unique business entity identifiers in the JSON format. |
| FetchTableToJson | migration-nifi-processors-open | Fetches data from DB table into JSON using either query (Custom Query) or table (Table) and  list of columns (Columns To Return). This processor works in batched mode: it collects FlowFiles until  batch size limit is reached and then processes batch. This processor can accept incoming connections;  the behavior of the processor is different whether incoming connections are provided: -If no incoming connection(s) are specified, the processor will generate SQL queries on the specified  processor schedule. -If incoming connection(s) are specified and no FlowFile is available to a processor task, no work will be performed. -If incoming connection(s) are specified and a FlowFile is available to a processor task, query  will be executed when processing the next FlowFile.  |
| ValidateJson | migration-nifi-processors-open | Validates the content of FlowFiles against the JSON schema. The FlowFiles that are successfully validated against the specified schema are routed to valid relationship without any changes. The FlowFiles that are not valid according to the schema are routed to invalid relationship. Array with validation errors is added to the content of FlowFile. |
| BackupAttributes | migration-nifi-processors-open | Backups all FlowFile attributes by adding prefix to their names. |
| PutGeneratedRecord | migration-nifi-processors-open | A processor that generates Records based on its properties and sends them to a destination specified by a Record Destination Service (i.e., record sink). The record source is defined by the 'Source Type' property, which can be either 'Dynamic Properties' or 'Json Property'. If 'Source Type' is set to 'Dynamic Properties', each dynamic property becomes a field in the Record, with the field type automatically determined by the value type: string, double, or Record (if the dynamic property contains a JSON value and is listed in the 'List Json Dynamic Property' property). If 'Source Type' is set to 'Json Property', the Record is generated directly from the JSON value in the 'Json Property'. |
| QueryDatabaseToCSV | migration-nifi-processors-open | Fetches data from DB using specified query and transforms it to CSV in particular CSV format.The processor allows to split query result into several FlowFiles and select CSV format for output. |
| PutSQLRecord | qubership-nifi-db-processors-nar | Executes given SQL statement using data from input records. All records within single  FlowFile are processed within single transaction. |
| PostgreSQLBulkLoader | qubership-nifi-db-processors-nar | The processor supports copying from stdin using the incoming content of the Flow File or a file accessible by path.It is also possible to copy from DB to FlowFile content. |

## Additional processors properties description

<!-- Additional processors properties description. DO NOT REMOVE -->
### PostgreSQLBulkLoader

The processor supports copying from stdin using the incoming content of the Flow File or a file accessible by path.It is also possible to copy from DB to FlowFile content.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Database Connection Pooling Service | dbcp-service |  |  | Database Connection Pooling Service to use for connecting to target Database. |
| SQL Query | sql-query |  |  | SQL query to execute. Copy command from stdin/to stdout. |
| File Path | file-path |  |  | Path to CSV file in file system. |
| Buffer Size | buffer-size |  |  | Number of characters to buffer and push over network to server at once. |
| Copy Mode | copy-mode | to | To, From | Provides a selection of copy mode (from stdin/to stdout). |
| Read From | read-from | file-system | File System, Content | Provides a selection of data to copy. |

### PutSQLRecord

Executes given SQL statement using data from input records. All records within single  FlowFile are processed within single transaction.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Record Reader | record-reader |  |  | Record Reader |
| SQL Statement | sql-statement |  |  | SQL statement that should be executed for each record. Statement must contains exactly the same number and types of binds as number and types of fields in RecordSchema. |
| Database Connection Pooling Service | dbcp-service |  |  | Database Connection Pooling Service to use for connecting to target Database |
| Maximum Batch Size | max-batch-size | 100 |  | Maximum number of records to include into DB batch |
| Convert Payload | convert-payload | false | true, false | When set to true, Map/Record/Array/Choice fields will be converted to JSON strings. Otherwise processor will throw exception, if Map/Record/Array/Choice fields are present in the input Record. By default is set to false. |

### FetchTableToJson

Fetches data from DB table into JSON using either query (Custom Query) or table (Table) and  list of columns (Columns To Return). This processor works in batched mode: it collects FlowFiles until  batch size limit is reached and then processes batch. This processor can accept incoming connections;  the behavior of the processor is different whether incoming connections are provided: -If no incoming connection(s) are specified, the processor will generate SQL queries on the specified  processor schedule. -If incoming connection(s) are specified and no FlowFile is available to a processor task, no work will be performed. -If incoming connection(s) are specified and a FlowFile is available to a processor task, query  will be executed when processing the next FlowFile. 

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Database Connection Pooling Service | Database Connection Pooling Service |  |  | The Controller Service that is used to obtain a connection to the database. |
| Table Name | table |  |  | The name of the database table to be queried. If Custom Query is set, this property is ignored. |
| Columns To Return | columns-to-return |  |  | A comma-separated list of column names to be used in the query. If your database requires special treatment of the names (quoting, e.g.), each name should include such treatment. If no column names are supplied, all columns in the specified table will be returned. NOTE: It is important to use consistent column names for a given table for incremental fetch to work properly. |
| Custom Query | custom-query |  |  | Custom query. Would be used instead of tables and columns properties if specified. |
| Batch Size | batch-size | 1 |  | The maximum number of rows from the result set to be saved in a single FlowFile. |
| Fetch Size | fetch-size | 1 |  | The number of result rows to be fetched from the result set at a time. This is a hint to the database driver and may not be honored and/or exact. If the value specified is zero, then the hint is ignored. |
| Write By Batch | write-by-batch | false | true, false | Write a type that corresponds to the behavior of appearing FlowFiles in the queue. |

### ValidateJson

Validates the content of FlowFiles against the JSON schema. The FlowFiles that are successfully validated against the specified schema are routed to valid relationship without any changes. The FlowFiles that are not valid according to the schema are routed to invalid relationship. Array with validation errors is added to the content of FlowFile.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| JSON Schema | validate-json-schema |  |  | Validation Json Schema |
| Entity Type Path | be-type-path | _businessEntityType |  | A JsonPath expression that specifies path to business entity type attribute in the content of incoming FlowFile. |
| ID Path | source-id-path | _sourceId |  | A JsonPath expression that specifies path to source id attribute in the content of incoming FlowFile. |
| Error Code | error-code | ME-JV-0002 |  | Validation error code. Used as identification error code when formatting an array of validation errors. |
| Wrapper regex | wrapper-regex |  |  | Regex to define path of wrapper in aggregated business entity. If validation errors are detected and regex is set and matched, the wrapper path will be removed from the error path, ID of the wrapper will be replaced to ID of the business entity. |

### QueryDatabaseToJsonWithMerge

Executes custom query to fetch rows from table and merge them with the main JSON object which is in the content of incoming FlowFile.  The Path property supports JsonPath syntax to find source id attributes in the main object.  The main and queried objects are merged by join key properties.You can specify where exactly to insert queried objects and by what key with path  to insert and key to insert properties.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Database Connection Pooling Service | Database Connection Pooling Service |  |  | The Controller Service that is used to obtain a connection to the database. |
| Prepared Statement Provider | prepared-statement-provider-service |  |  | The Controller Service that is used to create a prepared statement. |
| Batch Size | internal-batch-size | 100 |  | The maximum number of rows from the result set to be saved in single FlowFile. |
| Fetch Size | Fetch Size | 1000 |  | The number of result rows to be fetched from the result set at a time. This is a hint to the database driver and may not be honored and/or exact. If the value specified is zero, then the hint is ignored. |
| Query | db-fetch-sql-query |  |  | A custom SQL query used to retrieve data. Instead of building a SQL query from other properties, this query will be wrapped as a sub-query. Query must have no ORDER BY statement. |
| Path | path |  |  | A JsonPath expression that specifies path to source id attribute inside the array in the incoming FlowFile. |
| Join Key Parent With Child | join-key-parent-with-child |  |  | The objects' key in the input JSON that uses to join with objects that will be queried |
| Join Key Child With Parent | Join Key Child With Parent |  |  | The queried objects' key that uses to join with objects that will come in the input JSON |
| Key To Insert | key-to-insert |  |  | A key that is used to insert the queried object in the main object. |
| Path To Insert | path-to-insert |  |  | A key that uses to insert queried objects in the objects that will come in the input JSON |
| Clean Up Policy | clean-up-policy | NONE | NONE, SOURCE, TARGET | Defines cleanup policy for keys used in join:- TARGET: remove parent key- SOURCE: remove child key- NONE: don't remove any keys |

### QueryDatabaseToCSV

Fetches data from DB using specified query and transforms it to CSV in particular CSV format.The processor allows to split query result into several FlowFiles and select CSV format for output.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Database Connection Pooling Service | Database Connection Pooling Service |  |  | The Controller Service that is used to obtain a connection to the database. |
| Custom Query | custom-query |  |  | Query to run against DB to get CSV data from. |
| CSV Format | csv-format | Default | Default, Excel, InformixUnload, InformixUnloadCsv, MongoDBCsv, MongoDBTsv, MySQL, Oracle, PostgreSQLCsv, PostgreSQLText, RFC4180, TDF | CSV Format to use for data extraction |
| Batch Size | batch-size | 0 |  | The maximum number of rows from the result set to be saved in a single FlowFile. If set to 0, then the whole result set is saved to a single FlowFile. |
| Fetch Size | fetch-size | 10000 |  | The number of result rows to be fetched from the result set at a time.  This is a hint to the database driver and may not be honored and/or exact. If the value specified is zero, then the hint is ignored. |
| Write By Batch | write-by-batch | false | true, false | Write a type that corresponds to the behavior of appearing FlowFiles in the queue. |

### BackupAttributes

Backups all FlowFile attributes by adding prefix to their names.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Prefix Attribute | prefix-attr |  |  | FlowFile attribute to use as prefix for backup attributes |
| Excluded Attributes | excluded-attrs-regex |  |  | Regular expression defining attributes to exclude from backup |

### QueryDatabaseToJson

Fetches data from database table and transforms it to JSON.This processor gets incoming FlowFile and reads id attributes using Json Path. Found ids are passedin select query as an array. Obtained result set will be written into output FlowFile.Expects that content of an incoming FlowFile is array of unique business entity identifiers in the JSON format.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Database Connection Pooling Service | Database Connection Pooling Service |  |  | The Controller Service that is used to obtain a connection to the database. |
| Prepared Statement Provider | prepared-statement-provider-service |  |  | The Controller Service that is used to create a prepared statement. |
| Batch Size | internal-batch-size | 100 |  | The maximum number of rows from the result set to be saved in single FlowFile. |
| Fetch Size | Fetch Size | 1000 |  | The number of result rows to be fetched from the result set at a time. This is a hint to the database driver and may not be honored and/or exact. If the value specified is zero, then the hint is ignored. |
| Query | db-fetch-sql-query |  |  | A custom SQL query used to retrieve data. Instead of building a SQL query from other properties, this query will be wrapped as a sub-query. Query must have no ORDER BY statement. |
| Path | path |  |  | A JsonPath expression that specifies path to source id attribute inside the array in an incoming FlowFile. |

### PutGeneratedRecord

A processor that generates Records based on its properties and sends them to a destination specified by a Record Destination Service (i.e., record sink). The record source is defined by the 'Source Type' property, which can be either 'Dynamic Properties' or 'Json Property'. If 'Source Type' is set to 'Dynamic Properties', each dynamic property becomes a field in the Record, with the field type automatically determined by the value type: string, double, or Record (if the dynamic property contains a JSON value and is listed in the 'List Json Dynamic Property' property). If 'Source Type' is set to 'Json Property', the Record is generated directly from the JSON value in the 'Json Property'.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Record Destination Service | put-record-sink |  |  | The Controller Service which is used to send the result Record to some destination. |
| Source type | source-type | dynamicProperties | Dynamic Properties, Json Property | The source type that will be used to create the record. The record source can be a Dynamic Processor Property or a 'Json Property' property. |
| List Json Dynamic Property | list-json-dynamic-property |  |  | Comma-separated list of dynamic properties that contain JSON values |
| Json Property | json-property-object |  |  | A complex json object for generating Record.A JSON object must have a flat structure without nested objects or arrays of non-scalar types. Object keys directly correspond to attribute names and are used as field names. All values must be scalar. Arrays containing only numeric values are allowed. |


## Additional controller services

Qubership-nifi contains additional controller services compared with Apache NiFi.
Table below provides list of these controller services with descriptions.
More information on their usage is available in Help (`Global Menu` -> `Help`) within qubership-nifi.

<!-- Table additional controller services. DO NOT REMOVE. -->

| Controller Service | NAR                 | Description        |
|------------------------|---------------------|--------------------|
| RedisBulkDistributedMapCacheClientService | qubership-nifi-bulk-redis-nar | Provides a Redis-based distributed map cache client with bulk operation support. This service enables efficient batch operations on Redis cache, including bulk get-and-put-if-absent and bulk remove operations. It uses Lua scripting for atomic bulk operations and supports configurable TTL (time-to-live) for cached entries. The service is particularly useful for high-performance scenarios requiring atomic bulk cache operations across multiple NiFi instances. |
| OraclePreparedStatementWithArrayProvider | qubership-service-nar | Provides a prepared statement service. |
| PostgresPreparedStatementWithArrayProvider | qubership-service-nar | Provides a prepared statement service. |
| JsonContentValidator | qubership-service-nar | Provides validate method to check the JSON against a given schema. |
| QubershipPrometheusRecordSink | qubership-service-nar | A Record Sink service that exposes metrics to Prometheus via an embedded HTTP server  on a configurable port. Collects metrics from incoming records by treating string fields as labels, numeric fields as gauges, and nested records (with 'type' and 'value' fields) as counters or distribution summaries. |
| HttpLookupService | qubership-nifi-lookup-service-nar | Sends HTTP GET request with specified URL and headers (set up via dynamic PROPERTY_DESCRIPTORS) to look up values. If the response status code is 2xx, the response body is parsed with Record Reader and returned as array of records. Otherwise (status code other than 2xx), the controller service throws exception and logs the response body. |

## Additional controller services properties description

<!-- Additional controller services description. DO NOT REMOVE -->
### HttpLookupService

Sends HTTP GET request with specified URL and headers (set up via dynamic PROPERTY_DESCRIPTORS) to look up values. If the response status code is 2xx, the response body is parsed with Record Reader and returned as array of records. Otherwise (status code other than 2xx), the controller service throws exception and logs the response body.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| URL | http-lookup-url |  |  | The URL to send request to. Expression language is supported and evaluated against both the lookup key/value pairs and FlowFile attributes. |
| Record Reader | http-lookup-record-reader |  |  | The record reader to use for loading response body and handling it as a record set. |
| Connection Timeout | http-lookup-connection-timeout | 5 secs |  | Max wait time for connection to remote service. |
| Read Timeout | http-lookup-read-timeout | 15 secs |  | Max wait time for response from remote service. |

### PostgresPreparedStatementWithArrayProvider

Provides a prepared statement service.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Char Array Type | array-type | text |  | Character array base type. |
| Numeric Array Type | numeric-array-type | numeric |  | Numeric array base type. |

### QubershipPrometheusRecordSink

A Record Sink service that exposes metrics to Prometheus via an embedded HTTP server  on a configurable port. Collects metrics from incoming records by treating string fields as labels, numeric fields as gauges, and nested records (with 'type' and 'value' fields) as counters or distribution summaries.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Prometheus Metrics Endpoint Port | prometheus-sink-metrics-endpoint-port | 9092 |  | The Port where prometheus metrics can be scraped from. |
| Instance ID | prometheus-sink-instance-id | ${hostname(true)}_${NAMESPACE} |  | Identifier of the NiFi instance to be included in the metrics as a label. |
| Clear Metrics on Disable | prometheus-sink-clear-metrics | No | Yes, No | If set to Yes, all metrics stored in the controller service are cleared, when the controller service is disabled. By default, metrics are not cleared. |

### JsonContentValidator

Provides validate method to check the JSON against a given schema.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Schema | schema |  |  | Validation Json Schema. |

### OraclePreparedStatementWithArrayProvider

Provides a prepared statement service.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Schema Name | dbSchema |  |  | Owner of the array type |
| Char Array Type | array-type | ARRAYOFSTRINGS |  | Character-based array type. |
| Numeric Array Type | num-array-type | ARRAYOFNUMBERS |  | Numeric array type. |

### RedisBulkDistributedMapCacheClientService

Provides a Redis-based distributed map cache client with bulk operation support. This service enables efficient batch operations on Redis cache, including bulk get-and-put-if-absent and bulk remove operations. It uses Lua scripting for atomic bulk operations and supports configurable TTL (time-to-live) for cached entries. The service is particularly useful for high-performance scenarios requiring atomic bulk cache operations across multiple NiFi instances.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Redis Connection Pool | redis-connection-pool |  |  | A service that provides connections to Redis. |
| TTL | redis-cache-ttl | 0 secs |  | Indicates how long the data should exist in Redis.Setting '0 secs' would mean the data would exist forever |


## Additional reporting tasks

Qubership-nifi contains additional reporting tasks compared with Apache NiFi.
Table below provides list of these reporting tasks with descriptions.
More information on their usage is available in Help (`Global Menu` -> `Help`) within qubership-nifi.

<!-- Table additional reporting tasks. DO NOT REMOVE. -->

| Reporting Task | NAR                 | Description        |
|------------------------|---------------------|--------------------|
| ComponentMetricsReportingTask | migration-nifi-processors-open | Sends components (Processors, Connections) metrics to InfluxDB. |
| CommonMetricsReportingTask | migration-nifi-processors-open | Sends Nifi metrics to InfluxDB. |
| ComponentPrometheusReportingTask | migration-nifi-processors-open | Sends components (Processors, Connections) metrics to Prometheus. |

## Additional reporting tasks properties description

<!-- Additional reporting tasks description. DO NOT REMOVE -->
### ComponentMetricsReportingTask

Sends components (Processors, Connections) metrics to InfluxDB.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Database Name | influxdb-dbname | monitoring_server |  | InfluxDB database |
| InfluxDB URL | influxdb-url | `http://localhost:8086` |  | InfluxDB URL to connect to. For example, `http://influxdb:8086` |
| Connection timeout | Connection timeout | 0 seconds |  | The maximum time for establishing connection to the InfluxDB |
| Username | influxdb-username |  |  | Username for accessing InfluxDB |
| Password | influxdb-password |  |  | Password for accessing InfluxDB |
| Character Set | influxdb-charset | UTF-8 |  | Specifies the character set of the document data. |
| Consistency Level | influxdb-consistency-level | ONE | One, Any, All, Quorum | InfluxDB consistency level |
| Retention Policy | influxdb-retention-policy | monitor |  | Retention policy for the saving the records |
| Max size of records | influxdb-max-records-size | 1 MB |  | Maximum size of records allowed to be posted in one batch |
| Processor time threshold | processor-time-threshold | 150 sec |  | Minimal processing time for processor to be included in monitoring.Limits data volume collected in InfluxDB. |
| Connection queue threshold | connection-queue-threshold | 80 |  | Minimal connection usage % relative to backPressureObjectThreshold.Limits data volume collected in InfluxDB. |

### CommonMetricsReportingTask

Sends Nifi metrics to InfluxDB.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Database Name | influxdb-dbname | monitoring_server |  | InfluxDB database |
| InfluxDB URL | influxdb-url | `http://localhost:8086` |  | InfluxDB URL to connect to. For example, `http://influxdb:8086` |
| Connection timeout | Connection timeout | 0 seconds |  | The maximum time for establishing connection to the InfluxDB |
| Username | influxdb-username |  |  | Username for accessing InfluxDB |
| Password | influxdb-password |  |  | Password for accessing InfluxDB |
| Character Set | influxdb-charset | UTF-8 |  | Specifies the character set of the document data. |
| Consistency Level | influxdb-consistency-level | ONE | One, Any, All, Quorum | InfluxDB consistency level |
| Retention Policy | influxdb-retention-policy | monitor |  | Retention policy for the saving the records |
| Max size of records | influxdb-max-records-size | 1 MB |  | Maximum size of records allowed to be posted in one batch |

### ComponentPrometheusReportingTask

Sends components (Processors, Connections) metrics to Prometheus.

| Display Name                      | API Name            | Default Value      | Allowable Values   | Description        |
|-----------------------------------|---------------------|--------------------|--------------------|--------------------|
| Server Port | port | 9192 |  | The Port where prometheus metrics can be accessed. |
| Meter Registry Provider | meter-registry-provider |  |  | Identifier of Controller Services, which is used to obtain the Meter Registry. |
| Processor time threshold | processor-time-threshold | 150 sec |  | Minimal processing time for processor to be included in monitoring.Limits data volume collected in Prometheus. |
| Connection queue threshold | connection-queue-threshold | 80 |  | Minimal connection usage % relative to backPressureObjectThreshold.Limits data volume collected in Prometheus. |
| Process group level threshold | pg-level-threshold | 2 |  | Maximum depth of process group to report in monitoring.Limits data volume collected in Prometheus. |

