#!/bin/bash
# Copyright 2020-2026 NetCracker Technology Corporation
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

# shellcheck disable=SC2034
# Defaults for the consul-unreachable scenario. A variable already set in the environment wins.
# How Consul becomes unreachable on the second start: wrong-host (CONSUL_URL names a host that does not resolve)
# or no-response (the Consul container is paused, so connections open and requests get no response).
CONSUL_FAILURE="${CONSUL_FAILURE:-wrong-host}"
# Consul integration framework of NiFi (NIFI_CONSUL_INT_FRAMEWORK): spring or quarkus.
CONSUL_FRAMEWORK="${CONSUL_FRAMEWORK:-spring}"
# CONSUL_URL for CONSUL_FAILURE=wrong-host. Empty selects consul-wrong:8500 for spring and http://consul-wrong:8500
# for quarkus, which needs the protocol.
WRONG_CONSUL_URL="${WRONG_CONSUL_URL:-}"
# Seconds the startup waits for a paused Consul before the evidence is collected, for CONSUL_FAILURE=no-response.
HANG_SECONDS="${HANG_SECONDS:-120}"
