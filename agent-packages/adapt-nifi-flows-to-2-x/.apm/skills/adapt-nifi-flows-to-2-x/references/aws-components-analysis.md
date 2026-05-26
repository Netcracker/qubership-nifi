## AWS Components analysis

If the CSV contains an Access Key ID and Secret Access Key warning (any row whose `Issue` references
`Access Key ID and Secret Access Key`):

1. Run the following command to get AWS credential property values for all affected processors (no file reading needed):

   ```bash
   python3 .claude/skills/adapt-nifi-flows-to-2-x/scripts/upgrade_nifi_lib.py \
     --show-processor-props <csv_path> <exports_dir> --handler fix_s3_credentials
   ```

2. For each entry in the output, check whether `Access Key` and `Secret Key` are present and non-empty. If both are
   absent or empty, note that the service will be created with empty credentials and must be configured manually in the
   NiFi UI after import.
3. List all top-level flow files under `exports_dir` and **use `AskUserQuestion`** to ask the user where the
   `AWSCredentialsProviderControllerService` should be created. Present each top-level flow file as a separate option.
4. If multiple Controller Services are needed, assign them unique names.
5. If the user's chosen file differs from the processor's file, populate `S3_CROSS_FILE` in the run script; otherwise
   leave it as `{}`.

If credentials are empty, include this in the manual action items for Step 7:

- Open the process group containing the service
- Go to Controller Services, find `AWSCredentialsProviderControllerService`, click Edit
- Set `Access Key ID` and `Secret Access Key`, then enable the service

Skip this section when no Access Key ID and Secret Access Key warning is present in the CSV.
