#!/bin/bash
# Finds the Apache NiFi version to upgrade to and writes version, digest, and branch
# to $GITHUB_OUTPUT. Outside GitHub Actions, where GITHUB_OUTPUT is unset, it prints them.
# All three values are empty when there is nothing to upgrade to.
#
# A version qualifies when its apache/nifi Docker Hub tag is X.Y.Z with the current
# major version, is newer than the current version, and is old enough: pushed at least
# MIN_AGE_DAYS_PATCH ago when only the patch version differs from the current one, and at
# least MIN_AGE_DAYS_MINOR ago when the minor version differs. The highest qualifying
# version is chosen.
#
# When TARGET_VERSION is set, that version is used instead of the newest qualifying one.
# It must be X.Y.Z with the current major version, be newer than the current version, and
# have a tag on Docker Hub; otherwise the script fails. The minimum ages do not apply to it.
#
# When the branch of the chosen version, feat/upgrade-nifi-to-<version>, exists, a scheduled
# run skips the version, and any other run, such as a manual one, fails and asks to delete the
# branch, and to close the pull requests from it when there are any. Pull requests from a
# deleted branch are ignored, so deleting the branch lets the version be upgraded to again.
#
# Environment:
#   TARGET_VERSION     version to upgrade to; the newest qualifying version when unset or empty
#   CURRENT_VERSION    current version; read from nifi.version in pom.xml when unset
#   MIN_AGE_DAYS_PATCH minimum tag age in days for a patch version change, 7 by default
#   MIN_AGE_DAYS_MINOR minimum tag age in days for a minor version change, 14 by default
#   TAGS_URL           first page of the apache/nifi tag list, Docker Hub by default; a single tag is read
#                      from the same URL without the query, followed by /<version>
#   GH_TOKEN           token for the GitHub CLI
#   GITHUB_REPOSITORY  owner/name of the repository; gh uses the git remote when unset
#   GITHUB_EVENT_NAME  event that started the workflow run, set by GitHub Actions; schedule skips an existing
#                      branch, and any other value, or none, fails on it
#   GITHUB_OUTPUT      file for the step outputs; set by GitHub Actions

set -euo pipefail

MIN_AGE_DAYS_PATCH="${MIN_AGE_DAYS_PATCH:-7}"
MIN_AGE_DAYS_MINOR="${MIN_AGE_DAYS_MINOR:-14}"
TAGS_URL="${TAGS_URL:-https://hub.docker.com/v2/repositories/apache/nifi/tags?page_size=100}"

write_output() {
    local version="$1" digest="$2" branch="$3"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        {
            echo "version=$version"
            echo "digest=$digest"
            echo "branch=$branch"
        } >>"$GITHUB_OUTPUT"
    else
        echo "version=$version"
        echo "digest=$digest"
        echo "branch=$branch"
    fi
}

read_current_version() {
    local version
    version=$(sed -n 's:.*<nifi\.version>\([^<]*\)</nifi\.version>.*:\1:p' pom.xml | head -n 1)
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "::error::Cannot read nifi.version from pom.xml, found '$version'." >&2
        return 1
    fi
    echo "$version"
}

# Prints every apache/nifi tag as one JSON object per line, following pagination.
fetch_tags() {
    local url="$TAGS_URL" page
    while [ -n "$url" ] && [ "$url" != "null" ]; do
        # The caller runs this function inside a command substitution, where set -e is off,
        # so each failure has to return explicitly.
        page=$(curl --fail --silent --show-error --retry 3 "$url") || return 1
        echo "$page" | jq -c '.results[]' || return 1
        url=$(echo "$page" | jq -r '.next') || return 1
    done
}

# Reads tags from stdin and prints {version, digest, pushed} for the newest
# qualifying version, or {} when none qualifies.
select_version() {
    local current="$1"
    jq -s -c \
        --arg current "$current" \
        --argjson min_age_days_patch "$MIN_AGE_DAYS_PATCH" \
        --argjson min_age_days_minor "$MIN_AGE_DAYS_MINOR" '
        def semver: split(".") | map(tonumber);
        ($current | semver) as $cur
        | now as $now
        | map(select(
            (.name | test("^[0-9]+[.][0-9]+[.][0-9]+$"))
            and .tag_last_pushed != null
            and .digest != null
        ))
        | map(. + {semver: (.name | semver)})
        | map(select(
            .semver[0] == $cur[0]
            and .semver > $cur
            and (.tag_last_pushed | sub("[.][0-9]+Z$"; "Z") | fromdateiso8601)
                <= $now - (if .semver[1] == $cur[1] then $min_age_days_patch else $min_age_days_minor end) * 86400
        ))
        | max_by(.semver)
        | if . == null then {} else {version: .name, digest: .digest, pushed: .tag_last_pushed} end
        '
}

