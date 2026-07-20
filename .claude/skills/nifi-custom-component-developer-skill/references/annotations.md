# Class-Level Annotations

Order and choice of annotations placed directly above a NiFi component class.

## Processors

Stack annotations in this order, immediately above `public class`:

1. Behavior/scheduling annotations, if applicable: `@SideEffectFree`, `@SupportsBatching`.
2. `@InputRequirement(InputRequirement.Requirement.X)` - always declare it explicitly, matching the FlowFile-check pattern used in `onTrigger` (see check-flowfile.md). Never leave it out and rely on the framework default.
3. `@Tags({...})`, then `@CapabilityDescription("...")`.
4. Attribute-contract annotations last: `@WritesAttributes({...})`, `@DynamicProperties(...)`. List only the attributes this component itself adds or overwrites on the FlowFile - not attributes it merely reads, passes through unchanged, or that come from a parent/base class.

```java
@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"Attribute", "BackupAttributes"})
@CapabilityDescription("Backups all FlowFile attributes by adding prefix to their names.")
public class BackupAttributes extends AbstractProcessor {
```

## Controller Services

No behavior/scheduling annotations apply. Order is `@Tags` then `@CapabilityDescription`:

```java
@Tags({"properties"})
@CapabilityDescription("Provides a prepared statement service.")
public class OraclePreparedStatementWithArrayProvider extends AbstractPreparedStatementProvider {
```

## Rules

- Order: behavior annotations (`@SideEffectFree`, `@SupportsBatching`, `@InputRequirement`) -> `@Tags` -> `@CapabilityDescription` -> attribute-contract annotations (`@WritesAttributes`, `@DynamicProperties`).
- Always declare `@InputRequirement` explicitly on processors that call `session.get()` - do not rely on the framework's implicit default.
- `@Tags` always comes before `@CapabilityDescription`, never after.
- Controller services only need `@Tags` and `@CapabilityDescription`, in that order.
- `@WritesAttributes` must list exactly the attributes this component adds/overwrites - no attributes from a parent class, no attributes it only reads.
