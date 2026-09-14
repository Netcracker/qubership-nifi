#!/usr/bin/env bash
# Drives the merge request pipeline shipped in flow-diff-ci-cd-pipeline/.gitlab-ci.yml against the
# GitLab instance started by .github/docker/flow-diff-gitlab/docker-compose.yaml.
#
# Sourced by .github/workflows/flow-diff-pipeline-test.yml, one scenario function per workflow step.
# Every function returns non-zero on failure and prints both operands of a failed comparison, so the
# workflow log alone says what broke. A scenario reports the first failure and stops only under
# `set -e`, which every step of the workflow sets; without it a scenario runs on and still returns 0.

# GitLab is reached from two places, and they do not always agree on the name.
#
# The runner and the job containers sit on the compose network and reach it at gitlab:8929, which is
# also the external_url GitLab bakes into CI_REPOSITORY_URL and CI_API_V4_URL.
GITLAB_INTERNAL_URL='http://gitlab:8929'
# The host side of the test uses that same name, which the workflow maps to 127.0.0.1. Set
# FLOW_DIFF_GITLAB_HOST to 127.0.0.1:8929 to run the test by hand where editing /etc/hosts is not
# practical; it changes only how this script reaches GitLab, never what the pipeline sees.
GITLAB_HOST="${FLOW_DIFF_GITLAB_HOST:-gitlab:8929}"
GITLAB_API="http://${GITLAB_HOST}/api/v4"
GITLAB_CONTAINER='flow-diff-gitlab'
RUNNER_CONTAINER='flow-diff-gitlab-runner'
PROJECT_NAME='flow-diff-it'

# The tag the workflow builds flow-diff-ci-cd-pipeline/Dockerfile as.
FLOW_DIFF_IMAGE='flow-diff-cli:it'

# The three strings below are the output this test asserts, so they are written out here rather
# than read from the pipeline. A test that took its expected values from the file under test would
# pass whatever that file said, and reworded output is a user-visible change that should fail.
# Everything else the test needs from the pipeline, adopt_pipeline_settings reads out of it.
MARKER='<!-- nifi-flow-diff -->'
NO_CHANGE_TEXT='No significant NiFi flow changes detected.'
CHANGE_TEXT='Significant and environmental changes to the NiFi flows modified in this MR:'

# Read out of the pipeline by adopt_pipeline_settings, and carried between steps in the context
# file, so that changing it in the pipeline needs no change here.
FLOW_DIFF_PATH=''

#
# Assertions
#

# Prints the failure and returns 1. Callers run under `set -e`, so returning is enough to stop them.
fail() {
    printf 'FAIL: %s\n' "$*" >&2
    return 1
}

assert_equals() {
    local want="$1" got="$2" context="$3"
    if [ "$want" != "$got" ]; then
        printf 'FAIL: %s\n  want: %s\n   got: %s\n' "$context" "$want" "$got" >&2
        return 1
    fi
    printf 'ok: %s is %s\n' "$context" "$got"
}

assert_contains() {
    local haystack="$1" needle="$2" context="$3"
    case "$haystack" in
        *"$needle"*)
            printf 'ok: %s contains %s\n' "$context" "$needle"
            ;;
        *)
            printf 'FAIL: %s does not contain the expected text\n  expected to contain: %s\n  actual: %s\n' \
                "$context" "$needle" "$haystack" >&2
            return 1
            ;;
    esac
}

#
# GitLab API
#

# gitlab_api <method> <path> [curl argument...]; prints the response body on success.
#
# On an error status it prints GitLab's own message to stderr and returns non-zero. Nearly every
# caller pipes this into jq inside a command substitution, which would otherwise discard the body
# and leave the reader with a bare "returned no project id" and no cause; --fail-with-body alone
# would only put the message where those callers throw it away.
#
# The || on the assignment keeps set -e from killing the caller before the report below runs.
gitlab_api() {
    local method="$1" path="$2" body status=0
    shift 2
    body="$(curl --silent --show-error --fail-with-body --request "$method" \
        --header "PRIVATE-TOKEN: ${FLOW_DIFF_GITLAB_TOKEN}" "$@" "${GITLAB_API}${path}")" || status=$?
    if [ "$status" -ne 0 ]; then
        printf 'GitLab %s %s failed (curl exit %s): %s\n' "$method" "$path" "$status" "$body" >&2
        return "$status"
    fi
    printf '%s' "$body"
}

