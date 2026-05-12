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
- `--analyze` summarises every CSV row as AUTO, AI Agent, CONTEXT PLAN, or MANUAL. AUTO rows include the processor name and UUID - use these directly in subsequent steps; do not re-discover UUIDs by searching flow JSON files.

### Step 2b - Prepare upgrade decision plan

1. If the `--analyze` output contains any `[CONTEXT PLAN]` rows, **or** if the JSON produced by `--collect-vars` is not empty (variables present in the flow but not flagged by the advisor), open and follow `references/parameter-context-planning.md`.
2. If `NIFI_SOURCE_VERSION < 1.28.1` **and** the CSV contains any row whose `Issue` references `PrometheusRecordSink`, open and follow `references/prometheus-record-sink-analysis.md`.
3. If the CSV contains a Proxy property warning (any row whose `Issue` references `Proxy properties in InvokeHTTP`), open and follow `references/proxy-properties-handling.md`.
4. If the CSV contains an Access Key ID and Secret Access Key warning (any row whose `Issue` references `Access Key ID and Secret Access Key`), open and follow `references/aws-components-analysis.md`.
5. Ensure all user questions  from the applicable reference files have complete answers before proceeding to Step 3.

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

# Only needed when a StandardProxyConfigurationService should live in a different file
# than the InvokeHTTP processor. Keyed by processor UUID (from --analyze output).
# Leave as {} if the CS should be created in the same file as the processor.
INVOKEHTTP_CROSS_FILE = {
    # "<invokehttp-processor-uuid>": {
    #     "group_key":      "<any-unique-string-for-deduplication>",
    #     "parent_pg_path": "<exports_dir>/relative/path/to/parent.json",
    #     "child_pg_path":  "<exports_dir>/relative/path/to/child.json",
    # },
}

# Same structure as INVOKEHTTP_CROSS_FILE, for AWSCredentialsProviderControllerService.
# Leave as {} if the CS should be in the same file as the S3 processor.
S3_CROSS_FILE = {
    # "<s3-processor-uuid>": {
    #     "group_key":      "<any-unique-string-for-deduplication>",
    #     "parent_pg_path": "<exports_dir>/relative/path/to/parent.json",
    #     "child_pg_path":  "<exports_dir>/relative/path/to/child.json",
    # },
}

apply_csv_transforms(
    CSV_PATH, EXPORTS_DIR,
    nifi_version=NIFI_SOURCE_VERSION,
    prometheus_params=PROMETHEUS_UPGRADE_PARAMS,
    invokehttp_cross_file=INVOKEHTTP_CROSS_FILE or None,
    s3_cross_file=S3_CROSS_FILE or None,
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
   - Set `Script Engine` = `"Groovy"`
   - Set `Script Body` to:
     ```groovy
     // Auto-translated from <language> by apply-upgrade-advisor-recommendations. Review before use.
     <translated Groovy code>

     // ── Original <language> preserved below ──
     /*
     <original script body>
     */
     ```

### Step 6 - Adapt other repository files

1. Enumerate candidate files, then **filter out** the following before reading anything: `tmp/`, `<exports_dir>/`, `<csv_path>`, `.claude/skills/apply-upgrade-advisor-recommendations/`, build artifacts and `.git/`. Only read files that survive the filter.
2. For each remaining file, check whether the changes from steps 4–5 require a corresponding update — for example: adding new controller service to an enable-list, a renamed property referenced in config, a parameterised variable mentioned in configuration or documentation file. If in doubt, propose the change and ask the user to confirm before applying it.

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
| Kafka version upgrades (1_0/2_0 to 2_6) | `apply_csv_transforms` - type rename only |
| Azure Storage to _v12 | `apply_csv_transforms` - type rename + property renames; **credentials service flagged as manual** |
| Level = Error | Always manual - report the solution from the CSV |
| `ConvertExcelToCSVProcessor`, `ConvertAvroToJSON` | Manual - complex restructuring needed |

---

## Notes

- The library never overwrites a file unless it was actually modified.
- JSON files are written with `indent=4`, key order preserved.
- If a processor UUID from the CSV is not found in the flow JSON, a `[WARN]` is logged and
  the row is skipped (does not abort the run).
- `apply_variable_contexts` replaces `${varName}` with `#{varName}` **only** for variable names
  that are part of the parameter context plan for the given PG (including inherited parameters
  from parent contexts). FlowFile attribute expressions like `${filename}` or `${uuid()}` are
  left untouched.
- `apply_hardcoded_values` replaces `${varName}` with the literal string value directly in
  processor/service properties. Use for variables with `values_differ: true` when the user
  prefers hardcoding over per-flow parameter contexts. Each flow file is loaded once even
  when multiple variables in the same file are hardcoded.
