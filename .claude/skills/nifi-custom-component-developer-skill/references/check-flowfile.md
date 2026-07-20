# Check FlowFile

Pattern for validating that a FlowFile is present before a processor does any
work in `onTrigger`.

## Required input (`INPUT_REQUIRED`)

If the component declares `@InputRequirement(Requirement.INPUT_REQUIRED)`,
get the FlowFile and bail out immediately if it is missing, before any other
work happens:

```java
@Override
public void onTrigger(final ProcessContext context, final ProcessSession session) {
    FlowFile flowFile = session.get();
    if (flowFile == null) {
        return;
    }
    // process flowFile
}
```

A `null` here means there is nothing queued for this run - return silently,
there is nothing to transfer.

## Optional input (`INPUT_ALLOWED`)

If the component declares `@InputRequirement(Requirement.INPUT_ALLOWED)` -
it can run both as a regular step (consuming an incoming FlowFile) and as a
source, triggered on a schedule with no incoming connection at all - a plain
null check is wrong. A `null` FlowFile is a normal case when the processor
has no incoming connection; it is only an error when an incoming connection
exists but did not deliver a FlowFile this run:

```java
private boolean isRequestInvalid(FlowFile invocationFile, ProcessContext context) {
    return context.hasIncomingConnection() && invocationFile == null && context.hasNonLoopConnection();
}

@Override
public void onTrigger(final ProcessContext context, final ProcessSession session) {
    FlowFile invocationFile = session.get();
    if (isRequestInvalid(invocationFile, context)) {
        return;
    }
    if (context.hasIncomingConnection() && invocationFile != null) {
        // read attributes / use invocationFile only here
    }
    // rest of the logic must not assume invocationFile is non-null
}
```

Every later access to `invocationFile` must stay guarded by its own
null check - after the validity check, `null` is still a legitimate state.

## Rules

- Match the check to `@InputRequirement`: `INPUT_REQUIRED` -> plain `session.get()` + null-return; `INPUT_ALLOWED` -> connection-aware check; `INPUT_FORBIDDEN` -> `session.get()` is not called at all.
- Perform the check first, before any other work in `onTrigger`.
- For `INPUT_ALLOWED` components, never treat a `null` FlowFile as an automatic error - check `context.hasIncomingConnection()` / `context.hasNonLoopConnection()` first.
- Guard every later access to the FlowFile variable, not just the initial check, when input is optional.
- On a failed check, return without transferring anything - there is no FlowFile to route.
