#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_TAG="${1:-flow-diff-cli:local}"
LIB_DIR="${SCRIPT_DIR}/lib"

rm -rf "${LIB_DIR}"
mvn -f "${SCRIPT_DIR}/flowdiff-pom.xml" dependency:copy-dependencies -Dflow.diff.lib.dir="${LIB_DIR}"

docker build -t "${IMAGE_TAG}" -f "${SCRIPT_DIR}/Dockerfile" "${SCRIPT_DIR}"
