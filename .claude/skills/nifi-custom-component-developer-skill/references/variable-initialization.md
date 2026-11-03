# Variable Initialization Scope

Where to initialize a processor's instance field depends on what its value
is derived from: processor configuration (safe to cache once) or FlowFile
data (must be recomputed for every FlowFile).

## Depends on processor configuration -> initialize in `@OnScheduled`

If a value depends only on a `PropertyDescriptor` value, `ProcessContext`,
or the NiFi Variable Registry - not on the FlowFile being processed -
evaluate it once in `@OnScheduled` and store it as an instance field.
`@OnScheduled` runs once per processor start, so the value cannot change
between FlowFiles; re-evaluating it in `onTrigger` is wasted work.

```java
@OnScheduled
public void onScheduled(final ProcessContext context) {
    prefixAttr = context.getProperty(PREFIX_ATTR).evaluateAttributeExpressions().getValue();
}
```

`evaluateAttributeExpressions()` called with no FlowFile argument only
resolves environment/variable-registry references - matching a
`PropertyDescriptor` declared with EL scope `NONE` or `ENVIRONMENT`.

## Depends on FlowFile attributes -> initialize in `onTrigger`

If a `PropertyDescriptor` is declared with EL scope `FLOWFILE_ATTRIBUTES`
(or the value otherwise depends on the specific FlowFile), it must be
evaluated per FlowFile, inside `onTrigger`, using
`evaluateAttributeExpressions(flowFile)`. Do not cache it as an instance
field: different FlowFiles can carry different values, and a processor
instance is shared across concurrent `onTrigger` calls (multiple threads),
so a flowfile-derived field is a race condition, not just wasted work.

```java
@Override
public void onTrigger(final ProcessContext context, final ProcessSession session) {
    FlowFile ff = session.get();
    if (ff == null) {
        return;
    }
    String sqlStatement = context.getProperty(SQL_STATEMENT).evaluateAttributeExpressions(ff).getValue();
}
```

## Controller Service -> initialize in `@OnEnabled`

A Controller Service has no `@OnScheduled` or `onTrigger`. It reads its
configuration once in `@OnEnabled` from a `ConfigurationContext`, and
FlowFile data arrives as parameters of each service method call, such as
the `context` map of `LookupService.lookup(...)`. The same split applies:
evaluate a configuration-only value in `@OnEnabled` and store it as a
field. For a property with EL scope `FLOWFILE_ATTRIBUTES`, store the
unevaluated `PropertyValue` in `@OnEnabled` and evaluate it in each call
with the attributes passed to that call. A service instance is shared by
every component that references it, so a value derived from one call's
attributes must stay a local variable.

```java
@OnEnabled
public void onEnabled(final ConfigurationContext context) {
    timeout = context.getProperty(TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS);
    urlTemplate = context.getProperty(URL);
}

@Override
public Optional<Record> lookup(final Map<String, Object> coordinates, final Map<String, String> context) {
    final String endpoint = urlTemplate.evaluateAttributeExpressions(context).getValue();
    // ...
}
```

## Rules

- EL scope `NONE` / `ENVIRONMENT`, or no EL support -> evaluate once in `@OnScheduled`, cache as an instance field.
- EL scope `FLOWFILE_ATTRIBUTES` -> evaluate per FlowFile in `onTrigger`, never cache.
- Never store a FlowFile-derived value in an instance field - concurrent `onTrigger` invocations share the same processor instance.
- Controller Service: evaluate configuration-only values once in `@OnEnabled`; for EL scope `FLOWFILE_ATTRIBUTES`, store the `PropertyValue` in `@OnEnabled` and evaluate it per call with the attributes passed to the service method, never caching the result.
- When unsure, check the property's `.expressionLanguageSupported(...)` declaration - it settles which case applies.
