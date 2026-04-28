---
name: apply-upgrade-advisor-recommendations
description: >-
  Use this skill to apply NiFi 1.x to 2.x upgrade recommendations produced by the
  [upgradeAdvisor](https://github.com/Netcracker/qubership-nifi/tree/main/dev/upgrade-advisor) script. The static transformation library is at:
  `.claude/skills/apply-upgrade-advisor-recommendations/scripts/upgrade_nifi_lib.py`
---

# Apply Upgrade Advisor Recommendations

## Inputs

Accept as positional args: `/apply-upgrade-advisor-recommendations [csv_path] [exports_dir]`

If either argument is missing:
- For `csv_path`: use Glob to find `**/upgradeAdvisorReport.csv` in the workspace.
- For `exports_dir`: once the CSV is found, run:
  ```bash
  python3 .claude/skills/apply-upgrade-advisor-recommendations/scripts/upgrade_nifi_lib.py \
    --detect-exports-dir <csv_path>
  ```
  Use the printed value as `exports_dir`. Do **not** derive `exports_dir` from the location
  of JSON files — it must be derived from the CSV's `Flow name` values, which encode the
  path relative to the correct exports root.

If either path was discovered automatically, use AskUserQuestion tool to confirm both paths with the user before proceeding.

Once `exports_dir` is confirmed, detect the source NiFi version:

```bash
python3 .claude/skills/apply-upgrade-advisor-recommendations/scripts/detect_nifi_version.py \
  <exports_dir>
```

- **Exit 0, one version printed**: use it directly as `NIFI_SOURCE_VERSION`.
- **Exit 0, multiple versions printed**: use AskUserQuestion to ask which version to use, listing all options.
- **Exit 1** (no `org.apache.nifi` bundles found): use AskUserQuestion to ask the user to supply the source NiFi version.

Report the resolved `NIFI_SOURCE_VERSION` to the user before continuing.

---

## Steps

### Step 1 - Create tmp directory

1. Create repo-local `tmp/` directory if not exists.
2. Add it to `.gitignore` if not present already.
3. Remove existing `tmp/upgrade_nifi_run.py` if exists, to avoid confusion with the new one that will be generated in Step 3.

Log the changes that were made.

### Step 2a - Collect data

Run both commands and show the full output to the user:

```bash
python3 .claude/skills/apply-upgrade-advisor-recommendations/scripts/upgrade_nifi_lib.py \
  --collect-vars <csv_path> <exports_dir>
```

```bash
python3 .claude/skills/apply-upgrade-advisor-recommendations/scripts/upgrade_nifi_lib.py \
  --analyze <csv_path> <exports_dir>
```

- `--collect-vars` outputs a JSON object mapping each variable name to its occurrences
  (file path, PG name/UUID, value, reference count within the PG subtree) and a
  `values_differ` flag.
- `--analyze` summarises every CSV row as AUTO, AI Agent, CONTEXT PLAN, or MANUAL.

### Step 2b - AI analysis of variable data

Read the JSON produced by `--collect-vars`. For each variable, note:

- **How many files/PGs define it** - appears in 1 vs. multiple flows
- **Whether values differ** (`values_differ: true`) - means it can never go into a
  common parameter context with a single value
- **Total reference count per PG** - how many times `${varName}` appears in properties
  within that PG's subtree

Using this data, propose a parameter context plan. Specifically:

1. **Common context candidates** - variables that appear in ≥ 2 PGs with the *same* value.
   Propose a common parameter context for these. Suggest name to include some prefix related to top-level PG name to avoid conflicts with any existing contexts in the flows: e.g. `orchestrator-common-params` if the top-level PG is `Orchestrator`.
2. **Per-flow context candidates** - variables unique to one PG, or that differ across PGs.
   Propose a per-flow context for each affected PG.
3. **Hardcoding candidates** - variables defined in only one PG *and* referenced <= 2 times
   total in that PG's subtree. These may not be worth parameterising.
4. **Variables with differing values** - propose separate per-flow contexts *or* hardcoding,
   and note the conflicting values so the user can decide what to do. If the user chooses
   hardcoding, add one entry to `HARDCODE_PLAN` per PG instead of creating per-flow contexts.

**Rule for `apply_to`:** Include **every PG that defines a variable** in `apply_to`, even if
its `reference_count = 0`. A PG with `ref_count = 0` is a variable *source* — `apply_variable_contexts`
must still attach the parameter context to it and clear its `variables` dict. Omitting it
leaves stale NiFi 1.x variable definitions in the flow.

If `NIFI_SOURCE_VERSION < 1.28.1` **and** the CSV contains a PrometheusRecordSink issue (any row
whose `Issue` or `Solution` references `PrometheusRecordSink`), also ask the user what target
type, bundle, and property mapping to use for the upgrade, showing these defaults:

```python
new_type   = "org.qubership.nifi.service.QubershipPrometheusRecordSink"
new_bundle = {"group": "org.qubership.nifi", "artifact": "qubership-service-nar", "version": "1.0.7"}
prop_map   = {
    "prometheus-reporting-task-metrics-endpoint-port": "prometheus-sink-metrics-endpoint-port",
    "prometheus-reporting-task-instance-id":           "prometheus-sink-instance-id",
    "prometheus-reporting-task-ssl-context":           None,  # dropped
    "prometheus-reporting-task-client-auth":           None,  # dropped
}
```

If the user accepts the defaults, use them as-is. Skip this question when
`NIFI_SOURCE_VERSION >= 1.28.1` or no PrometheusRecordSink issue is present in the CSV.

If the CSV contains a Proxy property warning (any row whose `Warning` or `Solution` references `Proxy properties in InvokeHTTP`), list the process group from `exports_dir` and ask the user in which process group the Proxy Configuration Service should be created. Compare the InvokeHTTP property values ​​of the processors. If they are the same, you will need to create a single shared Controller Service for all processors. If the values ​​differ, you will need to create multiple Controller Services. If you are going create multiple Controller Services, you must assign them unique names.

Skip this question when no Proxy properties warning is present in the CSV.

If the CSV contains a Access Key ID and Secret Access Key warning (any row whose `Warning` or `Solution` references `Access Key ID and Secret Access Key`), list the process group from `exports_dir` and ask the user in which process group the AWS Credentials Provider service should be created.

For each affected processor, check whether the `Access Key ID` and `Secret Access Key` properties are present in the processor. If both are absent or empty, inform the user that the `AWSCredentialsProviderControllerService` will be created with empty credentials and that they will need to configure it manually in NiFi UI after the script runs:
1. Open the process group that contains the service
2. Go to Controller Services and find `AWSCredentialsProviderService`
3. Click Edit, set `Access Key` and `Secret Key`, then enable the service
If you are going create multiple Controller Services, you must assign them unique names.

Skip this question when no Access Key ID and Secret Access Key warning is present in the CSV.

Then use AskUserQuestion tool to ask the following questions before generating any run script:

- Do the proposed context names work, or should they be different?
- Should any hardcoding candidates actually be hardcoded instead of parameterised?
  (If so, which ones, and what value should be used?)
- For variables with differing values across flows: should each flow get its own parameter
  context, or does the user want to unify on one value?
- Are there variables that should be excluded from parameterisation entirely?
- Any other questions you judge relevant given what you see in the data (e.g. consolidating
  many small per-PG contexts into one, grouping by environment, etc.)

Only proceed to Step 3 once the user has answered.

### Step 3 - Generate the run script

Based on the analysis and user answers from Step 2b, generate `tmp/upgrade_nifi_run.py`:

```python
import sys
sys.path.insert(0, '.claude/skills/apply-upgrade-advisor-recommendations/scripts')
from fixes    import apply_csv_transforms
from contexts import apply_variable_contexts, apply_hardcoded_values

CSV_PATH    = "<abs_path_to_csv>"
EXPORTS_DIR = "<abs_path_to_exports_dir>"

# PARAMETER_CONTEXT_PLAN - built from the AI analysis and user answers in Step 2b.
# Each entry: name, parent (or None), parameters dict, apply_to list of (rel_path, pg_uuid).
PARAMETER_CONTEXT_PLAN = [
    {
        "name": "common-params",
        "parent": None,
        "parameters": {
            # ... common variables here ...
        },
        "apply_to": [
            # ("relative/path/to/flow.json", "process-group-uuid"),
        ],
    },
    # ... child contexts ...
]

# HARDCODE_PLAN - variables excluded from parameterisation (values_differ: true and
# the user chose hardcoding over per-flow contexts). One entry per PG per variable.
HARDCODE_PLAN = [
    # {
    #     "variable": "processing.var2",
    #     "value":    "EntityType1",
    #     "rel_path": "relative/path/to/flow.json",
    #     "pg_uuid":  "process-group-uuid",
    # },
]

# Detected (or user-confirmed) source NiFi version — used for new Apache NiFi bundle versions.
NIFI_SOURCE_VERSION = "<detected_version>"

# Empty dict = use defaults (NiFi >= 1.28.1).
# For older NiFi, fill with user-confirmed values:
#   {"new_type": "...", "new_bundle": {...}, "prop_map": {...}}
PROMETHEUS_UPGRADE_PARAMS = {}

apply_csv_transforms(
    CSV_PATH, EXPORTS_DIR,
    nifi_version=NIFI_SOURCE_VERSION,
    prometheus_params=PROMETHEUS_UPGRADE_PARAMS,
)
apply_variable_contexts(EXPORTS_DIR, PARAMETER_CONTEXT_PLAN)
apply_hardcoded_values(EXPORTS_DIR, HARDCODE_PLAN)
```

**Show the generated script to the user for a final review** before running it.
Ask them to confirm or adjust anything.

### Step 4 - Run on approval

```bash
python3 tmp/upgrade_nifi_run.py
```

Show the full output to the user.

### Step 5 - Script engine translation (ExecuteScript processors)

For each row in the CSV where `Issue` contains `Script Engine = python/ruby/lua`:

1. Read the processor's `Script Body` property from the modified flow JSON (use the Read tool)
2. Translate the script body from its original language to Groovy:
   - Preserve logic and variable names as closely as possible
   - Use NiFi Groovy globals: `session`, `flowFile`, `log`, `context`
   - FlowFile operations: `session.get()`, `session.write()`, `session.putAttribute()`,
     `session.transfer(ff, REL_SUCCESS)`, `session.transfer(ff, REL_FAILURE)`
   - If translation is uncertain, emit a Groovy stub with the original in a comment block
3. Update the processor in the JSON via Edit tool:
   - Set `Script Engine` → `"Groovy"`
   - Set `Script Body` to:
     ```groovy
     // Auto-translated from <language> by apply-upgrade-advisor-recommendations. Review before use.
     <translated Groovy code>

     // ── Original <language> preserved below ──
     /*
     <original script body>
     */
     ```

### Step 6 - Checking accompanying files

Check the files in the repository and identify the files related to the changes. If you find files related to the changes, apply the changes to them.

### Step 7 - Report

Summarise:
- Files modified and what changed in each
- Manual action items (from script output + any items you could not automate)

---

## What is automated vs. manual

| Issue type | How handled |
| --- | --- |
| `Script Engine = python/ruby/lua` | Step 5 (AI agent translation) |
| `Proxy properties in InvokeHTTP` | `apply_csv_transforms` - creates StandardProxyConfigurationService |
| `Variables are not available` | Steps 2b + 3 - AI-assisted parameter context design |
| S3 hardcoded credentials | `apply_csv_transforms` - creates AWSCredentialsProviderControllerService; if `Access Key ID` and `Secret Access Key` are absent from the processor, credentials must be filled in manually in NiFi UI after the script runs |
| `ConvertJSONToSQL` | `apply_csv_transforms` - migrates to PutDatabaseRecord + JsonTreeReader |
| Kafka version upgrades (1_0/2_0 → 2_6) | `apply_csv_transforms` - type rename only |
| Azure Storage → _v12 | `apply_csv_transforms` - type rename + property renames; **credentials service flagged as manual** |
| Level = Error | Always manual - report the solution from the CSV |
| `ConvertExcelToCSVProcessor`, `ConvertAvroToJSON` | Manual - complex restructuring needed |

---

## Notes

- The library never overwrites a file unless it was actually modified.
- JSON files are written with `indent=4`, key order preserved.
- If a processor UUID from the CSV is not found in the flow JSON, a `[WARN]` is logged and
  the row is skipped (does not abort the run).
- `apply_variable_contexts` replaces `${varName}` → `#{varName}` **only** for variable names
  that are part of the parameter context plan for the given PG (including inherited parameters
  from parent contexts). FlowFile attribute expressions like `${filename}` or `${uuid()}` are
  left untouched.
- `apply_hardcoded_values` replaces `${varName}` → the literal string value directly in
  processor/service properties. Use for variables with `values_differ: true` when the user
  prefers hardcoding over per-flow parameter contexts. Each flow file is loaded once even
  when multiple variables in the same file are hardcoded.
