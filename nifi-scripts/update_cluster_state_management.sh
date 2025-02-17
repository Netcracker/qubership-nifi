#!/bin/sh -e
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

state_providers_file=${NIFI_HOME}/conf/state-management.xml

info "Setting cluster configuration: Connect String = ${ZOOKEEPER_ADDRESS}"
sed -i -E "s|^(.*)<property name=\"Connect String\">(.*)</property>(.*)|\1<property name=\"Connect String\">${ZOOKEEPER_ADDRESS}</property>\3|" ${state_providers_file}

info "Setting cluster configuration: Root Node = ${NIFI_ZK_ROOT_NODE}"
sed -i -E "s|^(.*)<property name=\"Root Node\">(.*)</property>(.*)|\1<property name=\"Root Node\">${NIFI_ZK_ROOT_NODE}</property>\3|" ${state_providers_file}

info "Setting local state provider configuration: Directory = ${NIFI_LOCAL_PROVIDER_DIR:-./persistent_data/state/local}"
sed -i -E "s|^(.*)<property name=\"Directory\">(.*)</property>(.*)|\1<property name=\"Directory\">${NIFI_LOCAL_PROVIDER_DIR:-./persistent_data/state/local}</property>\3|" ${state_providers_file}