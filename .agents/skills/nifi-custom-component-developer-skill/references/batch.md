# Batch processing

Patterns for NiFi components that handle more than one item per `onTrigger`
call against an external system (JDBC, bulk HTTP API, message queue). Three
shapes cover most cases - pick the one matching the component's FlowFile
cardinality:

1. **Batch write** - one FlowFile in, no FlowFile out; the FlowFile's content
   (or a query keyed by its attributes) drives writes to an external system.
2. **Fan-out** - one FlowFile in, N FlowFiles out; a query against an
   external system is split into multiple output FlowFiles by item count.
3. **Batch pull** - N FlowFiles in (via `session.get(int)` /
   `session.get(FlowFileFilter)`), M FlowFiles out.

## Batch size configuration

Expose batch size as a `PropertyDescriptor` (e.g. `max-batch-size`, default
`100`, `NON_NEGATIVE_INTEGER_VALIDATOR`, EL scope `NONE`). Batch size does
not depend on the FlowFile being processed and needs no EL support, so it
follows the `@OnScheduled` case from `variable-initialization.md`: read it
from `ProcessContext` once, in `@OnScheduled`, and store it as an instance
field. Never re-read it inside the per-item loop or hardcode it.

## Pattern: batch write (1 FlowFile in, 0 FlowFiles out)

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

One connection/session covers the whole FlowFile - never one per item.
Commit (or equivalent finalize) exactly once, after every batch in the
FlowFile succeeded. On any failure, roll back before transferring the
FlowFile away, so a partially sent batch is never left applied. Split
recoverable errors (timeouts, connection drops, throttling) from permanent
ones (validation failures, malformed data) into `retry` vs `failure`
relationships.

## Pattern: fan-out (1 FlowFile in, N FlowFiles out)

The component reads a batch from an external system (a DB result set, a
paged API response) and splits it into multiple output FlowFiles, capping
each one at the configured batch size - see `QueryDatabaseToCSV` for a real
example. `0` conventionally means "no cap", i.e. the whole result set goes
into a single output FlowFile:

```java
@OnScheduled
public void onScheduled(final ProcessContext context) {
    this.maxItemsPerFlowFile = context.getProperty(BATCH_SIZE).asInteger();
}

@Override
public void onTrigger(final ProcessContext context, final ProcessSession session) {
    FlowFile inFlowFile = session.get();
    if (isRequestInvalid(inFlowFile, context)) {
        return;
    }
    Map<String, String> attributes = inFlowFile != null ? inFlowFile.getAttributes() : Collections.emptyMap();

    try (Source source = openSource(context, inFlowFile)) {
        while (source.hasNext()) {
            FlowFile outFlowFile = session.write(
                    session.putAllAttributes(session.create(), attributes),
                    out -> {
                        int written = 0;
                        do {
                            writeItem(out, source.next());
                            written++;
                        } while (source.hasNext()
                                && (maxItemsPerFlowFile == 0 || written < maxItemsPerFlowFile));
                    });
            session.transfer(outFlowFile, REL_SUCCESS);
        }
    }
    if (inFlowFile != null) {
        session.remove(inFlowFile);
    }
}
```

`inFlowFile` here follows the `INPUT_ALLOWED` check from `check-flowfile.md`
- the component can run as a source (scheduled, no incoming connection) or
as a step that reads its query/parameters from an incoming FlowFile's
attributes. Every output FlowFile is transferred as soon as it fills up, not
batched in memory - the source is read once, streaming.

## Pattern: batch pull (N FlowFiles in, M FlowFiles out)

The component pulls a batch of FlowFiles from the queue with
`session.get(int)` / `session.get(FlowFileFilter)` (see the "Multiple
FlowFiles" section in `check-flowfile.md`), combines or redistributes their
content, and produces a different number of output FlowFiles - for example,
merging N small records into one bulk payload, or repartitioning N inputs
into M outputs sized by a target byte/row count:

```java
@Override
public void onTrigger(final ProcessContext context, final ProcessSession session) {
    List<FlowFile> inFlowFiles = session.get(maxBatchSize);
    if (inFlowFiles.isEmpty()) {
        return;
    }

    FlowFile outFlowFile = session.create(inFlowFiles);
    outFlowFile = session.write(outFlowFile, out -> {
        for (FlowFile inFlowFile : inFlowFiles) {
            try (InputStream in = session.read(inFlowFile)) {
                appendItem(out, in);
            }
        }
    });

    session.transfer(outFlowFile, REL_SUCCESS);
    session.remove(inFlowFiles);
}
```

Use `session.create(inFlowFiles)` (the overload taking a `Collection<FlowFile>`
of parents) rather than `session.create()` for every output FlowFile derived
from more than one input - this records the parent/child relationship in
NiFi's provenance lineage. Every input FlowFile pulled by `session.get(...)`
must end up either `transfer`red or `remove`d exactly once per `onTrigger`
call, whichever output FlowFile(s) it contributed to.

## Rules

- Batch size does not depend on the FlowFile and needs no EL support - cache it in `@OnScheduled` per `variable-initialization.md`, never inside the per-item loop.
- One connection/session/transaction per FlowFile (batch write) or per `onTrigger` call (fan-out/batch pull) - never one per item.
- Always flush the trailing partial batch after the read loop ends.
- Commit once per FlowFile/transaction; roll back before routing to `retry`/`failure`.
- Route recoverable and non-recoverable errors to different relationships.
- Log every failure at ERROR level and record `ex.getMessage()` in a FlowFile attribute before routing away.
- Close every opened resource via try-with-resources.
- Fan-out: a batch-size property of `0` means "no cap" (single output FlowFile) - do not treat `0` as "produce nothing".
- Batch pull: use `session.create(Collection<FlowFile> parents)` for output FlowFiles derived from more than one input FlowFile, so provenance lineage reflects the merge.
- Every FlowFile obtained via `session.get(int)`/`session.get(FlowFileFilter)` must be transferred or removed exactly once before `onTrigger` returns.
