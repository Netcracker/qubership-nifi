package org.qubership.nifi.service;

import org.apache.nifi.components.AbstractConfigurableComponent;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.record.sink.RecordSinkService;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockRecordSinkService extends AbstractControllerService implements RecordSinkService {

    private List<Map<String, Object>> rows = new ArrayList<>();

    @Override
    public WriteResult sendData(RecordSet recordSet, Map<String, String> attributes, boolean sendZeroResults) throws IOException {
        rows = new ArrayList<>();
        int numRecordsWritten = 0;
        RecordSchema recordSchema = recordSet.getSchema();
        Record record;
        while ((record = recordSet.next()) != null) {
            Map<String, Object> row = new HashMap<>();
            final Record finalRecord = record;
            recordSchema.getFieldNames().forEach((fieldName) -> row.put(fieldName, finalRecord.getValue(fieldName)));
            rows.add(row);
            numRecordsWritten++;
        }

        return WriteResult.of(numRecordsWritten, Collections.emptyMap());
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }
}
