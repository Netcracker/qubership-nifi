#!/usr/bin/env bash
set -Eeuo pipefail
if [[ $# -ne 2 ]]; then
    echo "Error: Incorrect number of arguments."
    echo "Usage: $0 <old_nifi_version> <new_nifi_version>"
    echo "Example: $0 1.27.0 2.0.0"
    exit 1
fi
OLD_VER="$1"
NEW_VER="$2"
BASE_DIR="filesCompare"

echo "Creating directory structure..."
mkdir -p "${BASE_DIR}/scripts/${OLD_VER}" \
         "${BASE_DIR}/scripts/${NEW_VER}" \
         "${BASE_DIR}/config/${OLD_VER}" \
         "${BASE_DIR}/config/${NEW_VER}"

extract_files_for_version() {
    local ver="$1"
    local image="apache/nifi:${ver}"
    local s_dir="${BASE_DIR}/scripts/${ver}"
    local c_dir="${BASE_DIR}/config/${ver}"

    echo -e "\nProcessing ${image}..."

    local cid
    cid=$(docker create "$image") || {
        echo "Error: Failed to create container for ${image}."
        exit 1
    }

    echo "Copying scripts..."
    docker cp "${cid}:/opt/nifi/scripts/." "${s_dir}/" 2>/dev/null || echo "Warning: /opt/nifi/scripts not found"

    echo "Copying configs..."
    docker cp "${cid}:/opt/nifi/nifi-current/conf/." "${c_dir}/" 2>/dev/null || echo "Warning: /opt/nifi/nifi-current/conf not found"

    echo "Removing temporary container ${cid}..."
    docker rm -f "$cid" &>/dev/null || true
}

echo "Starting extraction for versions: ${OLD_VER} and ${NEW_VER}"
extract_files_for_version "$OLD_VER"
extract_files_for_version "$NEW_VER"

echo -e "\nDone! Files saved in '${BASE_DIR}/'"
echo "Resulting structure:"
if command -v tree &>/dev/null; then
    tree "${BASE_DIR}"
else
    find "${BASE_DIR}" -type f | sort
fi