#
# Setup
#

# adopt_pipeline_settings <pipeline file>; takes the pipeline's own configuration and checks the
# output strings this test asserts are still the ones it emits.
#
# The split is deliberate. FLOW_DIFF_PATH is configuration: the test only has to agree with the
# pipeline about where the flows live, so it reads the value and changing it needs no edit here.
# The marker and the two bodies are what the assertions compare against, so reading them from the
# file would make those assertions pass whatever the pipeline said. They are checked instead, and a
# reworded body fails here, naming the string, rather than minutes later as an unfindable note.
adopt_pipeline_settings() {
    local pipeline="$1" name value

    FLOW_DIFF_PATH="$(sed -n 's|^  FLOW_DIFF_PATH: *"\(.*\)"[[:space:]]*$|\1|p' "$pipeline" | head -1)"
    [ -n "$FLOW_DIFF_PATH" ] \
        || fail 'could not read FLOW_DIFF_PATH from the variables block of .gitlab-ci.yml'

    # An explicit return: fail inside a loop body only sets the exit status, and the loop runs on.
    for name in MARKER NO_CHANGE_TEXT CHANGE_TEXT; do
        value="${!name}"
        if ! grep -Fq "$value" "$pipeline"; then
            fail "flow-diff-ci-cd-pipeline/.gitlab-ci.yml no longer emits ${name}: ${value}"
            return 1
        fi
    done
    echo "ok: flows live under ${FLOW_DIFF_PATH}, and the pipeline still emits all three asserted strings"
}

# Creates the root personal access token the rest of the test authenticates with, and exports it as
# FLOW_DIFF_GITLAB_TOKEN. Seeding it through the Rails console rather than the sign-in form keeps
# the root password out of the test entirely.
seed_gitlab_token() {
    local token
    token="glpat-$(openssl rand -hex 12)"
    # The token reaches Ruby through the environment; interpolating it into the script would put it
    # in the process list of every container on the machine.
    docker exec --env "SEED_TOKEN=${token}" "$GITLAB_CONTAINER" gitlab-rails runner "$(cat <<'RUBY'
user = User.find_by_username("root")
raise "no root user" if user.nil?
params = { scopes: %w[api write_repository], name: "flow-diff-it", expires_at: 30.days.from_now }
# Some releases require an organization on a token and older ones define no such model at all,
# so ask before setting it.
params[:organization] = Organizations::Organization.default_organization if defined?(Organizations::Organization)
token = user.personal_access_tokens.create!(params)
token.set_token(ENV["SEED_TOKEN"])
token.save!
RUBY
    )" || fail 'could not seed the root personal access token'
    export FLOW_DIFF_GITLAB_TOKEN="$token"

    local username
    username="$(gitlab_api GET /user | jq -r '.username')"
    assert_equals 'root' "$username" 'the user of the seeded token'
}

# Registers the compose runner as an instance runner on the given compose network.
#
# Two settings carry the test. The job container joins the compose network, so it resolves the
# gitlab host name the same way the host side does. And if-not-present makes the runner use the
# locally built flow-diff-cli image; the default policy would try to pull it from Docker Hub.
register_gitlab_runner() {
    local network="$1"
    [ -n "$network" ] || fail 'register_gitlab_runner needs the compose network name'

    local runner_token
    runner_token="$(gitlab_api POST /user/runners \
        --data 'runner_type=instance_type' \
        --data 'description=flow-diff-it' \
        --data 'run_untagged=true' \
        --data 'locked=false' | jq -r '.token')"
    [ -n "$runner_token" ] && [ "$runner_token" != 'null' ] || fail 'GitLab returned no runner token'

    docker exec "$RUNNER_CONTAINER" gitlab-runner register --non-interactive \
        --url "$GITLAB_INTERNAL_URL" \
        --token "$runner_token" \
        --name 'flow-diff-it' \
        --executor docker \
        --docker-image 'alpine:3.22' \
        --docker-network-mode "$network" \
        --docker-pull-policy 'if-not-present' \
        || fail 'gitlab-runner register failed'

    wait_for_online_runner
}

