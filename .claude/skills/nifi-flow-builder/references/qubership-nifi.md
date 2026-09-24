# qubership-nifi

The qubership-nifi image (`ghcr.io/netcracker/qubership-nifi`) is Apache NiFi with extra
bundles and extra files. `kb.py locate` prints `Platform : qubership-nifi` when the KB holds
`org.qubership.nifi` bundles; everything on this page applies only then.

## Library directories for scripts

The image links the bundled libraries of some NARs into fixed directories, so that a scripted
component can put them on its classpath. The links are created in the
[qubership-nifi Dockerfile](https://github.com/Netcracker/qubership-nifi/blob/main/Dockerfile),
in the block that creates `auxiliary-cp`.

| Directory | Libraries | Use it for |
| --- | --- | --- |
| `/opt/nifi/nifi-current/auxiliary-cp/nifi-poi-nar-cp` | Apache POI 5 (`poi`, `poi-ooxml`, `poi-ooxml-lite`), `xmlbeans`, `excel-streaming-reader`, `commons-collections4`, `commons-math3` | Writing or reading XLS and XLSX |

The directory is a symbolic link to the `bundled-dependencies` of `nifi-poi-nar` in NiFi's work
directory. Its jars therefore match the running NiFi version, and it exists once NiFi has
unpacked the NAR at startup. The jar names carry versions (`poi-5.5.1.jar` in NiFi 2.10.0), so
reference the directory or a `*.jar` mask, never a jar file.

The directory holds only the jars the NAR bundles itself. POI also needs `commons-io` and
`commons-compress`, which `nifi-poi-nar` gets from its parent, `nifi-standard-shared-nar`.
`nifi-scripting-nar` and `nifi-groovyx-nar` have the same parent, so every scripted component
in them loads POI from this directory. For a component of any other NAR, run it once on a
test NiFi before relying on the classes loading.

Set the directory on the component's classpath property. Check the key and the value format
with `kb.py props <Name>`, because they differ between the scripting NARs:

| NAR | Property | Value |
| --- | --- | --- |
| `nifi-scripting-nar` (`ExecuteScript`, `InvokeScriptedProcessor`, `ScriptedRecordSetWriter`, `ScriptedTransformRecord`, and the other scripted components) | `Module Directory` | `/opt/nifi/nifi-current/auxiliary-cp/nifi-poi-nar-cp` |
| `nifi-groovyx-nar` (`ExecuteGroovyScript`) | `Additional Classpath` | `/opt/nifi/nifi-current/auxiliary-cp/nifi-poi-nar-cp/*.jar` |

Each component instance loads its own copy of the jars. Prefer one shared record writer
service to several scripts that each load POI.

## XLSX record writer

NiFi has no XLSX record writer. This `ScriptedRecordSetWriter` writes the records of one
FlowFile as a single-sheet workbook: a header row with the field names of the record schema,
then one row per record. It sets `mime.type` through the processor that uses it. Pair it with
`QueryRecord` to choose and order the columns:
`SELECT item_id, item_name, item_status FROM FLOWFILE`.

`SXSSFWorkbook` keeps 100 rows in memory and moves the rest to a temporary file, so the size of
a report is not limited by the heap. Numbers are written as numeric cells, `Date` and
`Timestamp` values as date cells, and everything else as text, which POI escapes.

The service entry for `controllerServices`; the `Script Body` value is the script below, as
one JSON string:

```json
{
  "identifier": "<uuid>",
  "name": "XLSX report writer",
  "type": "org.apache.nifi.record.script.ScriptedRecordSetWriter",
  "bundle": { "group": "org.apache.nifi", "artifact": "nifi-scripting-nar", "version": "<KB NiFi version>" },
  "properties": {
    "Script Engine": "Groovy",
    "Script Body": "<the script below>",
    "Module Directory": "/opt/nifi/nifi-current/auxiliary-cp/nifi-poi-nar-cp"
  }
}
```

```groovy
import org.apache.nifi.controller.AbstractControllerService
import org.apache.nifi.logging.ComponentLog
import org.apache.nifi.serialization.RecordSetWriter
import org.apache.nifi.serialization.RecordSetWriterFactory
import org.apache.nifi.serialization.WriteResult
import org.apache.nifi.serialization.record.Record
import org.apache.nifi.serialization.record.RecordSchema
import org.apache.nifi.serialization.record.RecordSet
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.streaming.SXSSFWorkbook

// Writes the records of one FlowFile as a single-sheet XLSX workbook: a header row with the field
// names of the record schema, then one row per record. SXSSFWorkbook keeps 100 rows in memory and
// flushes the rest to a temporary file, which dispose() deletes.
class XlsxRecordSetWriter implements RecordSetWriter {
    private final RecordSchema schema
    private final OutputStream out
    private final SXSSFWorkbook workbook = new SXSSFWorkbook(100)
    private final CellStyle timestampStyle
    private final def sheet = workbook.createSheet('items')
    private int recordCount = 0
    private boolean written = false

    XlsxRecordSetWriter(RecordSchema schema, OutputStream out) {
        this.schema = schema
        this.out = out
        timestampStyle = workbook.createCellStyle()
        timestampStyle.dataFormat = workbook.creationHelper.createDataFormat().getFormat('yyyy-mm-dd hh:mm:ss')
        Row header = sheet.createRow(0)
        schema.fieldNames.eachWithIndex { String name, int i -> header.createCell(i).setCellValue(name) }
    }

    @Override
    void beginRecordSet() {
    }

    @Override
    WriteResult write(RecordSet recordSet) {
        Record record
        while ((record = recordSet.next()) != null) {
            write(record)
        }
        return finishRecordSet()
    }

    @Override
    WriteResult write(Record record) {
        Row row = sheet.createRow(++recordCount)
        schema.fieldNames.eachWithIndex { String name, int i ->
            def value = record.getValue(name)
            if (value == null) {
                return
            }
            def cell = row.createCell(i)
            if (value instanceof Number) {
                cell.setCellValue(((Number) value).doubleValue())
            } else if (value instanceof Boolean) {
                cell.setCellValue((Boolean) value)
            } else if (value instanceof Date) {
                cell.setCellValue((Date) value)
                cell.cellStyle = timestampStyle
            } else {
                cell.setCellValue(value.toString())
            }
        }
        return WriteResult.of(recordCount, [:])
    }

    @Override
    WriteResult finishRecordSet() {
        writeWorkbook()
        return WriteResult.of(recordCount, [:])
    }

    @Override
    String getMimeType() {
        return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    }

    @Override
    void flush() {
    }

    // The workbook is written once, on finishRecordSet() or on close() when the caller wrote
    // single records without finishing a record set.
    @Override
    void close() {
        try {
            writeWorkbook()
        } finally {
            workbook.dispose()
            workbook.close()
            out.close()
        }
    }

    private void writeWorkbook() {
        if (!written) {
            written = true
            workbook.write(out)
        }
    }
}

class XlsxRecordSetWriterFactory extends AbstractControllerService implements RecordSetWriterFactory {
    @Override
    RecordSchema getSchema(Map<String, String> variables, RecordSchema readSchema) {
        return readSchema
    }

    @Override
    RecordSetWriter createWriter(ComponentLog logger, RecordSchema schema, OutputStream out,
                                 Map<String, String> variables) {
        return new XlsxRecordSetWriter(schema, out)
    }
}

writer = new XlsxRecordSetWriterFactory()
```

`ScriptedRecordSetWriter` evaluates its script when the service is enabled. `verify_live.py`
imports every service disabled, so its check never runs the script and cannot show that the
classes load. To check that, enable the service on a test NiFi and run one FlowFile through
it.
