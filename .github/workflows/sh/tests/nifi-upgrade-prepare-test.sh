#!/bin/bash
# Tests for nifi-upgrade-prepare.sh. getFilesFromDocker.sh, which pulls the Docker images, is replaced by a script
# that records its arguments in the file calls and exits with the status in docker-status, 0 by default. mvn is
# replaced by a stub that records its arguments in calls too, and fails when they contain the text in mvn-fail.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

# Sets up a Dockerfile with the current version 2.7.2, the getFilesFromDocker.sh replacement, and the mvn stub.
setup_prepare() {
    cat >Dockerfile <<'EOF'
ARG NIFI_VERSION='2.7.2'
ARG NIFI_VERSION_SHA256='sha256:0000'
FROM apache/nifi:$NIFI_VERSION@$NIFI_VERSION_SHA256 AS nifi
ARG NIFI_VERSION
EOF
    mkdir -p .claude/skills/nifi-upgrade-skill/scripts
    cat >.claude/skills/nifi-upgrade-skill/scripts/getFilesFromDocker.sh <<EOF
echo "getFilesFromDocker.sh \$*" >>"$TEST_DIR/calls"
exit "\$(cat "$TEST_DIR/docker-status" 2>/dev/null || echo 0)"
EOF
    stub mvn <<EOF
#!/bin/bash
echo "mvn \$*" >>"$TEST_DIR/calls"
if [ -f "$TEST_DIR/mvn-fail" ] && [[ "\$*" == *"\$(cat "$TEST_DIR/mvn-fail")"* ]]; then
    exit 1
fi
EOF
}

run_prepare() {
    run env NIFI_VERSION=2.13.0 bash "$SH_DIR/nifi-upgrade-prepare.sh"
}

test_the_reference_files_are_fetched_for_the_current_and_the_target_version() {
    setup_prepare
    run_prepare
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$(cat calls)" "getFilesFromDocker.sh 2.7.2 2.13.0" "calls"
}

test_the_properties_of_each_version_are_exported_to_its_own_directory() {
    setup_prepare
    run_prepare
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$(cat calls)" \
        "--version 2.7.2 --output-dir ./upgrade-temp-data/nifi-property-exports/2.7.2" "calls"
    assert_contains "$(cat calls)" \
        "--version 2.13.0 --output-dir ./upgrade-temp-data/nifi-property-exports/2.13.0" "calls"
}

test_the_export_tool_is_installed_before_the_exports_run() {
    setup_prepare
    run_prepare
    local install_line export_line
    install_line=$(grep -n " install .*-pl qubership-nifi-tools/qubership-nifi-api-export-tool" calls | cut -d: -f1)
    export_line=$(grep -n " exec:java " calls | head -1 | cut -d: -f1)
    if [ -z "$install_line" ] || [ -z "$export_line" ] || [ "$install_line" -gt "$export_line" ]; then
        fail "expected the install of the export tool before the first export, but the calls were <$(cat calls)>"
    fi
}

test_a_missing_target_version_fails_before_anything_runs() {
    setup_prepare
    run env -u NIFI_VERSION bash "$SH_DIR/nifi-upgrade-prepare.sh"
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "NIFI_VERSION is not set" "output"
    assert_file_missing calls
}

test_a_dockerfile_without_the_current_version_fails_before_anything_runs() {
    setup_prepare
    echo "ARG NIFI_VERSION" >Dockerfile
    run_prepare
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "::error::Cannot read the current Apache NiFi version from the Dockerfile." "output"
    assert_file_missing calls
}

test_a_failed_fetch_of_the_reference_files_stops_before_maven() {
    setup_prepare
    echo 1 >docker-status
    run_prepare
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_not_contains "$(cat calls)" "mvn " "calls"
}

test_a_failed_export_of_the_current_version_fails_the_script() {
    setup_prepare
    echo "--version 2.7.2 " >mvn-fail
    run_prepare
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_not_contains "$(cat calls)" "--version 2.13.0 " "calls"
}

run_tests
