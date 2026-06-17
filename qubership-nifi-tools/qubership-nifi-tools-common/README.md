# qubership-nifi-tools-common

A reusable library that re-serializes a JSON file through Jackson while preserving the input file's
formatting. It is intended for workflows that parse a JSON file, modify some elements in the Jackson
tree, and write it back without producing noisy whitespace-only diffs.

A thin command-line wrapper is available in
[`qubership-nifi-tools-common-test-tool`](../qubership-nifi-tools-common-test-tool/README.md).

## How it works

1. `JsonFormatDetector` scans the raw input text and infers a single, global formatting style:
   indentation unit, line endings, object/array layout, colon and comma spacing, empty-container
   spacing, and whether the file ends with a newline. For each dimension the most frequent observed
   value wins ("dominant-wins").
2. `JsonFormatReformatter` parses the JSON into a Jackson tree, then writes it back with a
   `DefaultPrettyPrinter` configured by `JsonFormatPrettyPrinters` to reproduce the detected style.

## Library usage

```java
JsonFormatReformatter reformatter = new JsonFormatReformatter();

// Reproduce a file's own formatting after a parse/serialize round trip:
String reformatted = reformatter.reformat(originalJson);

// Keep the original formatting after modifying the tree:
JsonFormat format = reformatter.detect(originalJson);
ObjectNode tree = (ObjectNode) mapper.readTree(originalJson);
tree.put("field", "newValue");
String output = reformatter.write(tree, format);
```

## Limitations

- **Single global style.** One style is applied to the whole document. A file that deliberately
  mixes layouts per node — for example an expanded outer object whose inner objects are written
  inline on one line — cannot be reproduced exactly; detection picks the dominant style and applies
  it everywhere. Uniformly formatted files (such as Jackson-generated output) reproduce faithfully.
- **Scalars may be normalized.** Only structure and whitespace are guaranteed. Jackson may normalize
  scalar literals (for example `1.50` becomes `1.5`, and string escaping may change). Byte-for-byte
  round-trip therefore holds only when the input scalars are already in Jackson's canonical form.
- **Key order** is preserved (Jackson trees keep insertion order).
