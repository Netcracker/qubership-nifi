## Proxy properties handling

If the CSV contains a Proxy property warning (any row whose `Issue` references `Proxy properties in InvokeHTTP`):

1. The processor UUID is already in the `--analyze` output - use it directly to read proxy property values from the flow JSON (no re-discovery needed).
2. List all top-level flow files under `exports_dir` and **use `AskUserQuestion`** to ask the user where the Proxy Configuration Service should be created.
3. If multiple affected processors share the same proxy values, create one shared Controller Service; otherwise create one per distinct set of values with unique names.
4. If the user's chosen file differs from the processor's file, populate `INVOKEHTTP_CROSS_FILE` in the run script; otherwise leave it as `{}`.

Skip this section when no Proxy properties warning is present in the CSV.