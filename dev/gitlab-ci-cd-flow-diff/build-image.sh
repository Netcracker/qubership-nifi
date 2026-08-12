#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_TAG="${1:-flow-diff-cli:local}"
LIB_DIR="${SCRIPT_DIR}/lib"

JAR="$(find "${LIB_DIR}" -maxdepth 1 -name 'qubership-nifi-flow-diff-cli-*.jar' 2>/dev/null | head -n 1)"
if [ -z "${JAR}" ]; then
  echo "No qubership-nifi-flow-diff-cli-*.jar found in ${LIB_DIR}." >&2
  echo "Fetch the jar and its runtime dependencies into ${LIB_DIR} first (see README.md)." >&2
  exit 1
fi

docker build -t "${IMAGE_TAG}" -f "${SCRIPT_DIR}/Dockerfile" "${SCRIPT_DIR}"
