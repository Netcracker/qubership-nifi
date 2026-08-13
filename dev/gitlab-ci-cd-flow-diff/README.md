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

**Status:** prototype.

## Files in this directory

| File               | Purpose                                                                                             |
| ------------------ | --------------------------------------------------------------------------------------------------- |
| `.gitlab-ci.yml`   | The pipeline itself. Copy this into the target repository's root and adjust the `variables:` block. |
| `Dockerfile`       | The image used in the pipeline.                                                                     |
| `build-image.sh`   | Fetches the CLI jar and its dependencies via Maven, then builds the image.                          |
| `flowdiff-pom.xml` | Pins the `qubership-nifi-flow-diff-cli` version; drives the Maven fetch in `build-image.sh`.        |

## Setup walkthrough

### 1. Prerequisites

- Maven 3.x, to fetch the `qubership-nifi-flow-diff-cli` jar and its runtime dependencies.
- Docker, to build the image.
- A container registry your GitLab runner can pull from, to publish the image you build in step 3.

### 2. Pin the CLI version

Edit `flow.diff.version` in `flowdiff-pom.xml` to the `qubership-nifi-flow-diff-cli` release you
want. See
`qubership-nifi-tools/qubership-nifi-flow-diff-cli/README.md` ("Getting the jars", "Option 2:
helper pom") for background on this file.

### 3. Build and push the Docker image

```bash
./build-image.sh registry.example.com/flow-diff-cli:X.Y.Z
docker push registry.example.com/flow-diff-cli:X.Y.Z
```

`build-image.sh` runs `mvn -f flowdiff-pom.xml dependency:copy-dependencies` to fetch the jar and
its runtime dependencies into `lib/`, then builds the given tag (`flow-diff-cli:local` if you omit
it) from `Dockerfile`. Re-run both commands whenever you bump `flow.diff.version` in
`flowdiff-pom.xml`.

The image must end up in a registry your runner can pull from: an unqualified, locally-built tag
(the `./build-image.sh` default) only exists on the machine that ran Docker, and a GitLab runner's
Docker executor almost never is that machine (shared, autoscaled, and Kubernetes runners each use
their own, separate Docker engine). Pointing `image:` at such a tag will fail as soon as the job
lands on a runner that never built it.

### 4. Point the pipeline at your image

In `.gitlab-ci.yml`, set `image: name:` to the fully qualified tag you pushed in step 3 - ideally
pinned by digest (`docker inspect --format='{{index .RepoDigests 0}}' <tag>` after the push) so the
pipeline is immune to the tag being overwritten later.

If you intentionally want to skip the registry and rely on a runner-local image instead, that only
works when every runner eligible for this job builds the image itself (so it's actually present
locally when the job starts), and even then the job needs `pull_policy: [if-not-present]`, which
GitLab rejects unless the runner's `config.toml` explicitly lists it in `allowed_pull_policies`.

### 5. Create the GitLab API token

The pipeline posts/updates the MR comment via the GitLab Notes API, authenticated with a
Project or Group Access Token (`api` scope).

1. In the target GitLab project: **Settings -> Access Tokens** (or a Group Access Token if you
   want it shared across several projects).
2. Create a token with the `api` scope and no higher than the `Developer` role (GitLab's Notes
   API requires the `api` scope; there is no narrower scope for posting/updating notes).
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

### 6. Copy the pipeline into the target repository

Copy `.gitlab-ci.yml` from this directory into the root of the target repository. If that
repository already has a `.gitlab-ci.yml`, merge this job into it rather than overwriting.

Adjust the `variables:` block for that repository:

```yaml
variables:
  FLOW_DIFF_PATH: "nifi/versioned-flow"   # directory with the NiFi flow exports to diff
```

### 7. Test it

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
