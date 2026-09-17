# Copyright 2020-2025 NetCracker Technology Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# run-benchmark.sh passes the base image pinned in the repository Dockerfile.
ARG BASE_IMAGE
# checkov:skip=CKV_DOCKER_7: base image comes from the argument and it's not latest.
FROM ${BASE_IMAGE}

ARG FRAMEWORK
ENV FRAMEWORK=${FRAMEWORK}
ENV NIFI_HOME=/tmp/nifi-home
# The base image entrypoint waits this many seconds before it forwards SIGTERM; the benchmark needs no delay.
ENV SIGTERM_EXIT_DELAY=0

COPY --chown=10001:0 build/${FRAMEWORK}/ /app/
COPY --chown=10001:0 --chmod=755 run-app.sh /app/run-app.sh

USER 10001
# The ENTRYPOINT of the base image stays in place and runs this command.
CMD ["/app/run-app.sh"]
