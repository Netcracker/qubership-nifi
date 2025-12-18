# User Guide

Apache NiFi is scalable and configurable dataflow platform.
Sections below describe usage of additional components present only in qubership-nifi.
Refer to Apache NiFi [User Guide](https://nifi.apache.org/docs/nifi-docs/html/user-guide.html) for basic usage.

## Additional processors

Qubership-nifi contains additional processors compared with Apache NiFi.
Table below provides list of these processors with descriptions.
More information on their usage is available in Help (`Global Menu` -> `Help`) within qubership-nifi.

| Processor                    | NAR                              | Description                                                                                                                                                                                                                                                                                     |
|------------------------------|----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| BackupAttributes             | migration-nifi-processors-open   | Backups all FlowFile attributes by adding prefix to their names.                                                                                                                                                                                                                                |
| FetchTableToJson             | migration-nifi-processors-open   | Fetches data from DB table into JSON using either query or table and list of columns. This processor works in batched mode: it collects FlowFiles until batch size limit is reached and then commits the session.                                                                               |
| QueryDatabaseToCSV           | migration-nifi-processors-open   | Fetches data from DB using specified query and transforms it to CSV in particular CSV format. The processor allows to split query result into several FlowFiles and select CSV format for output.                                                                                               |
| QueryDatabaseToJson          | migration-nifi-processors-open   | Fetches data from database table and transforms it to JSON. This processor gets incoming FlowFile and reads ID attributes using Json Path. Found IDs are passed in select query as an array. Expects that content of an incoming FlowFile is an array of entity identifiers in the JSON format. |
| QueryDatabaseToJsonWithMerge | migration-nifi-processors-open   | Executes query to fetch rows from the table and merge them with the main JSON object in the content of incoming FlowFile. The Path property supports JsonPath syntax to find source ID attributes in the main object. The main and queried objects are merged using join key properties.        |
| QueryIdsAndFetchTableToJson  | migration-nifi-processors-open   | Executes query to fetch IDs from one DB Connection and then uses these IDs to execute another query to fetch data from other DB Connection and transform it into JSON.                                                                                                                          |
| PutGeneratedRecord           | migration-nifi-processors-open   | Generates Records based on the processor properties and sends them to a destination specified by a Record Destination Service, i.e. record sink.  <br/>                                                                                                                                              |
| PostgreSQLBulkLoader         | qubership-nifi-db-processors-nar | This processor supports PostgreSQL's COPY command both FROM (either content of the incoming FlowFile or some file accessible in the file system) or TO (to content of FlowFile or some file accessible in the file system).                                                                     |
| PutSQLRecord                 | qubership-nifi-db-processors-nar | Executes given SQL statement using data from input records. All records within single FlowFile are processed within single transaction. Allows to use "?" in SQL for passing values from the record.                                                                                            |

## Additional controller services

Qubership-nifi contains additional controller services compared with Apache NiFi.
Table below provides list of these controller services with descriptions.
More information on their usage is available in Help (`Global Menu` -> `Help`) within qubership-nifi.

| Controller Service                         | NAR                               | Description                                                                                                                                                                                                                                                                                               |
|--------------------------------------------|-----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| HttpLookupService                          | qubership-nifi-lookup-service-nar | Sends HTTP GET request with specified URL and headers to look up values. If the response status code is 2xx, the response body is parsed with Record Reader and returned as array of records. Otherwise (status code other than 2xx), the controller service throws exception and logs the response body. |
| JsonContentValidator                       | qubership-service-nar             | Validates JSON against a given JSON schema.                                                                                                                                                                                                                                                               |
| OraclePreparedStatementWithArrayProvider   | qubership-service-nar             | Provides a prepared statement service.                                                                                                                                                                                                                                                                    |
| PostgresPreparedStatementWithArrayProvider | qubership-service-nar             | Provides a prepared statement service.                                                                                                                                                                                                                                                                    |
| QubershipPrometheusRecordSink              | qubership-service-nar             | Exposes metrics endpoint on specified port to allow scraping by Prometheus service. Implements RecordSink interface that allows to put records with metrics into this service to be exposed at metrics endpoint.                                                                                          |
| RedisBulkDistributedMapCacheClientService  | qubership-nifi-bulk-redis-nar     | Provides methods for bulk redis operations.                                                                                                                                                                                                                                                               |

## Additional reporting tasks

Qubership-nifi contains additional reporting tasks compared with Apache NiFi.
Table below provides list of these reporting tasks with descriptions.
More information on their usage is available in Help (`Global Menu` -> `Help`) within qubership-nifi.

| Reporting Task                             | NAR                            | Description                                                                                                                                                   |
|--------------------------------------------|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| CommonMetricsReportingTask                 | migration-nifi-processors-open | Sends NiFi metrics to InfluxDB.                                                                                                                               |
| ComponentMetricsReportingTask              | migration-nifi-processors-open | Sends components (Processors, Connections) metrics to InfluxDB.                                                                                               |
| ComponentPrometheusReportingTask           | migration-nifi-processors-open | Exposes metrics endpoint on the specified port to allow scraping by Prometheus service. Stores components (Processors, Connections) and standard JVM metrics. |
