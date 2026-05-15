## Parameter context planning

Read the JSON produced by `--collect-vars`. For each variable, note:

- **How many files/PGs define it** - appears in 1 vs. multiple flows
- **Whether values differ** (`values_differ: true`) - means it can never go into a
  common parameter context with a single value
- **Total reference count per PG** - how many times `${varName}` appears in properties
  within that PG's subtree

Using this data, propose a parameter context plan. Specifically:

1. **Common context candidates** - variables that appear in >= 2 PGs with the *same* value.
   Propose a common parameter context for these. Suggest name to include some prefix related to top-level PG name to
   avoid conflicts with any existing contexts in the flows: e.g. `orchestrator-common-params` if the top-level PG is
   `Orchestrator`.
2. **Per-flow context candidates** - variables unique to one PG, or that differ across PGs.
   Propose a per-flow context for each affected PG.
3. **Hardcoding candidates** - variables defined in only one PG *and* referenced <= 2 times
   total in that PG's subtree. These may not be worth parameterising.
4. **Variables with differing values** - propose separate per-flow contexts *or* hardcoding,
   and note the conflicting values so the user can decide what to do. If the user chooses
   hardcoding, add one entry to `HARDCODE_PLAN` per PG instead of creating per-flow contexts.

**Rule for `apply_to`:** Include **every PG that defines a variable** in `apply_to`, even if
its `reference_count = 0`. A PG with `ref_count = 0` is a variable *source* - `apply_variable_contexts`
must still attach the parameter context to it and clear its `variables` dict. Omitting it
leaves stale NiFi 1.x variable definitions in the flow.

Then use AskUserQuestion tool to ask the following questions before generating any run script:

- Do the proposed context names work, or should they be different?
- Should any hardcoding candidates actually be hardcoded instead of parameterised?
  (If so, which ones, and what value should be used?)
- For variables with differing values across flows: should each flow get its own parameter
  context, or does the user want to unify on one value?
- Are there variables that should be excluded from parameterisation entirely?
- Any other questions you judge relevant given what you see in the data (e.g. consolidating
  many small per-PG contexts into one, grouping by environment, etc.)
