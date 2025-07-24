#!/bin/bash

# shellcheck source=/dev/null
# shellcheck disable=SC2089

handle_error() {
    echo "$1" >&2
    exit 1
}

deprecatedComponents='{
    "org.apache.nifi.rules.handlers.ActionHandlerLookup": "Error: The ActionHandlerLookup Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.rules.handlers.AlertHandler": "Error: The AlertHandler Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.Base64EncodeContent": "Warning: The Base64EncodeContent Processor is not available in Apache NiFi 2.x. You should use EncodeContent instead.",
    "org.apache.nifi.service.CassandraSessionProvider": "Error: The CassandraSessionProvider Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.cybersecurity.CompareFuzzyHash": "Error: The CompareFuzzyHash Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.email.ConsumeEWS": "Error: The ConsumeEWS Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.avro.ConvertAvroToJSON": "Warning: The ConvertAvroToJSON Processor is not available in Apache NiFi 2.x. You should use ConvertRecord instead.",
    "org.apache.nifi.processors.poi.ConvertExcelToCSVProcessor": "Warning: The ConvertExcelToCSVProcessor Processor is not available in Apache NiFi 2.x. You should use ExcelReader instead.",
    "org.apache.nifi.couchbase.CouchbaseClusterService": "Error: The CouchbaseClusterService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.couchbase.CouchbaseKeyValueLookupService": "Error: The CouchbaseKeyValueLookupService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.couchbase.CouchbaseMapCacheClient": "Error: The CouchbaseMapCacheClient Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.couchbase.CouchbaseRecordLookupService": "Error: The CouchbaseRecordLookupService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.CryptographicHashAttribute": "Warning: The CryptographicHashAttribute Processor is not available in Apache NiFi 2.x. You should use UpdateAttribute with expression language hash() function instead.",
    "org.apache.nifi.processors.cipher.DecryptContent": "Error: The DecryptContent Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.cipher.DecryptContentCompatibility": "Error: The DecryptContentCompatibility Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.DeleteHBaseCells": "Error: The DeleteHBaseCells Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.DeleteHBaseRow": "Error: The DeleteHBaseRow Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.rethinkdb.DeleteRethinkDB": "Error: The DeleteRethinkDB Processor is not available in Apache NiFi 2.x.",
    "org.qubership.nifi.service.RedisBulkDistributedMapCacheClientService": "Warning: The DistributedMapCacheClientService Controller Service is not available in Apache NiFi 2.x. You should use MapCacheClientService instead.",
    "org.apache.nifi.distributed.cache.server.map.DistributedMapCacheServer": "Warning: The DistributedMapCacheServer Controller Service is not available in Apache NiFi 2.x. You should use MapCacheServer instead.",
    "org.apache.nifi.processors.standard.EncryptContent": "Warning: The EncryptContent Processor is not available in Apache NiFi 2.x. You should use EncryptContentAge or EncryptContentPGP instead.",
    "org.apache.nifi.processors.flume.ExecuteFlumeSink": "Error: The ExecuteFlumeSink Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.flume.ExecuteFlumeSource": "Error: The ExecuteFlumeSource Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.influxdb.ExecuteInfluxDBQuery": "Warning: The ExecuteInfluxDBQuery Processor is not available in Apache NiFi 2.x. You should use InfluxData nifi-influxdb-bundle instead.",
    "org.apache.nifi.rules.handlers.ExpressionHandler": "Error: The ExpressionHandler Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.ccda.ExtractCCDAAttributes": "Error: The ExtractCCDAAttributes Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.email.ExtractTNEFAttachments": "Error: The ExtractTNEFAttachments Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.elasticsearch.FetchElasticsearchHttp": "Warning: The FetchElasticsearchHttp Processor is not available in Apache NiFi 2.x. You should use GetElasticsearch instead.",
    "org.apache.nifi.hbase.FetchHBaseRow": "Error: The FetchHBaseRow Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.cybersecurity.FuzzyHashContent": "Error: The FuzzyHashContent Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.azure.storage.queue.GetAzureQueueStorage": "Warning: The GetAzureQueueStorage Processor is not available in Apache NiFi 2.x. You should use GetAzureQueueStorage_v12 instead.",
    "org.apache.nifi.processors.couchbase.GetCouchbaseKey": "Error: The GetCouchbaseKey Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.GetHBase": "Error: The GetHBaseRow Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.GetHTMLElement": "Error: The GetHTMLElement Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.GetHTTP": "Warning: The GetHTTP Processor is not available in Apache NiFi 2.x. You should use InvokeHTTP instead.",
    "org.apache.nifi.processors.ignite.cache.GetIgniteCache": "Error: The GetIgniteCache Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.GetJMSQueue": "Warning: The GetJMSQueue Processor is not available in Apache NiFi 2.x. You should use ConsumeJMS instead.",
    "org.apache.nifi.processors.standard.GetJMSTopic": "Warning: The GetJMSTopic Processor is not available in Apache NiFi 2.x. You should use ConsumeJMS instead.",
    "org.apache.nifi.processors.rethinkdb.GetRethinkDB": "Error: The GetRethinkDB Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.solr.GetSolr": "Error: The GetSolr Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.gettcp.GetTCP": "Error: The GetTCP Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.twitter.GetTwitter": "Warning: The GetTwitter Processor is not available in Apache NiFi 2.x. You should use ConsumeTwitter instead.",
    "org.apache.nifi.metrics.reporting.reporter.service.GraphiteMetricReporterService": "Error: The GraphiteMetricReporterService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.azure.storage.DeleteAzureBlobStorage": "Warning: The DeleteAzureBlobStorage Processor is not available in Apache NiFi 2.x. You should use DeleteAzureBlobStorage_v12 instead.",
    "org.apache.nifi.processors.livy.ExecuteSparkInteractive": "Error: The ExecuteSparkInteractive Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.azure.storage.FetchAzureBlobStorage": "Warning: The FetchAzureBlobStorage Processor is not available in Apache NiFi 2.x. You should use FetchAzureBlobStorage_v12 instead.",
    "org.apache.nifi.services.iceberg.HadoopCatalogService": "Error: The HadoopCatalogService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.services.iceberg.HiveCatalogService": "Error: The HiveCatalogService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.dbcp.hive.HiveConnectionPool": "Error: The HiveConnectionPool Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.dbcp.hive.Hive_1_1ConnectionPool": "Error: The Hive_1_1ConnectionPool Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.dbcp.hive.Hive3ConnectionPool": "Error: The Hive3ConnectionPool Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.HashAttribute": "Warning: The HashAttribute Processor is not available in Apache NiFi 2.x. You should use CryptographicHashAttribute instead.",
    "org.apache.nifi.processors.standard.HashContent": "Warning: The HashContent Processor is not available in Apache NiFi 2.x. You should use CryptographicHashContent instead.",
    "org.apache.nifi.schemaregistry.hortonworks.HortonworksSchemaRegistry": "Error: The HortonworksSchemaRegistry Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.HBase_2_ClientMapCacheService": "Error: The HBase_2_ClientMapCacheService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.HBase_2_ClientService": "Error: The HBase_2_ClientService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.HBase_2_LIstLookupService": "Error: The HBase_2_LIstLookupService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.HBase_2_RecordLookupService": "Error: The HBase_2_RecordLookupService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.ListHBaseRegions": "Error: The ListHBaseRegions Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.aws.wag.InvokeAWSGatewayApi": "Error: The InvokeAWSGatewayApi Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.grpc.InvokeGRPC": "Error: The InvokeGRPC Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.services.iceberg.JdbcCatalogService": "Error: The JdbcCatalogService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.controller.kudu.KuduLookupService": "Error: The KuduLookupService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.beats.ListenBeats": "Error: The ListenBeats Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.grpc.ListenGRPC": "Error: The ListenGRPC Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.ListenRELP": "Warning: The ListenRELP Processor is not available in Apache NiFi 2.x. You should use ListenOTLP instead.",
    "org.apache.nifi.processors.email.ListenSMTP": "Error: The ListenSMTP Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.ListenTCPRecord": "Warning: The ListenTCPRecord Processor is not available in Apache NiFi 2.x. You should use ListenTCP instead.",
    "org.apache.nifi.processors.azure.storage.ListAzureBlobStorage": "Warning: The ListAzureBlobStorage Processor is not available in Apache NiFi 2.x. You should use ListAzureBlobStorage_v12 instead.",
    "org.apache.nifi.controller.livy.LivySessionController": "Error: The LivySessionController Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.rules.handlers.LogHandler": "Error: The LogHandler Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.ModifyHTMLElement": "Error: The ModifyHTMLElement Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.graph.Neo4JCypher3ClientService": "Warning: The Neo4JCypher3ClientService Controller Service is not available in Apache NiFi 2.x. You should use Neo4JCypherClientService instead.",
    "org.apache.nifi.oauth2.OAuth2TokenProviderImpl": "Warning: The OAuth2TokenProviderImpl Controller Service is not available in Apache NiFi 2.x. You should use StandardOauth2AccessTokenProvider instead.",
    "org.apache.nifi.graph.OpenCypherClientService": "Error: The OpenCypherClientService Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.ParseCEF": "Warning: The ParseCEF Processor is not available in Apache NiFi 2.x. You should use CEFReader instead.",
    "org.apache.nifi.processors.standard.PostHTTP": "Warning: The PostHTTP Processor is not available in Apache NiFi 2.x. You should use InvokeHTTP instead.",
    "org.apache.nifi.processors.slack.PostSlack": "Warning: The PostSlack Processor is not available in Apache NiFi 2.x. You should use PublishSlack instead.",
    "org.apache.nifi.processors.cassandra.PutCassandraQL": "Error: The PutCassandraQL Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.cassandra.PutCassandraRecord": "Error: The PutCassandraRecord Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.couchbase.PutCouchbaseKey": "Error: The PutCouchbaseKey Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.azure.storage.PutAzureBlobStorage": "Warning: The PutAzureBlobStorage Processor is not available in Apache NiFi 2.x. You should use PutAzureBlobStorage_v12 instead.",
    "org.apache.nifi.processors.azure.storage.queue.PutAzureQueueStorage": "Warning: The PutAzureQueueStorage Processor is not available in Apache NiFi 2.x. You should use PutAzureQueueStorage_v12 instead.",
    "org.apache.nifi.processors.gcp.bigquery.PutBigQueryBatch": "Warning: The PutBigQueryBatch Processor is not available in Apache NiFi 2.x. You should use PutBigQuery instead.",
    "org.apache.nifi.processors.gcp.bigquery.PutBigQueryStreaming": "Warning: The PutBigQueryStreaming Processor is not available in Apache NiFi 2.x. You should use PutBigQuery instead.",
    "org.apache.nifi.processors.elasticsearch.PutElasticsearchHttp": "Warning: The PutElasticsearchHttp Processor is not available in Apache NiFi 2.x. You should use PutElasticsearchJson instead.",
    "org.apache.nifi.processors.elasticsearch.PutElasticsearchHttpRecord": "Warning: The PutElasticsearchHttpRecord Processor is not available in Apache NiFi 2.x. You should use PutElasticsearchRecord instead.",
    "org.apache.nifi.hbase.PutHBaseCell": "Error: The PutHBaseCell Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.PutHBaseJSON": "Error: The PutHBaseJSON Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.PutHBaseRecord": "Error: The PutHBaseRecord Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.hive.PutHiveQL": "Warning: The PutHiveQL Processor is not available in Apache NiFi 2.x. You should use PutHive3QL instead.",
    "org.apache.nifi.processors.hive.PutHive_1_1QL": "Warning: The PutHive_1_1QL Processor is not available in Apache NiFi 2.x. You should use PutHive3QL instead.",
    "org.apache.nifi.processors.hive.PutHiveStreaming": "Warning: The PutHiveStreaming Processor is not available in Apache NiFi 2.x. You should use PutHive3Streaming instead.",
    "org.apache.nifi.processors.hive.PutHive3QL": "Error: The PutHive3QL Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.hive.PutHive3Streaming": "Error: The PutHive3Streaming Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.PutHTMLElement": "Error: The PutHTMLElement Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.iceberg.PutIceberg": "Error: The PutIceberg Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.ignite.cache.PutIgniteCache": "Error: The PutIgniteCache Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.influxdb.PutInfluxDB": "Warning: The PutInfluxDB Processor is not available in Apache NiFi 2.x. You should use InfluxData nifi-influxdb-bundle instead.",
    "org.apache.nifi.processors.standard.PutJMS": "Warning: The PutJMS Processor is not available in Apache NiFi 2.x. You should use PublishJMS instead.",
    "org.apache.nifi.processors.kudu.PutKudu": "Error: The PutKudu Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.orc.PutORC": "Error: The PutORC Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.rethinkdb.PutRethinkDB": "Error: The PutRethinkDB Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.riemann.PutRiemann": "Error: The PutRiemann Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.slack.PutSlack": "Warning: The PutSlack Processor is not available in Apache NiFi 2.x. You should use PublishSlack instead.",
    "org.apache.nifi.processors.solr.PutSolrContentStream": "Error: The PutSolrContentStream Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.solr.PutSolrRecord": "Error: The PutSolrRecord Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.cassandra.QueryCassandra": "Error: The QueryCassandra Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.enrich.QueryDNS": "Error: The QueryDNS Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.elasticsearch.QueryElasticsearchHttp": "Warning: The QueryElasticsearchHttp Processor is not available in Apache NiFi 2.x. You should use PaginatedJsonQueryElasticsearch instead.",
    "org.apache.nifi.processors.solr.QuerySolr": "Error: The QuerySolr Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.enrich.QueryWhois": "Error: The QueryWhois Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.rules.handlers.RecordSinkHandler": "Error: The RecordSinkHandler Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.hbase.ScanHBase": "Error: The ScanHBase Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.elasticsearch.ScrollElasticsearchHttp": "Warning: The ScrollElasticsearchHttp Processor is not available in Apache NiFi 2.x. You should use SearchElasticsearch instead.",
    "org.apache.nifi.processors.hive.SelectHiveQL": "Warning: The SelectHiveQL Processor is not available in Apache NiFi 2.x. You should use SelectHive3QL instead.",
    "org.apache.nifi.processors.hive.SelectHive_1_1QL": "Warning: The SelectHive_1_1QL Processor is not available in Apache NiFi 2.x. You should use SelectHive3QL instead.",
    "org.apache.nifi.processors.hive.SelectHive3QL": "Error: The SelectHive3QL Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.spring.SpringContextProcessor": "Error: The SpringContextProcessor Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.hive.TriggerHiveMetaStoreEvent": "Error: The TriggerHiveMetaStoreEvent Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.hive.UpdateHiveTable": "Warning: The UpdateHiveTable Processor is not available in Apache NiFi 2.x. You should use UpdateHive3Table instead.",
    "org.apache.nifi.processors.hive.UpdateHive_1_1Table": "Warning: The UpdateHive_1_1Table Processor is not available in Apache NiFi 2.x. You should use UpdateHive3Table instead.",
    "org.apache.nifi.processors.hive.UpdateHive3Table": "Error: The UpdateHive3Table Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.yandex.YandexTranslate": "Error: The YandexTranslate Processor is not available in Apache NiFi 2.x.",
    "org.apache.nifi.processors.standard.ConvertJSONToSQL": "Warning: The ConvertJSONToSql Processor is not available in Apache NiFi 2.x. You should use RecordReader + PutDatabaseRecord or PutSQLRecord instead.",
    "org.apache.nifi.reporting.prometheus.PrometheusRecordSink": "Warning: The PrometheusRecordSink Controller Service is not available in Apache NiFi 2.x. You should use QubershipPrometheusRecordSink instead.",
    "org.apache.nifi.processors.stateless.ExecuteStateless": "Error: The ExecuteStateless Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use ExecuteStateless and in case of Apache NiFi 1.x. you need to use Stateless Process Groups.",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_1_0": "Error: The ConsumeKafka_1_0 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use ConsumeKafka_1_0 and in case of Apache NiFi 1.x. you need to use ConsumeKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_2_0": "Error: The ConsumeKafka_2_0 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use ConsumeKafka_2_0 and in case of Apache NiFi 1.x. you need to use ConsumeKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_2_6": "Error: The ConsumeKafka_2_6 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use ConsumeKafka_2_6 and in case of Apache NiFi 1.x. you need to use ConsumeKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_1_0": "Error: The ConsumeKafkaRecord_1_0 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use ConsumeKafkaRecord_1_0 and in case of Apache NiFi 1.x. you need to use ConsumeKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_2_0": "Error: The ConsumeKafkaRecord_2_0 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use ConsumeKafkaRecord_2_0 and in case of Apache NiFi 1.x. you need to use ConsumeKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_2_6": "Error: The ConsumeKafkaRecord_2_6 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use ConsumeKafkaRecord_2_6 and in case of Apache NiFi 1.x. you need to use ConsumeKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.PublishKafka_1_0": "Error: The PublishKafka_1_0 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use PublishKafka_1_0 and in case of Apache NiFi 1.x. you need to use PublishKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.PublishKafka_2_0": "Error: The PublishKafka_2_0 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use PublishKafka_2_0 and in case of Apache NiFi 1.x. you need to use PublishKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.PublishKafka_2_6": "Error: The PublishKafka_2_6 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use PublishKafka_2_6 and in case of Apache NiFi 1.x. you need to use PublishKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.PublishKafkaRecord_1_0": "Error: The PublishKafkaRecord_1_0 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use PublishKafkaRecord_1_0 and in case of Apache NiFi 1.x. you need to use PublishKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.PublishKafkaRecord_2_0": "Error: The PublishKafkaRecord_2_0 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use PublishKafkaRecord_2_0 and in case of Apache NiFi 1.x. you need to use PublishKafka + Kafka3ConnectionService.",
    "org.apache.nifi.processors.kafka.pubsub.PublishKafkaRecord_2_6": "Error: The PublishKafkaRecord_2_6 Processor is not available in Apache NiFi 2.x. You need to have 2 versions of flow. In case of Apache NiFi 1.x. you can use PublishKafkaRecord_2_6 and in case of Apache NiFi 1.x. you need to use PublishKafka + Kafka3ConnectionService.",
    "org.apache.nifi.record.sink.kafka.KafkaRecordSink_1_0": "Error: The KafkaRecordSink_1_0 Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.record.sink.kafka.KafkaRecordSink_2_0": "Error: The KafkaRecordSink_2_0 Controller Service is not available in Apache NiFi 2.x.",
    "org.apache.nifi.record.sink.kafka.KafkaRecordSink_2_6": "Error: The KafkaRecordSink_2_6 Controller Service is not available in Apache NiFi 2.x."
}'

