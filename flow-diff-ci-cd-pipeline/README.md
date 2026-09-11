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

**Status:** prototype.

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
