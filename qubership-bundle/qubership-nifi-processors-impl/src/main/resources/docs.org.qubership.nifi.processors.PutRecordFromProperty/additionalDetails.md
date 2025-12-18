# PutRecordFromProperty

The PutRecordFromProperty is a NiFi Processor that generate Records.
The processor generates a record based on its property and incoming flow files and sends them to the specified Record Destination Service (i.e., record sink).
The processor supports different configuration properties.

## Dynamic properties

When the `Source Type` property is set to `Dynamic Properties`, the processor utilizes Dynamic Properties as the data source for record generation.

During record generation, the processor maps each Dynamic Property’s name to the corresponding field name in the output record and uses its value as the field’s data.

The processor supports Dynamic Property values of the following types:

1. `Numeric`: If the Dynamic Property value is of type Numeric, it will be converted to double.
2. `String or char`: If the Dynamic Property value is of type String, it will be used as String.
3. `JSON`: If the Dynamic Property value is of type Json, it will be converted to Record.

If the value is a number, but needs to be used as a string, you need to surround it with quotation marks or use it in Json format.

The processor supports the JSON dynamic property format. In this case, the property name must be specified in the "List Json Dynamic Property" field.
The Record schema will be generated based on the JSON structure.
The JSON value must be a single, flat JSON object, where the attributes can either be scalar values or arrays of numeric values.

Example Dynamic Property with simple type:

| Dynamic Property Name | Dynamic Property Value |
|-----------------------|------------------------|
| request_duration_ms   | 100                    |
| request_method        | GET                    |
| request_url           | http://test.com        |


Example Json Dynamic Property:
```json
{
    "value": 1,
	"type": "Counter"
}
```

## Static Json

If the `Source Type` property has the value `Json Property`, then the Json from the `Json Property` property will be used to generate the record.

The JSON value in `Json Property` must be a single, flat JSON object, where the attributes can either be scalar values or arrays of numeric values.

Example Json for generating a Record in the case of Static Json:
```json
{
    "response_count": {
        "value": 1,
        "type": "Counter"
    },
    "request_duration_ms": ${flow.file.attribute.name},
    "request_url": "${invokehttp.request.url}",
    "request_status_code": "${invokehttp.status.code}"
}
```