deprecatedReportingTask='{
    "org.apache.nifi.reporting.ganglia.StandardGangliaReporter": "Warning: The StandardGangliaReporter Reporting Task is not available in Apache NiFi 2.x. You should use REST API Metrics instead.",
    "org.apache.nifi.atlas.reporting.ReportLineageToAtlas": "Error: The ReportLineageToAtlas Reporting Task is not available in Apache NiFi 2.x.",
    "org.apache.nifi.metrics.reporting.task.MetricsReportingTask": "Error: The MetricsReportingTask Reporting Task is not available in Apache NiFi 2.x.",
    "org.apache.nifi.reporting.ambari.AmbariReportingTask": "Error: The AmbariReportingTask Reporting Task is not available in Apache NiFi 2.x.",
    "org.apache.nifi.reporting.datadog.DatadogReportingTask": "Error: The DatadogReportingTask Reporting Task is not available in Apache NiFi 2.x.",
    "org.apache.nifi.reporting.prometheus.PrometheusReportingTask": "Warning: The PrometheusReportingTask Reporting Task is not available in Apache NiFi 2.x. You should use REST API Metrics instead."
}'

pathToExports=$1

#declare array
declare -a exportFlow

if [ -z "$pathToExports" ]; then
    echo "The first argument - 'pathToExports' is not set. The default value - '.' will be set."
    pathToExports="."
