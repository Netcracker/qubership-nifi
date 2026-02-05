# PutGeneratedRecord

The PutGeneratedRecord processor generates records from FlowFile attributes and configured properties, then sends them to a specified Record Destination Service (record sink).
Two modes of operation are supported for generating records: `Dynamic Properties` and `Json Property`.

## Dynamic Properties

When the `Source Type` property is set to `Dynamic Properties`, the processor uses Dynamic Properties as the data source for record generation.

During record generation, the processor maps each Dynamic Property name to a corresponding field name in the output record and uses its value as the field data.

The processor supports Dynamic Property values of the following types:

1. **Numeric**: Numeric values are converted to double.
2. **String**: String values are used as-is.
3. **JSON**: JSON values are converted to nested records. The record schema is generated based on the JSON structure.

Note: In `Dynamic Properties` mode, numeric values cannot be treated as strings. To use numeric values as strings, switch to `Json Property` mode instead.

The processor supports JSON-formatted Dynamic Properties. To enable this mode, specify the property names in the `List Json Dynamic Properties` field, separated by commas if multiple properties are needed.

When using JSON-formatted Dynamic Properties, the JSON value must be a single, flat JSON object with attributes that are either scalar values or arrays of numeric values.

Example Dynamic Properties:

| Dynamic Property Name | Dynamic Property Value           |
|-----------------------|----------------------------------|
| request_duration_ms   | 100                              |
| request_method        | GET                              |
| request_url           | `http://www.example.com/`        |
| request_count         | `{"value": 1,"type": "Counter"}` |

Note: `request_count` is an example of a JSON-formatted Dynamic Property. The processor converts it to a nested record with fields `value` (double) and `type` (string).


## `Json Property`

When the `Source Type` property is set to `Json Property`, the processor generates a record from the JSON defined in the `Json Property` property.

The JSON value in `Json Property` must be a single JSON object. Top-level attributes can be either scalar values or nested JSON objects. Nested objects can have attributes that are scalar values or arrays of numeric values.

Example JSON for generating a record using `Json Property` mode:
```json
{
    "response_count": {
        "value": 1,
        "type": "Counter"
    },
    "request_duration_ms": ${flow.file.attribute.name},
    "request_url": "${flow.file.attribute.url}",
    "request_status_code": "${flow.file.attribute.status.code}"
}
```
