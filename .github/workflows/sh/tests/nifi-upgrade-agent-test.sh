#!/bin/bash
# Tests for nifi-upgrade-agent.sh. mvn is replaced by a stub that prints the versions a test sets up, and opencode
# by a stub that records the prompt and the environment of the agent run, and answers the database queries of
# write_signature with the JSON files a test writes.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

# The signature starts with the robot face emoji, U+1F916; the scripts keep to ASCII, so it is built from bytes.
ROBOT=$(printf '\xf0\x9f\xa4\x96')

# The mvn stub prints project-version and nifi-version for help:evaluate of project.version and nifi.version.
stub_mvn() {
    stub mvn <<'EOF'
#!/bin/bash
for arg in "$@"; do
    case "$arg" in
        -Dexpression=project.version) cat project-version ;;
        -Dexpression=nifi.version) cat nifi-version ;;
    esac
done
EOF
}

# The opencode stub saves the prompt of opencode run to prompt and QUBERSHIP_NIFI_VERSION to qubership-version,
# writes report to REPORT_FILE when the file report exists, and exits with the status in run-status, 0 by default.
# opencode db prints main.json for the query of the main session, and totals.json for any other query.
stub_opencode() {
    stub opencode <<'EOF'
#!/bin/bash
case "$1" in
    run)
        printf '%s' "${@: -1}" >prompt
        printf '%s' "$QUBERSHIP_NIFI_VERSION" >qubership-version
        if [ -f report ]; then
            cp report "$REPORT_FILE"
        fi
        exit "$(cat run-status 2>/dev/null || echo 0)"
        ;;
    db)
        if [[ "${@: -1}" == *"parent_id is null"* ]]; then
            cat main.json
        else
            cat totals.json
        fi
        ;;
esac
EOF
}

# Sets up a pom with qubership-nifi 2.3.2-SNAPSHOT on Apache NiFi 2.7.2, and an agent run of model-a with high
# reasoning effort.
setup_run() {
    stub_mvn
    stub_opencode
    echo 2.3.2-SNAPSHOT >project-version
    echo 2.7.2 >nifi-version
    echo '[{"model": "model-a", "effort": "high"}]' >main.json
    echo '[{"input": 105900, "cached": 3927200, "output": 21600, "cost": 0.0834}]' >totals.json
}

run_agent() {
    local nifi_version="$1"
    # REPORT_FILE is set as the workflow sets it, so that the opencode stub sees it too.
    run env NIFI_VERSION="$nifi_version" NIFI_DIGEST=sha256:abc REPORT_FILE=upgrade-temp-data/pr-body.md \
        bash "$SH_DIR/nifi-upgrade-agent.sh"
}

test_a_patch_upgrade_releases_the_snapshot_version() {
    setup_run
    run_agent 2.7.3
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq 2.3.2 "$(cat qubership-version)" "QUBERSHIP_NIFI_VERSION of the agent"
    assert_contains "$(cat prompt)" "use 2.3.2 as the planned qubership-nifi release version" "prompt"
}

test_a_minor_upgrade_releases_the_next_minor_version() {
    setup_run
    run_agent 2.8.0
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq 2.4.0 "$(cat qubership-version)" "QUBERSHIP_NIFI_VERSION of the agent"
}

test_a_set_qubership_nifi_version_is_kept() {
    setup_run
    QUBERSHIP_NIFI_VERSION=2.5.1 run_agent 2.8.0
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq 2.5.1 "$(cat qubership-version)" "QUBERSHIP_NIFI_VERSION of the agent"
}

test_an_unreadable_project_version_fails_before_the_agent_runs() {
    setup_run
    # shellcheck disable=SC2016
    echo '${revision}' >project-version
    run_agent 2.7.3
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "Cannot read the qubership-nifi version from pom.xml, found '\${revision}'" "output"
    assert_file_missing prompt
}

test_the_prompt_carries_the_target_version_and_digest() {
    setup_run
    run_agent 2.7.3
    assert_contains "$(cat prompt)" "- Target NiFi version: 2.7.3" "prompt"
    assert_contains "$(cat prompt)" "- SHA256 hash: sha256:abc" "prompt"
}

test_the_signature_sums_the_tokens_and_rounds_the_cost() {
    setup_run
    run_agent 2.7.3
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "<sub>$ROBOT model-a high in=105.9k cached=3927.2k out=21.6k usd=0.083</sub>" \
        "$(cat upgrade-temp-data/agent-signature.md)" "signature"
}

test_the_signature_leaves_out_an_unknown_effort() {
    setup_run
    echo '[{"model": "model-a", "effort": null}]' >main.json
    run_agent 2.7.3
    assert_contains "$(cat upgrade-temp-data/agent-signature.md)" "$ROBOT model-a in=" "signature"
}

test_the_signature_prints_counts_below_a_thousand_as_they_are() {
    setup_run
    echo '[{"input": 999, "cached": 0, "output": 1000, "cost": 0}]' >totals.json
    run_agent 2.7.3
    assert_contains "$(cat upgrade-temp-data/agent-signature.md)" "in=999 cached=0 out=1k usd=0</sub>" "signature"
}

test_the_signature_rounds_the_cost_to_two_significant_digits() {
    local cost expected
    setup_run
    while read -r cost expected; do
        echo "[{\"input\": 0, \"cached\": 0, \"output\": 0, \"cost\": $cost}]" >totals.json
        run_agent 2.7.3
        assert_contains "$(cat upgrade-temp-data/agent-signature.md)" "usd=$expected</sub>" "signature for cost $cost"
    done <<'EOF'
1.234 1.2
9.96 10
123.4 120
0.0995 0.1
0.000456 0.00046
EOF
}

test_a_failed_agent_run_keeps_its_exit_status_and_still_writes_the_signature() {
    setup_run
    echo 3 >run-status
    run_agent 2.7.3
    assert_eq 3 "$STATUS" "exit status"
    assert_contains "$(cat upgrade-temp-data/agent-signature.md)" "model-a high" "signature"
}

test_a_run_without_a_model_response_writes_no_signature() {
    setup_run
    echo '[]' >main.json
    run_agent 2.7.3
    assert_eq 0 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "::warning::Cannot read the token usage of the agent" "output"
    assert_file_missing upgrade-temp-data/agent-signature.md
}

test_a_missing_report_is_warned_about() {
    setup_run
    run_agent 2.7.3
    assert_contains "$OUTPUT" "::warning::The agent did not write the report to upgrade-temp-data/pr-body.md" "output"
}

test_a_written_report_is_not_warned_about() {
    setup_run
    echo "Upgraded." >report
    run_agent 2.7.3
    assert_eq "Upgraded." "$(cat upgrade-temp-data/pr-body.md)" "report"
    assert_not_contains "$OUTPUT" "did not write the report" "output"
}

run_tests