fi

if [ -f "./upgradeAdvisorReport.txt" ]; then
    rm -f ./upgradeAdvisorReport.txt
else
    touch ./upgradeAdvisorReport.txt
fi

echo "Start Upgrade Advisor"

mapfile -t exportFlow < <(find "$pathToExports" -type f -name "*.json" | sort)

for flowName in "${exportFlow[@]}"; do

    echo "Current flowName - $flowName"
    echo "$flowName:" >>./upgradeAdvisorReport.txt

    echo "Checking for Deprecated Components in Exported Flow - $flowName"
    jq -r --argjson depracatedList "$deprecatedComponents" 'walk(
        if type == "object" and has("type") and .type != null and $depracatedList[.type] != null
            then
                .checkMessage = $depracatedList[.type]
            else .
        end
    ) | .. | objects | select(has("checkMessage")) | .checkMessage ' "$flowName" >>./upgradeAdvisorReport.txt || handle_error "Error while checking for Depracated Components in Exported Flow - $flowName"

    echo "Checking for deprecated Script Engines in ExecuteScript processors - $flowName"
    jq -r 'walk(
        if type == "object" and has("type") and .type != null and .type == "org.apache.nifi.processors.script.ExecuteScript"
            then
                if .properties."Script Engine" == "ruby" or .properties."Script Engine" == "python" or .properties."Script Engine" == "lua"
                    then
                        .checkMessage = "Warning: The ExecuteScript processor with name - " + .name + " has incorrect Script Engine. You should use Groovy Script Engine or Native Python in 2.0.0 instead."
                    else
                        .
                end
            else .
        end
    ) | .. | objects | select(has("checkMessage")) | .checkMessage ' "$flowName" >>./upgradeAdvisorReport.txt || handle_error "Error while checking for deprecate Script Engine in ExecuteScript processors - $flowName"

    echo "Checking for Proxy properties in InvokeHTTP processor - $flowName"
    jq -r 'walk(
    if type == "object" and .type != null and .type == "org.apache.nifi.processors.standard.InvokeHTTP"
        then
            if .properties | with_entries(select(.key | startswith("Proxy"))) | length > 0
                then
                    .checkMessage = "Warning: Proxy properties in processor with name - " + .name + " is not available in Apache NiFi 2.x. You should use Proxy Configuration Service instead."
                else
                    .
            end
        else .
    end) | .. | objects | select(has("checkMessage")) | .checkMessage ' "$flowName" >>./upgradeAdvisorReport.txt || handle_error "Error while checking for Proxy properties in InvokeHTTP processor - $flowName"

    echo "Checking for Variables in Exported Flow - $flowName"
    jq -r 'walk(
    if type == "object" and has("variables") and (.variables | type == "object") and (.variables | length > 0)
        then
            .checkMessage = "Warning: Variables in process group with name - " + .name + " is not available in Apache NiFi 2.x. You should use Parameter Contexts instead."
        else .
    end) | .. | objects | select(has("checkMessage")) | .checkMessage' "$flowName" >>./upgradeAdvisorReport.txt || handle_error "Error while checking for Variables in Exported Flow - $flowName"
done

echo "Checking the use of deprecated Reporting Task"
mapfile -t reportTaskTypes < <(echo "$deprecatedReportingTask" | jq -r 'keys[]')

for repTask in "${reportTaskTypes[@]}"; do
    if grep -rqF "$repTask" "$pathToExports"; then
        echo "$deprecatedReportingTask" | jq -r --arg repTask "$repTask" '.[$repTask]' >>./upgradeAdvisorReport.txt || handle_error "Error while forming message for reporting task - $repTask"
    fi
done

echo "Finish Update Advisor"