# The runner reloads config.toml on its own, so it reaches GitLab a moment after registration. A job
# queued before that sits pending until the pipeline times out, which reads as a pipeline failure
# rather than as the setup problem it is.
wait_for_online_runner() {
    local deadline=$((SECONDS + 120)) online
    while [ "$SECONDS" -lt "$deadline" ]; do
        online="$(gitlab_api GET '/runners/all?status=online' \
            | jq -r '[.[] | select(.description == "flow-diff-it")] | length')"
        if [ "$online" = '1' ]; then
            echo 'ok: the runner is online'
            return 0
        fi
        sleep 5
    done
    gitlab_api GET /runners/all >&2 || true
    fail 'the runner did not come online within 120 seconds'
}

# Creates the test project, gives the pipeline its API token, and seeds main.
#
# Seeded files: the shipped pipeline with its image placeholder replaced, the baseline flow under
# FLOW_DIFF_PATH, and a README. Two of the three sit outside FLOW_DIFF_PATH, and the README is there
# because it is the harmless one: the out-of-scope scenario needs a file it can edit without
# touching the pipeline under test. Exports FLOW_DIFF_PROJECT_ID and FLOW_DIFF_REPO_DIR.
create_test_project() {
    local repo_root="$1" work_dir="$2"

    local project_id
    project_id="$(gitlab_api POST /projects \
        --data "name=${PROJECT_NAME}" \
        --data "path=${PROJECT_NAME}" \
        --data 'visibility=private' \
        --data 'initialize_with_readme=false' | jq -r '.id')"
    [ -n "$project_id" ] && [ "$project_id" != 'null' ] || fail 'GitLab returned no project id'
    export FLOW_DIFF_PROJECT_ID="$project_id"

    # The pipeline reads GITLAB_API_TOKEN to post its note. Unprotected because a merge request
    # pipeline runs on a merge request ref, never on a protected branch, and unmasked because
    # GitLab rejects a masked value it cannot mask.
    gitlab_api POST "/projects/${project_id}/variables" \
        --data 'key=GITLAB_API_TOKEN' \
        --data "value=${FLOW_DIFF_GITLAB_TOKEN}" \
        --data 'protected=false' \
        --data 'masked=false' > /dev/null

    rm -rf "$work_dir"
    mkdir -p "$work_dir"

    local pipeline="${work_dir}/.gitlab-ci.yml"
    cp "${repo_root}/flow-diff-ci-cd-pipeline/.gitlab-ci.yml" "$pipeline"
    adopt_pipeline_settings "$pipeline"

    # Point the job at the locally built image. The job block carries the file's only name: key, so
    # this needs no knowledge of which image or version the pipeline ships with.
    sed -i "s|^\\( *name:\\) .*|\\1 ${FLOW_DIFF_IMAGE}|" "$pipeline"
    grep -Fq "    name: ${FLOW_DIFF_IMAGE}" "$pipeline" \
        || fail "could not point the job's image: at ${FLOW_DIFF_IMAGE}"

    mkdir -p "${work_dir}/${FLOW_DIFF_PATH}"
    cp "${repo_root}/.github/configuration/flow-diff-gitlab/flow.json" "${work_dir}/${FLOW_DIFF_PATH}/flow.json"
    printf 'Test project for the qubership-nifi flow diff pipeline.\n' > "${work_dir}/README.md"

    git -C "$work_dir" init --quiet --initial-branch=main
    git -C "$work_dir" config user.email 'flow-diff-it'
    git -C "$work_dir" config user.name 'flow diff integration test'
    git -C "$work_dir" add -A
    git -C "$work_dir" commit --quiet -m 'seed the baseline flow'
    git -C "$work_dir" remote add origin \
        "http://oauth2:${FLOW_DIFF_GITLAB_TOKEN}@${GITLAB_HOST}/root/${PROJECT_NAME}.git"
    git -C "$work_dir" push --quiet origin main
    export FLOW_DIFF_REPO_DIR="$work_dir"

    echo "ok: seeded project ${project_id} with ${FLOW_DIFF_PATH}/flow.json on main"
}

