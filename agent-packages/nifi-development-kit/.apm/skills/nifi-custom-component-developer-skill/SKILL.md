---
name: nifi-custom-component-developer-skill
description: Conventions and correctness rules for writing or reviewing custom Apache NiFi components (Processors, Controller Services, ReportingTasks) - class-level annotations, PropertyDescriptor/Relationship naming, FlowFile null-checks, batch writes to external systems, where to initialize instance fields, and how to unit-test these components. Use this skill whenever the user asks to create, write, implement, extend, or review a NiFi processor/controller service/reporting task, even if they don't mention "conventions" or "best practices" by name - e.g. "write a processor that inserts records into X", "add a new property to this processor", "why is my onTrigger NPEing", "how should I name this relationship", or "write a test for this controller service".
---

# NiFi Custom Component Developer

## When writing a new component

Creating a component includes its unit tests in the same change, even if
the user did not ask for tests.

Work through these in order; each links to the reference file with the
full rule, code shape, and rationale. Before writing code, read in full the
reference files for #1, #2, and #6, and the file for each of #3-5 that
applies to the component. A file whose topic the request did not mention
still has to be read if its step applies. For a Controller Service,
`annotations.md`, `naming.md`, `variable-initialization.md`, and
`mock-testing.md` are always required. Don't guess a rule from the
one-line summary here.

1. **Class-level annotations** - `@SideEffectFree`/`@SupportsBatching`/`@InputRequirement` order, `@Tags`/`@CapabilityDescription` placement, what `@WritesAttributes` may list. -> `references/annotations.md`
2. **PropertyDescriptor and Relationship naming** - `.name(...)` vs `.displayName(...)` casing, Java constant naming. -> `references/naming.md`
3. **FlowFile presence check** - the right null-check shape for `INPUT_REQUIRED` vs `INPUT_ALLOWED` components. -> `references/check-flowfile.md`
4. **Instance field initialization scope** - `@OnScheduled` vs `onTrigger`, driven by whether a value can depend on the FlowFile being processed. -> `references/variable-initialization.md`
5. **Batch writes to an external system** - if the component sends more than one item per FlowFile to a DB/API/queue: accumulate/flush/commit/rollback shape, retry vs failure routing. -> `references/batch.md`
6. **Unit tests** - which testing pattern applies (plain processor, processor + Controller Service, Controller Service standalone, Reporting Task).
   Load the `test-authoring` skill, if installed, and read `references/mock-testing.md` before writing the tests. The tests stay in the change: a temporary test that was deleted afterwards does not count. -> `references/mock-testing.md`
7. **Check against the rules** - before reporting the result, check the code against the `## Rules` section of every reference file you read.
   Also confirm that the unit tests from #6 are in the change, that every reference file this section requires was read, and that any naming conflict was put to the user.
   In the report, list each rule or check that is not met and explain why.

Steps #1, #2, #6, and #7 apply to every component. Skip a step from #3-5
only where it does not apply: a stateless transform processor with no
external I/O skips #5.

If a design document, ticket, or existing code gives a property or
relationship name that breaks `references/naming.md`, do not silently
follow either: stop and ask the user, citing both. Names in the style of
standard NiFi components, such as `Listening Port`, are not valid for
`.name(...)`, even where the original NiFi component uses them.

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
