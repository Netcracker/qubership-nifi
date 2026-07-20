---
name: nifi-custom-component-developer-skill
description: Conventions and correctness rules for writing or reviewing custom Apache NiFi components (Processors, Controller Services, ReportingTasks) in the qubership-nifi repository - class-level annotations, PropertyDescriptor/Relationship naming, FlowFile null-checks, batch writes to external systems, where to initialize instance fields, and how to unit-test these components. Use this skill whenever the user asks to create, write, implement, extend, or review a NiFi processor/controller service/reporting task in this repo, even if they don't mention "conventions" or "best practices" by name - e.g. "write a processor that inserts records into X", "add a new property to this processor", "why is my onTrigger NPEing", "how should I name this relationship", or "write a test for this controller service".
---

# NiFi Custom Component Developer

Rules extracted from the existing qubership-nifi component implementations
(`qubership-bundle`, `qubership-nifi-db-bundle`, `qubership-services`) so
new components stay consistent with the rest of the codebase. Each rule
below was derived from real code, not invented - when a reference file
gives an example, that shape is what reviewers will expect to see.

## When writing a new component

Work through these in order; each links to the reference file with the
full rule, code shape, and rationale. Read a reference file in full before
applying its rule - don't guess from the one-line summary here.

1. **Class-level annotations** - `@SideEffectFree`/`@SupportsBatching`/`@InputRequirement` order, `@Tags`/`@CapabilityDescription` placement, what `@WritesAttributes` may list. -> `references/annotations.md`
2. **PropertyDescriptor and Relationship naming** - `.name(...)` vs `.displayName(...)` casing, Java constant naming. -> `references/naming.md`
3. **FlowFile presence check** - the right null-check shape for `INPUT_REQUIRED` vs `INPUT_ALLOWED` components. -> `references/check-flowfile.md`
4. **Instance field initialization scope** - `@OnScheduled` vs `onTrigger`, driven by whether a value can depend on the FlowFile being processed. -> `references/variable-initialization.md`
5. **Batch writes to an external system** - if the component sends more than one item per FlowFile to a DB/API/queue: accumulate/flush/commit/rollback shape, retry vs failure routing. -> `references/batch.md`
6. **Unit tests** - which testing pattern applies (plain processor, processor + Controller Service, Controller Service standalone, ReportingTask, environment-dependent values). -> `references/mock-testing.md`

Not every component needs all six - a stateless transform processor with
no Controller Service dependency and no external I/O only needs #1-4.

## When reviewing existing code

Same six files double as a review checklist. Read the file for whichever
aspect is in question, then check the code against its `## Rules` section
(each file ends with one) - that section is the condensed, checkable form
of the rule if you don't need the full example/rationale again.

Common review triggers and where to look:

| Symptom in the diff | Reference file |
| --- | --- |
| `session.get()` with no null-check, or a null-check that ignores `hasIncomingConnection()` | `check-flowfile.md` |
| A property or relationship named with spaces, camelCase, or inconsistent casing between `.name()`/`.displayName()` | `naming.md` |
| `context.getProperty(...)` called inside a per-record loop, or a FlowFile-scoped value cached as an instance field | `variable-initialization.md` |
| `executeBatch()`/bulk API call with no trailing-batch flush, or a single `retry`/`failure` relationship for both transient and permanent errors | `batch.md` |
| Missing or misordered `@InputRequirement`/`@Tags`/`@CapabilityDescription`, or `@WritesAttributes` listing attributes the component doesn't itself set | `annotations.md` |
| A test that mocks `ProcessSession`/`ProcessContext` by hand, or mocks a plain status DTO | `mock-testing.md` |

## Source of truth

These rules are extracted from real qubership-nifi code, not the NiFi API
docs in the abstract. If a rule here seems to conflict with newer code in
the repo, re-derive it from the current code rather than trusting this
file blindly - these references were written at a point in time and the
codebase moves.