#
# Repository edits
#

# apply_flow_change <message> <jq program>; rewrites the flow on the current branch, commits, and
# pushes. Shared by start_flow_branch and push_flow_change, which differ only in the commit they
# build on.
apply_flow_change() {
    local message="$1" program="$2"
    local flow="${FLOW_DIFF_REPO_DIR}/${FLOW_DIFF_PATH}/flow.json"

    jq "$program" "$flow" > "${flow}.new" && mv "${flow}.new" "$flow"
    # An unchanged file would leave the job's changes: guard unmatched, and the scenario would then
    # assert nothing while still reporting success.
    if git -C "$FLOW_DIFF_REPO_DIR" diff --quiet -- "${FLOW_DIFF_PATH}/flow.json"; then
        fail "the jq program left ${FLOW_DIFF_PATH}/flow.json unchanged"
    fi
    git -C "$FLOW_DIFF_REPO_DIR" commit --quiet -am "$message"
    git -C "$FLOW_DIFF_REPO_DIR" push --quiet --force origin "$(git -C "$FLOW_DIFF_REPO_DIR" branch --show-current)"
}

# start_flow_branch <branch> <message> <jq program>; branches off main and pushes one flow change, so
# the branch carries that change alone.
start_flow_branch() {
    local branch="$1" message="$2" program="$3"
    git -C "$FLOW_DIFF_REPO_DIR" checkout --quiet -B "$branch" main
    apply_flow_change "$message" "$program"
}

# push_flow_change <branch> <message> <jq program>; adds a second commit to a branch that already has
# one, which is what a merge request author does when they push again.
push_flow_change() {
    local branch="$1" message="$2" program="$3"
    git -C "$FLOW_DIFF_REPO_DIR" checkout --quiet "$branch"
    apply_flow_change "$message" "$program"
}

# commit_readme_change <branch> <message>; touches the seeded README, the one file outside
# FLOW_DIFF_PATH that is not the pipeline under test.
commit_readme_change() {
    local branch="$1" message="$2"

    git -C "$FLOW_DIFF_REPO_DIR" checkout --quiet -B "$branch" main
    printf 'A change outside %s.\n' "$FLOW_DIFF_PATH" >> "${FLOW_DIFF_REPO_DIR}/README.md"
    git -C "$FLOW_DIFF_REPO_DIR" commit --quiet -am "$message"
    git -C "$FLOW_DIFF_REPO_DIR" push --quiet --force origin "$branch"
}

#
# Merge request queries
#

# open_mr <branch> <title>; prints the new merge request's iid.
open_mr() {
    local branch="$1" title="$2" iid
    iid="$(gitlab_api POST "/projects/${FLOW_DIFF_PROJECT_ID}/merge_requests" \
        --data "source_branch=${branch}" \
        --data 'target_branch=main' \
        --data "title=${title}" | jq -r '.iid // empty')"
    # GitLab answers 409 when a merge request for the branch is already open, and the error body has
    # no iid. Without this the scenario would run every later call against the literal "null" and
    # still report success, because those calls fail and the assertions then compare empty values.
    [ -n "$iid" ] || fail "GitLab opened no merge request for ${branch}"
    printf '%s' "$iid"
}

