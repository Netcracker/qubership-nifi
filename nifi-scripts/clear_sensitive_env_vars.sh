#!/bin/bash -e
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


. /opt/nifi/scripts/logging_api.sh

info "clear_sensitive_env_vars.sh start"

unset OIDC_CLIENT_SECRET
unset KEYSTORE_PASSWORD
unset KEY_PASSWORD
unset TRUSTSTORE_PASSWORD
unset SENSITIVE_KEY
unset NIFI_NEW_SENSITIVE_KEY

info "clear_sensitive_env_vars.sh end"