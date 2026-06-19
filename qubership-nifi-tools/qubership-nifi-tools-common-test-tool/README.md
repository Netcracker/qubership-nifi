# qubership-nifi-tools-common-test-tool

A small command-line wrapper around
[`qubership-nifi-tools-common`](../qubership-nifi-tools-common/README.md). It reads an input
JSON file, detects its formatting, parses it with Jackson, and writes an equivalently formatted copy
to the output path.

## Usage

The tool takes two positional arguments: the input and output file paths.

```bash
mvn -pl qubership-nifi-tools/qubership-nifi-tools-common-test-tool exec:java \
    -Dexec.args="path/to/input.json path/to/output.json"
```

For the library API, the detection model, and the known limitations, see the
[library README.md](../qubership-nifi-tools-common/README.md).
