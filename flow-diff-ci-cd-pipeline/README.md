# GitLab CI/CD Flow Diff

## Overview

This directory contains a **standalone `.gitlab-ci.yml`** that other repositories can
copy into their own root to get automatic NiFi flow diff comments on merge requests.

When a merge request touches files under a configured directory (versioned NiFi flow exports),
the pipeline:

1. Runs `qubership-nifi-flow-diff-cli` (`git-diff` subcommand) to compare the MR's changes
   against the merge-base commit.
2. Filters out purely technical/cosmetic differences, keeping only significant and environmental
   changes (plus added/removed flows).
3. Posts a single sticky comment on the merge request with the result (updating the same comment
   on re-runs instead of adding a new one each time).

Everything happens inside one job, in shell variables - no report files are written to disk and
no pipeline artifacts are produced. The MR comment is the only output.

## Files in this directory

| File               | Purpose                                                                                             |
| ------------------ | --------------------------------------------------------------------------------------------------- |
| `.gitlab-ci.yml`   | The pipeline itself. Copy this into the target repository's root and adjust the `variables:` block. |
| `Dockerfile`       | The image used in the pipeline.                                                                     |

## Setup walkthrough

### 1. Create the GitLab API token

The pipeline posts/updates the MR comment via the GitLab Notes API, authenticated with a
Project Access Token (`api` scope).

1. In the target GitLab project: **Settings -> Access Tokens**.
2. Create a token with the `api` scope and the `Reporter` role (sufficient for posting/updating MR
   notes; GitLab's Notes API still requires the `api` scope, since there is no narrower scope for
   posting/updating notes).
3. In **Settings -> CI/CD -> Variables**, add a variable:
   - Key: `GITLAB_API_TOKEN`
   - Value: the token from step 2
   - Type: `Variable`
   - **Protect variable: off**
   - **Mask variable: on**

The "Protect variable: off" part is important and easy to get wrong: merge request pipelines run
on a merge ref (`refs/merge-requests/<iid>/merge`), not on a protected branch, so a variable
marked "Protected" would silently not be available to the job (it would show up empty). Masking
is safe to enable independently and recommended, so the token never appears in job logs.

Because the variable must stay unprotected, it is available to every merge request pipeline in
this project, including ones whose own branch modifies `.gitlab-ci.yml` or this job's scripts. In
other words, anyone who can open a merge request here (and, if fork pipelines are enabled for this
project, anyone with a fork) can read or exfiltrate this `api`-scope token. Only add this pipeline
to repositories where every merge request author is already trusted with that level of API access.

### 2. Copy the pipeline into the target repository

Copy `.gitlab-ci.yml` from this directory into the root of the target repository. If that
repository already has a `.gitlab-ci.yml`, merge only the `flow-diff:` job and the top-level
`variables:` block (`FLOW_DIFF_PATH`) into it - do not copy `stages:` or
`workflow:`. Both replace, rather than extend, the target repository's own top-level `stages:` and
`workflow:rules:`, which would stop every other job's pipeline from being created. They are only
there so this file works standalone; the `flow-diff:` job's own `rules:` already restricts it to
merge request events on its own.

Adjust the `variables:` block for that repository:

```yaml
variables:
  FLOW_DIFF_PATH: "nifi/versioned-flow"   # directory with the NiFi flow exports to diff
```

### 3. Test it

1. Push a change under `FLOW_DIFF_PATH` on a branch, open a merge request, and check it for the
   `<!-- nifi-flow-diff -->` sticky comment.
2. Push another change and confirm the same comment gets updated in place rather than
   duplicated.

## Existing comment lookup

The pipeline finds the existing sticky comment by listing notes with `?per_page=100` and looking
for the `<!-- nifi-flow-diff -->` marker. This assumes the merge request has fewer than 100 notes;
beyond that, pagination would be needed to find an older sticky comment, which this prototype does
not implement.

## Comment size limit

GitLab caps a merge request note body at roughly 1,000,000 characters. The script truncates the
comment at 990,000 bytes (byte count as a conservative proxy for character count - bytes are
always >= characters for UTF-8, so this never truncates later than the real limit allows), cuts
on a line boundary, closes a dangling Markdown code fence if the cut landed inside one (otherwise
everything after it would render as code), and appends a truncation notice.

## Automated test

The `flow-diff-pipeline-test` GitHub workflow runs this pipeline against a real GitLab. It builds the image, starts
GitLab and a runner with `.github/docker/flow-diff-gitlab/docker-compose.yaml`, seeds a project with this
`.gitlab-ci.yml` and a NiFi flow, and drives four merge requests through it:

| Merge request | Asserts |
| --- | --- |
| A changed processor property | The pipeline succeeds and posts one note naming the changed property. |
| A second push onto the same branch | The same note is updated in place, rather than a second one added. |
| Rewritten `instanceIdentifier` values only | The note reads `No significant NiFi flow changes detected.` |
| A file outside `FLOW_DIFF_PATH` | No `flow-diff` job runs and no note appears. |

The last is opened before the other three and asserted after them, so a GitLab that is merely slow to create
pipelines cannot pass it.

Editing this file needs no matching edit in the test: it reads `FLOW_DIFF_PATH` out of the `variables:` block and
overwrites the job's `image:` whatever it points at. Only the three strings the test asserts - the
`<!-- nifi-flow-diff -->` marker and the two note bodies - are written out in the test as well, so that rewording one
fails the setup step instead of quietly passing.

### What the suite does not reach

| Not covered | Consequence |
| --- | --- |
| `environmental`, `addedFlows`, `removedFlows` in the totals gate | Only `significant` is ever non-zero, so dropping one of the others would not fail the suite. |
| Comment truncation | The 990,000-byte path is never taken. |
| The `git fetch` fallback for a missing base commit | `GIT_DEPTH: "0"` always leaves the base commit present. |

### Running it by hand

You need Docker, a JDK, and Maven.

1. Build the image under the tag the test expects:

   ```shell
   mvn -f qubership-nifi-tools/pom.xml install -pl qubership-nifi-flow-diff-cli-deps -am \
       -DskipUnitTests=true -Dgpg.skip=true
   docker build -t flow-diff-cli:it -f flow-diff-ci-cd-pipeline/Dockerfile .
   ```

2. Start GitLab and wait for it. The first boot runs the database migrations and takes several minutes:

   ```shell
   docker compose -f .github/docker/flow-diff-gitlab/docker-compose.yaml up -d --wait --wait-timeout 600
   ```

3. Export the settings the library reads, then source it:

   ```shell
   export FLOW_DIFF_GITLAB_HOST=127.0.0.1:8929
   export RUNNER_TEMP=/tmp
   set -euo pipefail
   . .github/workflows/sh/flow-diff-gitlab-lib.sh
   ```

4. Seed GitLab, then run the scenarios in this order:

   ```shell
   seed_gitlab_token
   register_gitlab_runner flow-diff-gitlab_default
   create_test_project "$PWD" "$RUNNER_TEMP/flow-diff-project"
   save_test_context
   open_out_of_scope_mr
   scenario_significant_change
   scenario_sticky_update
   scenario_technical_change
   assert_out_of_scope_skipped
   ```

5. Tear down:

   ```shell
   docker compose -f .github/docker/flow-diff-gitlab/docker-compose.yaml down -v
   ```

The runner pulls with the `if-not-present` policy, so rebuild `flow-diff-cli:it` before a rerun or the old image is
used in silence. `set -euo pipefail` is what stops a scenario at its first failure.
