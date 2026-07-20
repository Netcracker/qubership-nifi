# Batch processing

Batch-write pattern for qubership-nifi components that push multiple items
from a single FlowFile to an external system (JDBC, bulk HTTP API, message
queue). Applies whenever a component sends more than one item per
`onTrigger` call to something outside the JVM.

## Batch size configuration

Expose batch size as a `PropertyDescriptor` (e.g. `max-batch-size`, default
`100`, `NON_NEGATIVE_INTEGER_VALIDATOR`, EL scope `NONE`). Read it from
`ProcessContext` once, in `@OnScheduled`, and store it as an instance field.
Never re-read it inside the per-item loop or hardcode it.

## Canonical usage pattern

```java
@OnScheduled
public void onScheduled(final ProcessContext context) {
    this.maxBatchSize = context.getProperty(MAX_BATCH_SIZE).asInteger();
}

@Override
public void onTrigger(final ProcessContext context, final ProcessSession session) {
    try (Target target = openTarget(context, ff)) {
        target.begin();
        try {
            int batchSize = 0;
            while ((item = source.next()) != null) {
                target.add(item);
                if (++batchSize >= maxBatchSize) {
                    target.flush();
                    batchSize = 0;
                }
            }
            if (batchSize > 0) {
                target.flush(); // trailing batch - do not skip
            }
            target.commit();
            session.transfer(ff, REL_SUCCESS);
        } catch (RecoverableException ex) {
            getLogger().error("Failed to write batch for FlowFile", ex);
            target.rollback();
            session.putAttribute(ff, ERROR_MSG_ATTR, ex.getMessage());
            session.transfer(ff, REL_RETRY);
        } catch (Exception ex) {
            getLogger().error("Failed to write batch for FlowFile", ex);
            target.rollback();
            session.putAttribute(ff, ERROR_MSG_ATTR, ex.getMessage());
            session.transfer(ff, REL_FAILURE);
        }
    }
}
```

`Target`/`RecoverableException` stand in for whatever the concrete external
system uses (`Connection`/`SQLRecoverableException` for JDBC, an HTTP client
with a 5xx-vs-4xx split for a bulk API, etc.) - the shape stays the same:
open once per FlowFile, accumulate, flush by threshold, flush the tail,
commit once, roll back on error.

## Transaction and error routing

One connection/session covers the whole FlowFile - never one per item.
Commit (or equivalent finalize) exactly once, after every batch in the
FlowFile succeeded. On any failure, roll back before transferring the
FlowFile away, so a partially sent batch is never left applied. Split
recoverable errors (timeouts, connection drops, throttling) from permanent
ones (validation failures, malformed data) into `retry` vs `failure`
relationships.

## Rules

- Cache batch size and related config in `@OnScheduled` - never inside the per-item loop.
- One connection/session/transaction per FlowFile - never one per item.
- Always flush the trailing partial batch after the read loop ends.
- Commit once per FlowFile; roll back before routing to `retry`/`failure`.
- Route recoverable and non-recoverable errors to different relationships.
- Log every failure at ERROR level and record `ex.getMessage()` in a FlowFile attribute before routing away.
- Close every opened resource via try-with-resources.