# Checks the requested version against the current one and prints {version, digest, pushed}
# for its Docker Hub tag. Fails when the version does not qualify or has no tag.
select_target_version() {
    local current="$1" target="$2" tag
    if [[ ! "$target" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "::error::TARGET_VERSION must be X.Y.Z, found '$target'." >&2
        return 1
    fi
    if [ "${target%%.*}" != "${current%%.*}" ]; then
        echo "::error::Apache NiFi $target has a different major version than the current $current." >&2
        return 1
    fi
    if [ "$(printf '%s\n%s\n' "$current" "$target" | sort -V | tail -n 1)" != "$target" ] \
        || [ "$target" = "$current" ]; then
        echo "::error::Apache NiFi $target is not newer than the current $current." >&2
        return 1
    fi
    if ! tag=$(curl --fail --silent --show-error --retry 3 "${TAGS_URL%%\?*}/$target"); then
        echo "::error::Cannot read the apache/nifi:$target tag from Docker Hub." >&2
        return 1
    fi
    # select_version skips a tag without a digest, and the Dockerfile needs one, so such a tag is an error here.
    if [ "$(echo "$tag" | jq -r '.digest // ""')" = "" ]; then
        echo "::error::The apache/nifi:$target tag on Docker Hub has no digest." >&2
        return 1
    fi
    echo "$tag" | jq -c '{version: .name, digest: .digest, pushed: .tag_last_pushed}'
}

# Prints the ref of the branch when the branch exists in the repository, and nothing otherwise.
find_branch() {
    local branch="$1" repo="${GITHUB_REPOSITORY:-}"
    if [ -z "$repo" ]; then
        # gh fills in the placeholders from the git remote.
        repo='{owner}/{repo}'
    fi
    # matching-refs returns an empty list rather than 404 for a missing branch, so any error is a real one.
    gh api "repos/$repo/git/matching-refs/heads/$branch" \
        --jq ".[] | select(.ref == \"refs/heads/$branch\") | .ref"
}

# Prints "#<number> (<state>)" for each pull request from the branch, in any state.
find_pull_requests() {
    local branch="$1"
    local repo_args=()
    if [ -n "${GITHUB_REPOSITORY:-}" ]; then
        repo_args=(--repo "$GITHUB_REPOSITORY")
    fi
    gh pr list "${repo_args[@]}" --head "$branch" --state all \
        --json number,state --jq '.[] | "#\(.number) (\(.state))"'
}

main() {
    local current result version digest branch existing prs

    current="${CURRENT_VERSION:-}"
    if [ -z "$current" ]; then
        current=$(read_current_version)
    fi
    echo "Current Apache NiFi version: $current"

    if [ -n "${TARGET_VERSION:-}" ]; then
        result=$(select_target_version "$current" "$TARGET_VERSION")
    else
        result=$(fetch_tags | select_version "$current")
    fi
    version=$(echo "$result" | jq -r '.version // ""')
    digest=$(echo "$result" | jq -r '.digest // ""')
    if [ -z "$version" ]; then
        echo "No Apache NiFi version newer than $current is old enough: $MIN_AGE_DAYS_PATCH days for a patch version, $MIN_AGE_DAYS_MINOR days for a minor version."
        write_output "" "" ""
        return 0
    fi
    echo "Found Apache NiFi $version, pushed $(echo "$result" | jq -r '.pushed'), digest $digest."

    branch="feat/upgrade-nifi-to-$version"
    existing=$(find_branch "$branch")
    if [ -n "$existing" ]; then
        # A scheduled run finds the same version every week until it is upgraded to, so it skips the version.
        if [ "${GITHUB_EVENT_NAME:-}" = schedule ]; then
            echo "Branch $branch already exists, skipping the update." \
                "Delete the branch to upgrade to Apache NiFi $version again."
            write_output "" "" ""
            return 0
        fi
        prs=$(find_pull_requests "$branch")
        if [ -n "$prs" ]; then
            echo "::error::Branch $branch and pull requests from it already exist:" \
                "$(echo "$prs" | paste -sd ' ' -). To upgrade to Apache NiFi $version again," \
                "close the pull requests, delete the branch, and run the workflow again." >&2
        else
            echo "::error::Branch $branch already exists. To upgrade to Apache NiFi $version again," \
                "delete the branch and run the workflow again." >&2
        fi
        return 1
    fi

    write_output "$version" "$digest" "$branch"
}

main "$@"
