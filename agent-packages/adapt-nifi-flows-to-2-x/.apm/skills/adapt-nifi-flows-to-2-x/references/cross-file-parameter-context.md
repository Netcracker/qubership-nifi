## Cross-file parameter context assignment

Follow this reference when `INVOKEHTTP_CROSS_FILE` or `S3_CROSS_FILE` will be non-empty
(i.e. any controller service will be written into a parent PG file). The goal is to populate
`PARENT_CONTEXT_PLAN` so that `apply_parent_contexts` resolves `#{param}` references in those
services after the script runs.

Skip this reference entirely when both `INVOKEHTTP_CROSS_FILE` and `S3_CROSS_FILE` are `{}`.

---

### For each unique parent PG file

Work through every distinct `parent_pg_path` value from `INVOKEHTTP_CROSS_FILE` and
`S3_CROSS_FILE` combined.

#### 1. Collect NEEDED_PARAMS

Read the controller service properties that will be written into that parent file. Extract
every `#{paramName}` value. These are the parameter names the parent PG must be able to
resolve.

*Example: `proxy-user-name: "#{proxy.username}"` and `proxy-user-password: "#{proxy.password}"`
give `NEEDED_PARAMS = {"proxy.username", "proxy.password"}`.*

#### 2. Find COVERING_CONTEXTS

Run the following command to get all parameter context definitions across all flow files (no file reading needed):

```bash
python3 .claude/skills/adapt-nifi-flows-to-2-x/scripts/upgrade_nifi_lib.py \
  --show-contexts <csv_path> <exports_dir>
```

Use the `direct_params` lists in the output to identify which context names cover each needed parameter.

A context **covers** a needed param if that param appears in its `direct_params` list.
A context **covers all** if it directly defines every param in `NEEDED_PARAMS`.
Multiple contexts together **cover all** if their union of `direct_params` equals `NEEDED_PARAMS`.

Record `COVERING_CONTEXTS` - the minimal set of contexts (by name) whose union covers all
needed params.

#### 3. Read the parent PG's existing context

The `--show-contexts` output already shows `pg_context` (the assigned context name) and each context's `inherited`
list for each file - no separate file read required.

#### 4. Apply the case table

| Scenario                                                                                  | Action                                                |
|-------------------------------------------------------------------------------------------|-------------------------------------------------------|
| No existing context + 1 covering context                                                  | `assign_direct` - no question needed                  |
| No existing context + 2 or more covering contexts                                         | `create_wrapper` - ask user to confirm suggested name |
| Existing context already inherits all covering contexts (or is itself a covering context) | `none` - skip                                         |
| Existing context missing some covering contexts                                           | `add_inheritance` - no question needed                |
| Existing context cannot be found in any file under `exports_dir`                          | Manual item for Step 7                                |
| Any param in NEEDED_PARAMS not defined in any context under `exports_dir`                 | Manual item for Step 7                                |

#### 5. For `create_wrapper` only

Use `AskUserQuestion` to confirm the wrapper context name. Suggest `<pg_name>-params` using
the `pg_name` field from the parent PG's entry in `--show-contexts` output. Record the user's
answer as `wrapper_name`.

---

### Build PARENT_CONTEXT_PLAN entries

Produce one entry per unique parent PG file (skip files with action `none` or manual):

```python
PARENT_CONTEXT_PLAN = [
    # assign_direct: parent PG has no context, one child context covers all needed params
    {
        "action":          "assign_direct",
        "parent_pg_file":  "relative/path/to/parent.json",  # relative to EXPORTS_DIR
        "needed_contexts": ["child-ctx-name"],
    },

    # create_wrapper: parent PG has no context, multiple child contexts needed
    {
        "action":          "create_wrapper",
        "parent_pg_file":  "relative/path/to/parent.json",
        "needed_contexts": ["child-ctx-1", "child-ctx-2"],
        "wrapper_name":    "user-confirmed-wrapper-name",
    },

    # add_inheritance: parent PG already has a context, extend it with missing inherits
    {
        "action":                "add_inheritance",
        "parent_pg_file":        "relative/path/to/parent.json",
        "needed_contexts":       ["missing-ctx-name"],
        "existing_context_name": "already-assigned-ctx-name",
    },
]
```

Collect any unresolvable cases as manual items for Step 7:

```markdown
- Parameter context: '<parent-pg-file>' has existing context '<ctx-name>' which could not
  be found in any file under exports_dir. Manually assign or extend a parameter context
  that covers: <list of needed params>.
```

---

### Notes

- `needed_contexts` should contain only the context names required to cover the params -
  not every context in the file.
- For `add_inheritance`, `needed_contexts` lists only the contexts that are currently
  missing from `inheritedParameterContexts` (contexts already present are omitted).
- `apply_parent_contexts` will copy context definitions from child files into the parent
  file automatically - you do not need to duplicate them manually.
- If two parent PG files both need the same wrapper, they each get their own entry with
  their own `wrapper_name`.