# wait_for_new_mr_pipeline <iid> <after-id>; prints the id of the first pipeline newer than
# <after-id>. Identifying the pipeline by id rather than by commit sha keeps the second push of the
# sticky-update scenario from matching the pipeline the first push created.
wait_for_new_mr_pipeline() {
    local iid="$1" after_id="$2"
    local deadline=$((SECONDS + 180)) pipeline_id
    while [ "$SECONDS" -lt "$deadline" ]; do
        pipeline_id="$(gitlab_api GET "/projects/${FLOW_DIFF_PROJECT_ID}/merge_requests/${iid}/pipelines" \
            | jq -r --argjson after "$after_id" '[.[] | select(.id > $after)] | sort_by(.id) | .[0].id // empty')"
        if [ -n "$pipeline_id" ]; then
            printf '%s' "$pipeline_id"
            return 0
        fi
        sleep 5
    done
    fail "no pipeline newer than ${after_id} appeared on merge request ${iid} within 180 seconds"
}

# wait_for_pipeline <pipeline-id>; prints its terminal status.
wait_for_pipeline() {
    local pipeline_id="$1"
    local deadline=$((SECONDS + 240)) status=''
    while [ "$SECONDS" -lt "$deadline" ]; do
        status="$(gitlab_api GET "/projects/${FLOW_DIFF_PROJECT_ID}/pipelines/${pipeline_id}" | jq -r '.status')"
        case "$status" in
            success | failed | canceled | skipped)
                printf '%s' "$status"
                return 0
                ;;
        esac
        sleep 5
    done
    fail "pipeline ${pipeline_id} was still ${status} after 240 seconds"
}

# flow_diff_note <iid>; prints the single note whose body opens with the marker, as JSON. Prints
# nothing when there is none, and fails when the pipeline has posted more than one, which is the
# sticky note breaking.
flow_diff_note() {
    local iid="$1" notes count
    notes="$(gitlab_api GET "/projects/${FLOW_DIFF_PROJECT_ID}/merge_requests/${iid}/notes?per_page=100" \
        | jq --arg marker "$MARKER" '[.[] | select(.body | startswith($marker))]')"
    count="$(jq 'length' <<< "$notes")"
    [ -n "$count" ] || fail "could not read the notes of merge request ${iid}"
    case "$count" in
        0) return 0 ;;
        1) jq '.[0]' <<< "$notes" ;;
        *) fail "the merge request carries ${count} notes with the ${MARKER} marker, expected at most one" ;;
    esac
}

# flow_diff_jobs <pipeline-id>; prints the name of every flow-diff job in the pipeline, one per line.
flow_diff_jobs() {
    local pipeline_id="$1"
    gitlab_api GET "/projects/${FLOW_DIFF_PROJECT_ID}/pipelines/${pipeline_id}/jobs" \
        | jq -r '.[] | select(.name == "flow-diff") | .name'
}

#
# Diagnostics
#

# dump_pipeline_jobs <pipeline-id>; prints the trace of every job of a pipeline. Called when a
# scenario fails, so the workflow log carries the job's own output rather than only its status.
dump_pipeline_jobs() {
    local pipeline_id="$1" job_id
    echo "--- jobs of pipeline ${pipeline_id} ---"
    for job_id in $(gitlab_api GET "/projects/${FLOW_DIFF_PROJECT_ID}/pipelines/${pipeline_id}/jobs" | jq -r '.[].id'); do
        echo "--- trace of job ${job_id} ---"
        gitlab_api GET "/projects/${FLOW_DIFF_PROJECT_ID}/jobs/${job_id}/trace" || true
        echo
    done
}

#
# Test context
#

# Each workflow step runs in its own shell, so the ids the scenarios share travel through a file, as
# the NiFi autotest modes already pass their tokens. Cleanup deletes it.
FLOW_DIFF_CONTEXT_FILE='./flow-diff-gitlab-context.env'

