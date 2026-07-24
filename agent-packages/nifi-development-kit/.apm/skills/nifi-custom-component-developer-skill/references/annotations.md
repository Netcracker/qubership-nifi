# Class-Level Annotations

Order and choice of annotations placed directly above a NiFi component class.

## Processors

These annotations are commonly stacked immediately above `public class`, in
this order:

1. Behavior/scheduling annotations, if applicable: `@SideEffectFree`, `@SupportsBatching`.
2. `@InputRequirement(InputRequirement.Requirement.X)` - always declare it explicitly, matching the FlowFile-check pattern used in `onTrigger` (see check-flowfile.md). Never leave it out and rely on the framework default.
3. `@Tags({...})`, then `@CapabilityDescription("...")`.
4. Attribute-contract annotations last: `@ReadsAttributes({...})`, `@WritesAttributes({...})`, `@DynamicProperties(...)`.
   - `@WritesAttributes` must list only the attributes this component itself adds or overwrites on the FlowFile - not attributes it merely reads, passes through unchanged, or that come from a parent/base class.
   - `@ReadsAttributes` documents the FlowFile attributes the component reads and depends on for its own logic - not attributes it just passes through untouched.

`@CapabilityDescription` is a short, human-readable summary of what the
component does - it is what shows up next to the component in the NiFi UI's
"Add Processor" dialog, so it should describe the functionality in a way
someone browsing the component list can act on. `@Tags` is an array of
keywords associated with that functionality (protocol names, target
systems, the kind of operation performed, etc.) - the NiFi UI lets users
search/filter components by tag, so tags should reflect what someone would
actually search for.

```java
@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"Attribute", "BackupAttributes"})
@CapabilityDescription("Backups all FlowFile attributes by adding prefix to their names.")
public class BackupAttributes extends AbstractProcessor {
```

## Controller Services

No behavior/scheduling annotations apply. `@Tags` then `@CapabilityDescription`:

```java
@Tags({"properties"})
@CapabilityDescription("Provides a prepared statement service.")
public class OraclePreparedStatementWithArrayProvider extends AbstractPreparedStatementProvider {
```

## Reporting Tasks

Same as Controller Services: no behavior/scheduling or `@InputRequirement`
annotations apply, since a Reporting Task does not process FlowFiles.
`@Tags` then `@CapabilityDescription`:

```java
@Tags({"metrics", "monitoring"})
@CapabilityDescription("Reports component metrics to an external monitoring system.")
public class ExampleMetricsReportingTask extends AbstractReportingTask {
```

## Rules

- Order: behavior annotations (`@SideEffectFree`, `@SupportsBatching`, `@InputRequirement`) -> `@Tags` -> `@CapabilityDescription` -> attribute-contract annotations (`@ReadsAttributes`, `@WritesAttributes`, `@DynamicProperties`).
- This order is a readability/style convention for consistency across components - annotation order has no effect on NiFi's runtime behavior, so treat it as a coding-style rule rather than a correctness issue.
- Always declare `@InputRequirement` explicitly on processors that call `session.get()` - do not rely on the framework's implicit default.
- `@CapabilityDescription` is a concise summary of what the component does, shown in the NiFi UI; `@Tags` is a set of searchable keywords for the component's functionality.
- Controller services and Reporting Tasks only need `@Tags` and `@CapabilityDescription` - no behavior or `@InputRequirement` annotations, since neither processes FlowFiles directly.
- `@WritesAttributes` must list exactly the attributes this component adds/overwrites - no attributes from a parent class, no attributes it only reads. `@ReadsAttributes` lists attributes the component reads and depends on, not ones it merely passes through.
