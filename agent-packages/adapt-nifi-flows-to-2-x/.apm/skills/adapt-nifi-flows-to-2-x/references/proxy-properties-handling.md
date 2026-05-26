## Proxy properties handling

If the CSV contains a Proxy property warning (any row whose `Issue` references `Proxy properties in InvokeHTTP`):

1. Run the following command to get proxy property values for all affected processors (no file reading needed):

   ```bash
   python3 .claude/skills/adapt-nifi-flows-to-2-x/scripts/upgrade_nifi_lib.py \
     --show-processor-props <csv_path> <exports_dir> --handler fix_invokehttp_proxy
   ```

   Use the output to identify which processors share identical proxy settings (same `Proxy Host`, `Proxy Port`, and
   credential references) - those can share a single StandardProxyConfigurationService.

2. List all top-level flow files under `exports_dir` and **use `AskUserQuestion`** to ask the user where the Proxy
   Configuration Service should be created.
3. If multiple affected processors share the same proxy values, create one shared Controller Service; otherwise create
   one per distinct set of values with unique names.
4. If the user's chosen file differs from the processor's file, populate `INVOKEHTTP_CROSS_FILE` in the run script;
   otherwise leave it as `{}`.
5. **If you populated `INVOKEHTTP_CROSS_FILE` (any entry added), STOP and open
   `.claude/skills/adapt-nifi-flows-to-2-x/references/cross-file-parameter-context.md` now, before
   returning to SKILL.md.** Cross-file proxy services reference `#{param}` values that the parent PG
   must resolve; skipping this guide produces broken `PARENT_CONTEXT_PLAN` entries and runtime
   warnings of the form `[WARN] context '<name>' not found in any file under <exports_dir>`.

Skip this section when no Proxy properties warning is present in the CSV.
