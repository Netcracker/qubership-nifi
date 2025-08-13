#!/bin/bash

# shellcheck source=/dev/null
# shellcheck disable=SC2089

handle_error() {
    echo "$1" >&2
    exit 1
}

deprecatedComponents='{
    "org.apache.nifi.rules.handlers.ActionHandlerLookup": {
        "level": "Error",
        "issue": "The ActionHandlerLookup Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.rules.handlers.AlertHandler": {
        "level": "Error",
        "issue": "The AlertHandler Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.Base64EncodeContent": {
        "level": "Warning",
        "issue": "The Base64EncodeContent Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use EncodeContent Processor instead of Base64EncodeContent."
    },
    "org.apache.nifi.service.CassandraSessionProvider": {
        "level": "Error",
        "issue": "The CassandraSessionProvider Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.cybersecurity.CompareFuzzyHash": {
        "level": "Error",
        "issue": "The CompareFuzzyHash Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.email.ConsumeEWS": {
        "level": "Error",
        "issue": "The ConsumeEWS Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.avro.ConvertAvroToJSON": {
        "level": "Warning",
        "issue": "The ConvertAvroToJSON Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ConvertRecord Processor instead of ConvertAvroToJSON. This may require adjusting the properties and connections in the flow, as well as creation of necessary RecordReader and RecordWriter."
    },
    "org.apache.nifi.processors.poi.ConvertExcelToCSVProcessor": {
        "level": "Warning",
        "version": "1.23.0",
        "issue": "The ConvertExcelToCSVProcessor Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ConvertRecord Processor instead of ConvertExcelToCSVProcessor. This may require adjusting the properties and connections in the flow, as well as creation of necessary ExcelReader and CSVRecordSetWriter."
    },
    "org.apache.nifi.couchbase.CouchbaseClusterService": {
        "level": "Error",
        "issue": "The CouchbaseClusterService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.couchbase.CouchbaseKeyValueLookupService": {
        "level": "Error",
        "issue": "The CouchbaseKeyValueLookupService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.couchbase.CouchbaseMapCacheClient": {
        "level": "Error",
        "issue": "The CouchbaseMapCacheClient Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.couchbase.CouchbaseRecordLookupService": {
        "level": "Error",
        "issue": "The CouchbaseRecordLookupService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.CryptographicHashAttribute": {
        "level": "Warning",
        "issue": "The CryptographicHashAttribute Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use UpdateAttribute Processor with expression language hash() function instead of CryptographicHashAttribute."
    },
    "org.apache.nifi.processors.cipher.DecryptContent": {
        "level": "Error",
        "issue": "The DecryptContent Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.cipher.DecryptContentCompatibility": {
        "level": "Error",
        "issue": "The DecryptContentCompatibility Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.DeleteHBaseCells": {
        "level": "Error",
        "issue": "The DeleteHBaseCells Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.DeleteHBaseRow": {
        "level": "Error",
        "issue": "The DeleteHBaseRow Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.rethinkdb.DeleteRethinkDB": {
        "level": "Error",
        "issue": "The DeleteRethinkDB Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.EncryptContent": {
        "level": "Warning",
        "issue": "The EncryptContent Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use EncryptContentAge or EncryptContentPGP Processor instead of EncryptContent."
    },
    "org.apache.nifi.processors.flume.ExecuteFlumeSink": {
        "level": "Error",
        "issue": "The ExecuteFlumeSink Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.flume.ExecuteFlumeSource": {
        "level": "Error",
        "issue": "The ExecuteFlumeSource Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.influxdb.ExecuteInfluxDBQuery": {
        "level": "Warning",
        "issue": "The ExecuteInfluxDBQuery Processor is not available in Apache NiFi 2.x.",
        "solution": "Add nifi-influxdb-bundle NAR; update the flow to use InfluxData Processor instead of ExecuteInfluxDBQuery."
    },
    "org.apache.nifi.rules.handlers.ExpressionHandler": {
        "level": "Error",
        "issue": "The ExpressionHandler Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.ccda.ExtractCCDAAttributes": {
        "level": "Error",
        "issue": "The ExtractCCDAAttributes Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.email.ExtractTNEFAttachments": {
        "level": "Error",
        "issue": "The ExtractTNEFAttachments Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.elasticsearch.FetchElasticsearchHttp": {
        "level": "Warning",
        "version": "1.16.0",
        "issue": "The FetchElasticsearchHttp Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use GetElasticsearch Processor instead of FetchElasticsearchHttp."
    },
    "org.apache.nifi.hbase.FetchHBaseRow": {
        "level": "Error",
        "issue": "The FetchHBaseRow Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.cybersecurity.FuzzyHashContent": {
        "level": "Error",
        "issue": "The FuzzyHashContent Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.azure.storage.queue.GetAzureQueueStorage": {
        "level": "Warning",
        "version": "1.22.0",
        "issue": "The GetAzureQueueStorage Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use GetAzureQueueStorage_v12 Processor instead of GetAzureQueueStorage."
    },
    "org.apache.nifi.processors.couchbase.GetCouchbaseKey": {
        "level": "Error",
        "issue": "The GetCouchbaseKey Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.GetHBase": {
        "level": "Error",
        "issue": "The GetHBaseRow Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.GetHTMLElement": {
        "level": "Error",
        "issue": "The GetHTMLElement Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.GetHTTP": {
        "level": "Warning",
        "issue": "The GetHTTP Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use InvokeHTTP Processor instead of GetHTTP."
    },
    "org.apache.nifi.processors.ignite.cache.GetIgniteCache": {
        "level": "Error",
        "issue": "The GetIgniteCache Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.GetJMSQueue": {
        "level": "Warning",
        "issue": "The GetJMSQueue Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ConsumeJMS Processor instead of GetJMSQueue."
    },
    "org.apache.nifi.processors.standard.GetJMSTopic": {
        "level": "Warning",
        "issue": "The GetJMSTopic Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ConsumeJMS Processor instead of GetJMSTopic."
    },
    "org.apache.nifi.processors.rethinkdb.GetRethinkDB": {
        "level": "Error",
        "issue": "The GetRethinkDB Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.solr.GetSolr": {
        "level": "Error",
        "issue": "The GetSolr Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.gettcp.GetTCP": {
        "level": "Error",
        "issue": "The GetTCP Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.twitter.GetTwitter": {
        "level": "Warning",
        "version": "1.17.0",
        "issue": "The GetTwitter Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ConsumeTwitter Processor instead of GetTwitter."
    },
    "org.apache.nifi.metrics.reporting.reporter.service.GraphiteMetricReporterService": {
        "level": "Error",
        "issue": "The GraphiteMetricReporterService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.azure.storage.DeleteAzureBlobStorage": {
        "level": "Warning",
        "version": "1.22.0",
        "issue": "The DeleteAzureBlobStorage Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use DeleteAzureBlobStorage_v12 Processor instead of DeleteAzureBlobStorage."
    },
    "org.apache.nifi.processors.livy.ExecuteSparkInteractive": {
        "level": "Error",
        "issue": "The ExecuteSparkInteractive Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.azure.storage.FetchAzureBlobStorage": {
        "level": "Warning",
        "version": "1.22.0",
        "issue": "The FetchAzureBlobStorage Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use FetchAzureBlobStorage_v12 Processor instead of FetchAzureBlobStorage."
    },
    "org.apache.nifi.services.iceberg.HadoopCatalogService": {
        "level": "Error",
        "issue": "The HadoopCatalogService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.services.iceberg.HiveCatalogService": {
        "level": "Error",
        "issue": "The HiveCatalogService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.dbcp.hive.HiveConnectionPool": {
        "level": "Error",
        "issue": "The HiveConnectionPool Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.dbcp.hive.Hive_1_1ConnectionPool": {
        "level": "Error",
        "issue": "The Hive_1_1ConnectionPool Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.dbcp.hive.Hive3ConnectionPool": {
        "level": "Error",
        "issue": "The Hive3ConnectionPool Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.HashAttribute": {
        "level": "Warning",
        "issue": "The HashAttribute Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use UpdateAttribute Processor with expression language hash() function instead of HashAttribute."
    },
    "org.apache.nifi.processors.standard.HashContent": {
        "level": "Warning",
        "issue": "The HashContent Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use CryptographicHashContent Processor instead of HashContent."
    },
    "org.apache.nifi.schemaregistry.hortonworks.HortonworksSchemaRegistry": {
        "level": "Error",
        "issue": "The HortonworksSchemaRegistry Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.HBase_2_ClientMapCacheService": {
        "level": "Error",
        "issue": "The HBase_2_ClientMapCacheService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.HBase_2_ClientService": {
        "level": "Error",
        "issue": "The HBase_2_ClientService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.HBase_2_LIstLookupService": {
        "level": "Error",
        "issue": "The HBase_2_LIstLookupService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.HBase_2_RecordLookupService": {
        "level": "Error",
        "issue": "The HBase_2_RecordLookupService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.ListHBaseRegions": {
        "level": "Error",
        "issue": "The ListHBaseRegions Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.aws.wag.InvokeAWSGatewayApi": {
        "level": "Error",
        "issue": "The InvokeAWSGatewayApi Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.grpc.InvokeGRPC": {
        "level": "Error",
        "issue": "The InvokeGRPC Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.services.iceberg.JdbcCatalogService": {
        "level": "Error",
        "issue": "The JdbcCatalogService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.controller.kudu.KuduLookupService": {
        "level": "Error",
        "issue": "The KuduLookupService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.beats.ListenBeats": {
        "level": "Error",
        "issue": "The ListenBeats Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.grpc.ListenGRPC": {
        "level": "Error",
        "issue": "The ListenGRPC Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.ListenRELP": {
        "level": "Warning",
        "version": "1.24.0",
        "issue": "The ListenRELP Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ListenOTLP Processor instead of ListenRELP."
    },
    "org.apache.nifi.processors.email.ListenSMTP": {
        "level": "Error",
        "issue": "The ListenSMTP Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.ListenTCPRecord": {
        "level": "Warning",
        "issue": "The ListenTCPRecord Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ListenTCP Processor instead of ListenTCPRecord."
    },
    "org.apache.nifi.processors.azure.storage.ListAzureBlobStorage": {
        "level": "Warning",
        "version": "1.22.0",
        "issue": "The ListAzureBlobStorage Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ListAzureBlobStorage_v12 Processor instead of ListAzureBlobStorage."
    },
    "org.apache.nifi.controller.livy.LivySessionController": {
        "level": "Error",
        "issue": "The LivySessionController Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.rules.handlers.LogHandler": {
        "level": "Error",
        "issue": "The LogHandler Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.ModifyHTMLElement": {
        "level": "Error",
        "issue": "The ModifyHTMLElement Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.graph.Neo4JCypher3ClientService": {
        "level": "Warning",
        "issue": "The Neo4JCypher3ClientService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use Neo4JCypherClientService Controller Service instead of Neo4JCypher3ClientService."
    },
    "org.apache.nifi.oauth2.OAuth2TokenProviderImpl": {
        "level": "Warning",
        "version": "1.16.0",
        "issue": "The OAuth2TokenProviderImpl Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use StandardOauth2AccessTokenProvider Controller Service instead of OAuth2TokenProviderImpl."
    },
    "org.apache.nifi.graph.OpenCypherClientService": {
        "level": "Error",
        "issue": "The OpenCypherClientService Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.ParseCEF": {
        "level": "Warning",
        "issue": "The ParseCEF Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use ConvertRecord Processor with CEFReader and JsonRecordSetWriter controller services instead of ParseCEF processor."
    },
    "org.apache.nifi.processors.standard.PostHTTP": {
        "level": "Warning",
        "issue": "The PostHTTP Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use InvokeHTTP Processor instead of PostHTTP."
    },
    "org.apache.nifi.processors.slack.PostSlack": {
        "level": "Warning",
        "issue": "The PostSlack Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PublishSlack Processor instead of PostSlack."
    },
    "org.apache.nifi.processors.cassandra.PutCassandraQL": {
        "level": "Error",
        "issue": "The PutCassandraQL Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.cassandra.PutCassandraRecord": {
        "level": "Error",
        "issue": "The PutCassandraRecord Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.couchbase.PutCouchbaseKey": {
        "level": "Error",
        "issue": "The PutCouchbaseKey Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.azure.storage.PutAzureBlobStorage": {
        "level": "Warning",
        "version": "1.22.0",
        "issue": "The PutAzureBlobStorage Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PutAzureBlobStorage_v12 Processor instead of PutAzureBlobStorage."
    },
    "org.apache.nifi.processors.azure.storage.queue.PutAzureQueueStorage": {
        "level": "Warning",
        "version": "1.22.0",
        "issue": "The PutAzureQueueStorage Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PutAzureQueueStorage_v12 Processor instead of PutAzureQueueStorage."
    },
    "org.apache.nifi.processors.gcp.bigquery.PutBigQueryBatch": {
        "level": "Warning",
        "version": "1.18.0",
        "issue": "The PutBigQueryBatch Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PutBigQuery Processor instead of PutBigQueryBatch."
    },
    "org.apache.nifi.processors.gcp.bigquery.PutBigQueryStreaming": {
        "level": "Warning",
        "version": "1.18.0",
        "issue": "The PutBigQueryStreaming Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PutBigQuery Processor instead of PutBigQueryStreaming."
    },
    "org.apache.nifi.processors.elasticsearch.PutElasticsearchHttp": {
        "level": "Warning",
        "version": "1.16.0",
        "issue": "The PutElasticsearchHttp Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PutElasticsearchJson Processor instead of PutElasticsearchHttp."
    },
    "org.apache.nifi.processors.elasticsearch.PutElasticsearchHttpRecord": {
        "level": "Warning",
        "version": "1.16.0",
        "issue": "The PutElasticsearchHttpRecord Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PutElasticsearchJson Processor instead of PutElasticsearchHttpRecord."
    },
    "org.apache.nifi.hbase.PutHBaseCell": {
        "level": "Error",
        "issue": "The PutHBaseCell Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.PutHBaseJSON": {
        "level": "Error",
        "issue": "The PutHBaseJSON Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.PutHBaseRecord": {
        "level": "Error",
        "issue": "The PutHBaseRecord Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.PutHiveQL": {
        "level": "Error",
        "issue": "The PutHiveQL Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.PutHive_1_1QL": {
        "level": "Error",
        "issue": "The PutHive_1_1QL Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.PutHiveStreaming": {
        "level": "Error",
        "issue": "The PutHiveStreaming Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.PutHive3QL": {
        "level": "Error",
        "issue": "The PutHive3QL Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.PutHive3Streaming": {
        "level": "Error",
        "issue": "The PutHive3Streaming Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.PutHTMLElement": {
        "level": "Error",
        "issue": "The PutHTMLElement Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.iceberg.PutIceberg": {
        "level": "Error",
        "issue": "The PutIceberg Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.ignite.cache.PutIgniteCache": {
        "level": "Error",
        "issue": "The PutIgniteCache Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.influxdb.PutInfluxDB": {
        "level": "Warning",
        "issue": "The PutInfluxDB Processor is not available in Apache NiFi 2.x.",
        "solution": "Add nifi-influxdb-bundle NAR; update the flow to use InfluxData Processor instead of PutInfluxDB."
    },
    "org.apache.nifi.processors.standard.PutJMS": {
        "level": "Warning",
        "issue": "The PutJMS Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PublishJMS Processor instead of PutJMS."
    },
    "org.apache.nifi.processors.kudu.PutKudu": {
        "level": "Error",
        "issue": "The PutKudu Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.orc.PutORC": {
        "level": "Error",
        "issue": "The PutORC Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.rethinkdb.PutRethinkDB": {
        "level": "Error",
        "issue": "The PutRethinkDB Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.riemann.PutRiemann": {
        "level": "Error",
        "issue": "The PutRiemann Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.slack.PutSlack": {
        "level": "Warning",
        "issue": "The PutSlack Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PublishSlack Processor instead of PutSlack."
    },
    "org.apache.nifi.processors.solr.PutSolrContentStream": {
        "level": "Error",
        "issue": "The PutSolrContentStream Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.solr.PutSolrRecord": {
        "level": "Error",
        "issue": "The PutSolrRecord Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.cassandra.QueryCassandra": {
        "level": "Error",
        "issue": "The QueryCassandra Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.enrich.QueryDNS": {
        "level": "Error",
        "issue": "The QueryDNS Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.elasticsearch.QueryElasticsearchHttp": {
        "level": "Warning",
        "version": "1.15.0",
        "issue": "The QueryElasticsearchHttp Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PaginatedJsonQueryElasticsearch Processor instead of QueryElasticsearchHttp."
    },
    "org.apache.nifi.processors.solr.QuerySolr": {
        "level": "Error",
        "issue": "The QuerySolr Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.enrich.QueryWhois": {
        "level": "Error",
        "issue": "The QueryWhois Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.rules.handlers.RecordSinkHandler": {
        "level": "Error",
        "issue": "The RecordSinkHandler Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.hbase.ScanHBase": {
        "level": "Error",
        "issue": "The ScanHBase Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.elasticsearch.ScrollElasticsearchHttp": {
        "level": "Warning",
        "version": "1.15.0",
        "issue": "The ScrollElasticsearchHttp Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use SearchElasticsearch Processor instead of ScrollElasticsearchHttp."
    },
    "org.apache.nifi.processors.hive.SelectHiveQL": {
        "level": "Error",
        "issue": "The SelectHiveQL Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.SelectHive_1_1QL": {
        "level": "Error",
        "issue": "The SelectHive_1_1QL Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.SelectHive3QL": {
        "level": "Error",
        "issue": "The SelectHive3QL Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.spring.SpringContextProcessor": {
        "level": "Error",
        "issue": "The SpringContextProcessor Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.TriggerHiveMetaStoreEvent": {
        "level": "Error",
        "issue": "The TriggerHiveMetaStoreEvent Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.UpdateHiveTable": {
        "level": "Error",
        "issue": "The UpdateHiveTable Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.UpdateHive_1_1Table": {
        "level": "Error",
        "issue": "The UpdateHive_1_1Table Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.hive.UpdateHive3Table": {
        "level": "Error",
        "issue": "The UpdateHive3Table Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.yandex.YandexTranslate": {
        "level": "Error",
        "issue": "The YandexTranslate Processor is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.processors.standard.ConvertJSONToSQL": {
        "level": "Warning",
        "issue": "The ConvertJSONToSql Processor is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use PutDatabaseRecord or PutSQLRecord Processor with JsonTreeReader Controller Service instead of ConvertJSONToSQL Processor."
    },
    "org.apache.nifi.reporting.prometheus.PrometheusRecordSink": {
        "level": "Warning",
        "issue": "The PrometheusRecordSink Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Update the flow to use QubershipPrometheusRecordSink Controller Service instead of PrometheusRecordSink."
    },
    "org.apache.nifi.processors.stateless.ExecuteStateless": {
        "level": "Error",
        "version": "2.x",
        "issue": "The ExecuteStateless Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use Stateless Process Group instead of ExecuteStateless processor; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_1_0": {
        "level": "Error",
        "version": "2.x",
        "issue": "The ConsumeKafka_1_0 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use ConsumeKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_2_0": {
        "level": "Error",
        "version": "2.x",
        "issue": "The ConsumeKafka_2_0 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use ConsumeKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_2_6": {
        "level": "Error",
        "version": "2.x",
        "issue": "The ConsumeKafka_2_6 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use ConsumeKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_1_0": {
        "level": "Error",
        "version": "2.x",
        "issue": "The ConsumeKafkaRecord_1_0 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use ConsumeKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_2_0": {
        "level": "Error",
        "version": "2.x",
        "issue": "The ConsumeKafkaRecord_2_0 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use ConsumeKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_2_6": {
        "level": "Error",
        "version": "2.x",
        "issue": "The ConsumeKafkaRecord_2_6 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use ConsumeKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.PublishKafka_1_0": {
        "level": "Error",
        "version": "2.x",
        "issue": "The PublishKafka_1_0 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use PublishKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.PublishKafka_2_0": {
        "level": "Error",
        "version": "2.x",
        "issue": "The PublishKafka_2_0 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use PublishKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.PublishKafka_2_6": {
        "level": "Error",
        "version": "2.x",
        "issue": "The PublishKafka_2_6 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use PublishKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.PublishKafkaRecord_1_0": {
        "level": "Error",
        "version": "2.x",
        "issue": "The PublishKafkaRecord_1_0 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use PublishKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.PublishKafkaRecord_2_0": {
        "level": "Error",
        "version": "2.x",
        "issue": "The PublishKafkaRecord_2_0 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use PublishKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.processors.kafka.pubsub.PublishKafkaRecord_2_6": {
        "level": "Error",
        "version": "2.x",
        "issue": "The PublishKafkaRecord_2_6 Processor is not available in Apache NiFi 2.x.",
        "solution": "1) Upgrade to Apache NiFi 2.x; 2) Update the flow to use PublishKafka + Kafka3ConnectionService: create new processors and controller services and fill in their properties; 3) [Optional] If both 1.x and 2.x must be supported, two versions of the flow must be stored and maintained in parallel."
    },
    "org.apache.nifi.record.sink.kafka.KafkaRecordSink_1_0": {
        "level": "Error",
        "issue": "The KafkaRecordSink_1_0 Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.record.sink.kafka.KafkaRecordSink_2_0": {
        "level": "Error",
        "issue": "The KafkaRecordSink_2_0 Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.record.sink.kafka.KafkaRecordSink_2_6": {
        "level": "Error",
        "issue": "The KafkaRecordSink_2_6 Controller Service is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    }
}'

deprecatedReportingTask='{
    "org.apache.nifi.reporting.ganglia.StandardGangliaReporter": {
        "level": "Warning",
        "issue": "The StandardGangliaReporter Reporting Task is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.atlas.reporting.ReportLineageToAtlas": {
        "level": "Error",
        "issue": "The ReportLineageToAtlas Reporting Task is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.metrics.reporting.task.MetricsReportingTask": {
        "level": "Error",
        "issue": "The MetricsReportingTask Reporting Task is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.reporting.ambari.AmbariReportingTask": {
        "level": "Error",
        "issue": "The AmbariReportingTask Reporting Task is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.reporting.datadog.DatadogReportingTask": {
        "level": "Error",
        "issue": "The DatadogReportingTask Reporting Task is not available in Apache NiFi 2.x.",
        "solution": "Research if any alternatives can be used instead. If not, create a custom component."
    },
    "org.apache.nifi.reporting.prometheus.PrometheusReportingTask": {
        "level": "Warning",
        "issue": "The PrometheusReportingTask Reporting Task is not available in Apache NiFi 2.x.",
        "solution": "The following options are available to you: 1) rely on standard Apache NiFi REST API for metrics 2) create custom reporting task 3) rely on qubership-nifi ComponentPrometheusReportingTask"
    }
}'

pathToExports=$1
csvSeparator=$2
reportFileName="$3"

#declare array
declare -a exportFlow

if [ -z "$pathToExports" ]; then
    echo "The first argument - 'pathToExports' is not set. The default value - '.' will be used."
    pathToExports="."
fi

if [ -z "$csvSeparator" ]; then
    echo "The second argument - 'csvSeparator' is not set. The default value - 'comma' (',') will be used."
    csvSeparator=","
elif [ "$csvSeparator" = "comma" ]; then
    csvSeparator=","
elif [ "$csvSeparator" = "semicolon" ]; then
    csvSeparator=";"
elif [ "$csvSeparator" != "comma" ] && [ "$csvSeparator" != "semicolon" ]; then
    echo "Error: the second argument - 'csvSeparator' is set to unsupported value '$csvSeparator'. Supported values are 'comma' or 'semicolon'."
    exit 1
fi

if [ -z "$reportFileName" ]; then
    echo "The third argument - 'reportFileName' is not set. The default value - upgradeAdvisorReport.csv will be used."
    reportFileName="upgradeAdvisorReport.csv"
fi

if [ -f "$reportFileName" ]; then
    rm -f "$reportFileName"
else
    touch "$reportFileName"
fi

echo "Start Upgrade Advisor"
mapfile -t exportFlow < <(find "$pathToExports" -type f -name "*.json" | sort)

echo "Flow name${csvSeparator}Level${csvSeparator}Issue${csvSeparator}Solution${csvSeparator}Required NiFi version for solution${csvSeparator}Processor${csvSeparator}Process Group Id" >"$reportFileName"

for flowName in "${exportFlow[@]}"; do
    shortFlowName="${flowName//$pathToExports/}"
    echo "Current flowName - $flowName, shortFlowName - $shortFlowName"

    echo "Checking for Deprecated Components in Exported Flow - $flowName"
    jq -r --arg flowName "${shortFlowName}" --arg csvSeparator "${csvSeparator}" --argjson depracatedList "$deprecatedComponents" 'walk(
        if type == "object" and has("type") and .type != null and $depracatedList[.type] != null
            then
                .checkSolution = $depracatedList[.type].solution |
                .checkIssue = $depracatedList[.type].issue |
                .checkLevel = $depracatedList[.type].level |
                .checkVersion = $depracatedList[.type].version?
            else .
        end
    ) | .. | objects | select(has("checkIssue")) |
    $flowName + $csvSeparator + .checkLevel + $csvSeparator + .checkIssue + $csvSeparator +
    "\"" + .checkSolution + "\"" + $csvSeparator +
    "\"" + .checkVersion + "\"" + $csvSeparator +
    "\"" + .name + " (" + .identifier + ")" + "\"" + $csvSeparator +
    "\"" + .groupIdentifier + "\"" ' "$flowName" >>"$reportFileName" || handle_error "Error while checking for Depracated Components in Exported Flow - $flowName"

    echo "Checking for deprecated Script Engines in ExecuteScript processors - $flowName"
    jq -r --arg flowName "${shortFlowName}" --arg csvSeparator "${csvSeparator}" 'walk(
        if type == "object" and has("type") and .type != null and .type == "org.apache.nifi.processors.script.ExecuteScript"
            then
                if .properties."Script Engine" == "ruby" or .properties."Script Engine" == "python" or .properties."Script Engine" == "lua"
                    then
                        .checkLevel = "Warning" |
                        .checkIssue = "The ExecuteScript processor has Script Engine = " + .properties."Script Engine" + " not supported in Apache NiFi 2.x." |
                        .checkSolution = "Update the flow to use Groovy Script Engine."
                    else
                        .
                end
            else .
        end
    ) | .. | objects | select(has("checkIssue"))  |
    $flowName + $csvSeparator + .checkLevel + $csvSeparator + .checkIssue + $csvSeparator +
    "\"" + .checkSolution + "\"" + $csvSeparator +
    "\"" + "\"" + $csvSeparator +
    "\"" + .name + " (" + .identifier + ")" + "\"" + $csvSeparator +
    "\"" + .groupIdentifier + "\"" ' "$flowName" >>"$reportFileName" || handle_error "Error while checking for deprecate Script Engine in ExecuteScript processors - $flowName"

    echo "Checking for Proxy properties in InvokeHTTP processor - $flowName"
    jq -r --arg flowName "${shortFlowName}" --arg csvSeparator "${csvSeparator}" 'walk(
    if type == "object" and .type != null and .type == "org.apache.nifi.processors.standard.InvokeHTTP"
        then
            if .properties | with_entries(select(.key | startswith("Proxy "))) | length > 0
                then
                    .checkLevel = "Warning" |
                    .checkIssue = "Proxy properties in InvokeHTTP processor with name - " + .name + " is not available in Apache NiFi 2.x." |
                    .checkVersion = "1.18.0" |
                    .checkSolution = "Update the flow to use Proxy Configuration Service property: 1) Create new Proxy Configuration Service; 2) Fill its properties based on the documentation and the properties from the InvokeHTTP processor."
                else
                    .
            end
        else .
    end) | .. | objects | select(has("checkIssue"))  |
    $flowName + $csvSeparator + .checkLevel + $csvSeparator + .checkIssue + $csvSeparator +
    "\"" + .checkSolution + "\"" + $csvSeparator +
    "\"" + .checkVersion + "\"" + $csvSeparator +
    "\"" + .name + " (" + .identifier + ")" + "\"" + $csvSeparator +
    "\"" + .groupIdentifier + "\"" ' "$flowName" >>"$reportFileName" || handle_error "Error while checking for Proxy properties in InvokeHTTP processor - $flowName"

    echo "Checking for Variables in Exported Flow - $flowName"
    jq -r --arg flowName "${shortFlowName}" --arg csvSeparator "${csvSeparator}" 'walk(
    if type == "object" and has("variables") and (.variables | type == "object") and (.variables | length > 0)
        then
            .checkLevel = "Warning" |
            .checkIssue = "Variables are not available in Apache NiFi 2.x." |
            .checkSolution = "1) Create a parameter context (or several contexts, if needed). 2) Assign the PC(s) to the necessary process groups. 3) Change references in properties from ${varName} to #{paramName}."
        else .
    end) | .. | objects | select(has("checkIssue"))  |
    $flowName + $csvSeparator + .checkLevel + $csvSeparator + .checkIssue + $csvSeparator +
    "\"" + .checkSolution + "\"" + $csvSeparator +
    "\"" + "\"" + $csvSeparator +
    "\"" + "\"" + $csvSeparator +
    "\"" + .name + " (" + .identifier + ")" + "\"" ' "$flowName" >>"$reportFileName" || handle_error "Error while checking for Variables in Exported Flow - $flowName"
done

echo "Checking the use of deprecated Reporting Task"
mapfile -t reportTaskTypes < <(echo "$deprecatedReportingTask" | jq -r 'keys[]')

for repTask in "${reportTaskTypes[@]}"; do
    foundFiles=$(grep -rlF "$repTask" "$pathToExports" 2>/dev/null)
    if [ -n "$foundFiles" ]; then
        shortfoundFiles="${foundFiles//$pathToExports/}"
        echo "$deprecatedReportingTask" | jq -r --arg repTask "$repTask" --arg fileName "${shortfoundFiles}" --arg csvSeparator "${csvSeparator}" '$fileName + $csvSeparator + .[$repTask].level + $csvSeparator + .[$repTask].issue + $csvSeparator +
        "\"" + .[$repTask].solution + "\"" + $csvSeparator +
        "\"" + .checkVersion + "\"" + $csvSeparator +
        "\"" + "\"" + $csvSeparator +
        "\"" + "\""' >>"$reportFileName" || handle_error "Error while forming message for reporting task - $repTask"
    fi
done

#output summary

for flowName in "${exportFlow[@]}"; do
    shortFlowName="${flowName//$pathToExports/}"
    if grep -q "$shortFlowName" "$reportFileName"; then
        echo "- $shortFlowName - Failed" >>./summary_flow.txt
    else
        echo "- $shortFlowName - Success" >>./summary_flow.txt
    fi
done

count=$(grep -c "Failed$" ./summary_flow.txt)

if [ "$count" -eq 0 ]; then
    echo "Overall result: Success."
    echo "All flows are compatible with 2.x, no changes needed."
else
    echo "Overall result: Failed."
    echo "$count flows are incompatible with Apache NiFi 2.x and need to be adapted before upgrade."
    cat ./summary_flow.txt
    echo ""
    echo "See report '$reportFileName' for more details."
fi

rm -f ./summary_flow.txt

echo "Finish Update Advisor"