save_test_context() {
    local previous_umask
    previous_umask="$(umask)"
    umask 077
    {
        printf 'FLOW_DIFF_GITLAB_TOKEN=%s\n' "${FLOW_DIFF_GITLAB_TOKEN:-}"
        printf 'FLOW_DIFF_PROJECT_ID=%s\n' "${FLOW_DIFF_PROJECT_ID:-}"
        printf 'FLOW_DIFF_REPO_DIR=%s\n' "${FLOW_DIFF_REPO_DIR:-}"
        printf 'FLOW_DIFF_PATH=%s\n' "${FLOW_DIFF_PATH:-}"
        printf 'FLOW_DIFF_MR_IID=%s\n' "${FLOW_DIFF_MR_IID:-}"
        printf 'FLOW_DIFF_NOTE_ID=%s\n' "${FLOW_DIFF_NOTE_ID:-}"
        printf 'FLOW_DIFF_PIPELINE_ID=%s\n' "${FLOW_DIFF_PIPELINE_ID:-}"
        printf 'FLOW_DIFF_SKIP_MR_IID=%s\n' "${FLOW_DIFF_SKIP_MR_IID:-}"
    } > "$FLOW_DIFF_CONTEXT_FILE"
    umask "$previous_umask"
}

load_test_context() {
    [ -f "$FLOW_DIFF_CONTEXT_FILE" ] || fail "${FLOW_DIFF_CONTEXT_FILE} is missing; the setup step did not finish"
    set -a
    # shellcheck disable=SC1090
    . "$FLOW_DIFF_CONTEXT_FILE"
    set +a
}

#
# Scenarios
#

# run_mr_pipeline <iid> <after-id>; waits for the merge request's next pipeline to finish, asserts it
# succeeded, and prints its id. Dumps every job trace first when it did not, because the status alone
# never says which line of the pipeline script failed.
run_mr_pipeline() {
    local iid="$1" after_id="$2" pipeline_id status
    pipeline_id="$(wait_for_new_mr_pipeline "$iid" "$after_id")"
    status="$(wait_for_pipeline "$pipeline_id")"
    if [ "$status" != 'success' ]; then
        dump_pipeline_jobs "$pipeline_id" >&2
    fi
    assert_equals 'success' "$status" "status of pipeline ${pipeline_id} on merge request ${iid}" >&2
    printf '%s' "$pipeline_id"
}

# A merge request that changes a processor property gets a note naming the changed property.
scenario_significant_change() {
    start_flow_branch 'flow-significant' 'narrow the status filter' \
        '(.flowContents.processors[] | select(.name == "Filter status>0") | .properties["SQL Query"])
            |= sub("status > 0"; "status > 1")'

    local iid pipeline_id note body
    iid="$(open_mr 'flow-significant' 'Narrow the status filter')"
    pipeline_id="$(run_mr_pipeline "$iid" 0)"

    note="$(flow_diff_note "$iid")"
    [ -n "$note" ] || fail "merge request ${iid} carries no note opening with ${MARKER}"
    body="$(jq -r '.body' <<< "$note")"
    assert_contains "$body" "$CHANGE_TEXT" "the note on merge request ${iid}"
    assert_contains "$body" 'SQL Query' "the note on merge request ${iid}"

    # The sticky-update scenario pushes a second commit onto this same merge request.
    FLOW_DIFF_MR_IID="$iid"
    FLOW_DIFF_PIPELINE_ID="$pipeline_id"
    FLOW_DIFF_NOTE_ID="$(jq -r '.id' <<< "$note")"
    save_test_context
    echo "ok: note ${FLOW_DIFF_NOTE_ID} posted on merge request ${iid}"
}

# A second push updates that note in place rather than adding another one.
scenario_sticky_update() {
    [ -n "${FLOW_DIFF_MR_IID:-}" ] || fail 'no merge request in the context; the first scenario did not finish'

    # A note the pipeline's lookup has to skip over. Without it the lookup would find the right note
    # even with its marker filter removed, because the sticky note is the only one on the merge
    # request; with it, a lookup that stopped filtering would update this one instead and the body
    # assertion below would go red.
    gitlab_api POST "/projects/${FLOW_DIFF_PROJECT_ID}/merge_requests/${FLOW_DIFF_MR_IID}/notes" \
        --data 'body=A reviewer comment that carries no marker.' > /dev/null

    push_flow_change 'flow-significant' 'raise the LogAttribute log level' \
        '(.flowContents.processors[] | select(.name == "LogAttribute") | .properties["Log Level"]) = "debug"'

    local pipeline_id note body
    pipeline_id="$(run_mr_pipeline "$FLOW_DIFF_MR_IID" "$FLOW_DIFF_PIPELINE_ID")"
    echo "ok: pipeline ${pipeline_id} ran for the second push"

    note="$(flow_diff_note "$FLOW_DIFF_MR_IID")"
    [ -n "$note" ] || fail "merge request ${FLOW_DIFF_MR_IID} lost its ${MARKER} note on the second push"
    assert_equals "$FLOW_DIFF_NOTE_ID" "$(jq -r '.id' <<< "$note")" \
        "the note id on merge request ${FLOW_DIFF_MR_IID} after the second push"

    # The branch now carries both changes, so the updated body names the second one as well. Without
    # this the assertion above would also pass on a pipeline that never updated the note.
    body="$(jq -r '.body' <<< "$note")"
    assert_contains "$body" 'Log Level' "the updated note on merge request ${FLOW_DIFF_MR_IID}"
}

