# Naming Conventions

Naming rules for `PropertyDescriptor` and `Relationship` in qubership-nifi
custom components. 

## PropertyDescriptor

`.name(...)` is the property's stable internal identifier - lower-case
`kebab-case`, words separated by `-`. `.displayName(...)` is the
human-readable label shown in the NiFi UI - Title Case, words separated by
spaces. The Java constant holding the descriptor is `UPPER_SNAKE_CASE`, named
for what the property means (it does not have to be a literal transliteration
of `.name(...)`).

```java
public static final PropertyDescriptor EXAMPLE_PROPERTY = new PropertyDescriptor.Builder()
        .name("example-property-name")
        .displayName("Example Property Name")
        .description("What this property controls")
        .build();

public static final PropertyDescriptor MAX_BATCH_SIZE = new PropertyDescriptor.Builder()
        .name("max-batch-size")
        .displayName("Maximum Batch Size")
        .description("Maximum number of records to include into DB batch")
        .build();
```

One accepted exception: when a property mirrors a standard Apache NiFi
property (e.g. `DBCPService`), keep the exact upstream `.name(...)` value
(`"Database Connection Pooling Service"`) instead of inventing a kebab-case
one. This preserves configuration compatibility when swapping between
standard and qubership processors. Do not use this exception as a license to
name new, qubership-specific properties with spaces.

## Relationship

`.name(...)` is a single lower-case word - `success`, `failure`, `retry`,
`count`. The Java constant is `REL_<NAME>` in `UPPER_SNAKE_CASE`, where
`<NAME>` is the same word as `.name(...)`, upper-cased.

```java
public static final Relationship REL_SUCCESS = new Relationship.Builder()
        .name("success")
        .description("Successfully processed FlowFile")
        .build();

public static final Relationship REL_RETRY = new Relationship.Builder()
        .name("retry")
        .description("A FlowFile is routed to this relationship, if DB query failed with recoverable error")
        .build();
```

If a relationship genuinely needs more than one word, use `kebab-case`
(e.g. `partial-success`) rather than spaces or camelCase, to stay consistent
with the `PropertyDescriptor` `.name(...)` convention.

## Rules

- `PropertyDescriptor.name(...)`: lower-case `kebab-case`, words separated by `-`.
- `PropertyDescriptor.displayName(...)`: Title Case, words separated by spaces.
- `Relationship.name(...)`: a single lower-case word; use `kebab-case` only if a multi-word name is unavoidable - never spaces or camelCase.
- Java constants (`PropertyDescriptor`/`Relationship` fields) are `UPPER_SNAKE_CASE`; relationship constants follow `REL_<NAME>`.
- Reuse a standard Apache NiFi property's exact `.name(...)` only when wrapping/mirroring that same standard property (e.g. `DBCPService`) - never for new qubership-specific properties.
