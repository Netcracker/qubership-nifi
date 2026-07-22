# Naming Conventions

Naming rules for `PropertyDescriptor` and `Relationship` in custom Apache
NiFi components. 

## PropertyDescriptor

`.name(...)` is the property's API name - lower-case
`kebab-case`, words separated by `-`. `.displayName(...)` is the
human-readable label shown in the NiFi UI - Title Case, words separated by
spaces. `.description(...)` must always be set - it is the explanatory text
shown in the NiFi UI next to the property. The Java constant holding the
descriptor is `UPPER_SNAKE_CASE`, named for what the property means (it does
not have to be a literal transliteration of `.name(...)`).

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

## Relationship

`.name(...)` is a single lower-case word - `success`, `failure`, `retry`,
`count`. `.description(...)` must always be set - it should say when/why a
FlowFile is routed to this relationship. The Java constant is `REL_<NAME>`
in `UPPER_SNAKE_CASE`, where `<NAME>` is the same word as `.name(...)`,
upper-cased.

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
- `PropertyDescriptor.description(...)`: always set, explaining what the property controls.
- `Relationship.name(...)`: a single lower-case word; use `kebab-case` only if a multi-word name is unavoidable - never spaces or camelCase.
- `Relationship.description(...)`: always set, explaining when/why a FlowFile is routed there.
- Java constants (`PropertyDescriptor`/`Relationship` fields) are `UPPER_SNAKE_CASE`; relationship constants follow `REL_<NAME>`.
