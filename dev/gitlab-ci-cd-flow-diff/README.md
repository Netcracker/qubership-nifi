# GitLab CI/CD Flow Diff (Prototype)

## Overview

This directory contains a **prototype, standalone `.gitlab-ci.yml`** that other repositories can
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

**Status:** prototype. It has been exercised end-to-end against a local GitLab.

## Files in this directory

| File                    | Purpose                                                                                                                     |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `.gitlab-ci.yml`        | The pipeline itself. Copy this into the target repository's root and adjust the `variables:` block.                        |
| `Dockerfile`            | The image used in the pipeline.                                                                                             |
| `build-image.sh`        | Script that builds the image.                                                                                               |
| `nifi-flow-diff.sh`     | Launcher script baked into the image at `/usr/local/bin/nifi-flow-diff`, so the pipeline can call the CLI by name.          |

## Setup walkthrough

### 1. Prerequisites

- The `qubership-nifi-flow-diff-cli` jar and its runtime dependency jars (see step 2).
- Docker, to build `flow-diff-cli:local`.

### 2. Place the CLI jar and its dependencies into `lib/`

Put `qubership-nifi-flow-diff-cli-<version>.jar`, together with its runtime dependency jars, into
`lib/` in your directory. How you obtain them is up to you - see
`qubership-nifi-tools/qubership-nifi-flow-diff-cli/README.md` ("Getting the jars") for ways to
fetch a released version without a local checkout or build of this repository, e.g. the
[helper pom](https://github.com/Netcracker/qubership-nifi/tree/main/qubership-nifi-tools/qubership-nifi-flow-diff-cli#option-2-helper-pom)
option, which pins the version and output directory in a small `flowdiff-pom.xml` instead of
passing them on the command line.

### 3. Build the Docker image

```bash
./build-image.sh
# or: ./build-image.sh my-custom-tag
```

This builds `flow-diff-cli:local` (default tag) from `Dockerfile`, baking in whatever is
currently in `./lib`. Re-run this whenever you fetch a new CLI version into `lib/`.

### 4. Point the pipeline at your image

In `.gitlab-ci.yml`, set `image:` to the image you built in step 3 (`flow-diff-cli:local` by
default). If you tag it differently or push it to a registry, adjust `image:` accordingly.

### 5. Create the GitLab API token

The pipeline posts/updates the MR comment via the GitLab Notes API, authenticated with a
Project or Group Access Token (`api` scope).

1. In the target GitLab project: **Settings -> Access Tokens** (or a Group Access Token if you
   want it shared across several projects).
2. Create a token with the `api` scope and a role of at least `Developer`.
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

### 6. Copy the pipeline into the target repository

Copy `.gitlab-ci.yml` from this directory into the root of the target repository. If that
repository already has a `.gitlab-ci.yml`, merge this job into it rather than overwriting.

Adjust the `variables:` block for that repository:

```yaml
variables:
  FLOW_DIFF_PATH: "nifi/versioned-flow"   # directory with the NiFi flow exports to diff
```

### 7. Test it

1. Push a change under `FLOW_DIFF_PATH` on a branch and open a merge request.
2. Watch the `flow-diff` job in the pipeline.
3. Check the merge request for the `<!-- nifi-flow-diff -->` sticky comment.
4. Push another change and confirm the same comment gets updated in place rather than
   duplicated.

## Comment size limit

GitLab caps a merge request note body at roughly 1,000,000 characters. The script truncates the
comment at 990,000 bytes (byte count as a conservative proxy for character count - bytes are
always >= characters for UTF-8, so this never truncates later than the real limit allows), cuts
on a line boundary, closes a dangling markdown code fence if the cut landed inside one (otherwise
everything after it would render as code), and appends a truncation notice.