# A merge request whose flow changes are all technical gets the no-change note.
scenario_technical_change() {
    # Every instanceIdentifier is rewritten and every identifier left alone, which the classifier in
    # qubership-nifi-flow-diff-core counts as technical. The file still changes, so the job's
    # changes: guard matches and the job runs; only its totals gate decides the body.
    start_flow_branch 'flow-technical' 'rewrite the NiFi instance identifiers' \
        'walk(if type == "object" and has("instanceIdentifier")
            then .instanceIdentifier |= ("0000" + .[4:]) else . end)'

    local iid note body
    iid="$(open_mr 'flow-technical' 'Rewrite the NiFi instance identifiers')"
    run_mr_pipeline "$iid" 0 > /dev/null

    note="$(flow_diff_note "$iid")"
    [ -n "$note" ] || fail "merge request ${iid} carries no note opening with ${MARKER}"
    body="$(jq -r '.body' <<< "$note")"
    assert_contains "$body" "$NO_CHANGE_TEXT" "the note on merge request ${iid}"
}

# A merge request that touches nothing under FLOW_DIFF_PATH runs no flow-diff job and gets no note.
#
# Split in two because the assertion is that nothing happened, which no amount of polling can
# establish. Opening the merge request before the other scenarios and asserting on it after them
# replaces a clock with a signal: by then the other three have each had GitLab create, schedule, and
# finish a pipeline, so an instance that was merely slow has been shown to be working.
open_out_of_scope_mr() {
    commit_readme_change 'docs-only' 'extend the readme'
    FLOW_DIFF_SKIP_MR_IID="$(open_mr 'docs-only' 'Extend the readme')"
    save_test_context
    echo "ok: opened merge request ${FLOW_DIFF_SKIP_MR_IID} touching nothing under ${FLOW_DIFF_PATH}"
}

assert_out_of_scope_skipped() {
    [ -n "${FLOW_DIFF_SKIP_MR_IID:-}" ] \
        || fail 'no out-of-scope merge request in the context; the setup step did not finish'

    local iid="$FLOW_DIFF_SKIP_MR_IID" pipelines count pipeline_id jobs note

    # GitLab creates no pipeline at all when the only job's rules exclude it, so this list is
    # normally empty. Collecting the job names across whatever pipelines exist keeps the assertion
    # on the straight-line path either way: iterating the assertion instead would assert nothing on
    # an empty list, and a GitLab that did create an empty pipeline must still carry no flow-diff job.
    pipelines="$(gitlab_api GET "/projects/${FLOW_DIFF_PROJECT_ID}/merge_requests/${iid}/pipelines")"
    count="$(jq 'length' <<< "$pipelines")"
    jobs=''
    for pipeline_id in $(jq -r '.[].id' <<< "$pipelines"); do
        jobs="${jobs}$(flow_diff_jobs "$pipeline_id")"
    done
    assert_equals '' "$jobs" "flow-diff jobs across the ${count} pipeline(s) of merge request ${iid}"

    note="$(flow_diff_note "$iid")"
    assert_equals '' "$note" "notes opening with ${MARKER} on merge request ${iid}"
}
