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

# shellcheck source=/dev/null
. /opt/nifi/scripts/logging_api.sh

info "re_encrypt_sensitive_keys.sh start"
if [ -z "${NIFI_NEW_SENSITIVE_KEY}" ] || [ "${NIFI_NEW_SENSITIVE_KEY}" = '<empty>' ] || [ "${NIFI_NEW_SENSITIVE_KEY}" = "" ]
then
  error "NIFI_NEW_SENSITIVE_KEY cannot be empty. Terminating start-up..."
  sleep 10
  exit 3;
fi
flow_dir="${NIFI_HOME}/persistent_conf/conf"
newKeyHash="$(echo -n "${NIFI_NEW_SENSITIVE_KEY}" | sha256sum )"

if [ -f "${NIFI_HOME}/persistent_conf/old_key_hash" ]
then
  #old_key_hash file is present (i.e. upgraded to new version already)
  oldKeyHash="$(cat "${NIFI_HOME}/persistent_conf/old_key_hash")"

  if [ ! -f "${flow_dir}/flow.json.gz" ]; then
    #if ${flow_dir}/flow.json.gz is not present (means first install),
    # then, set NIFI_NEW_SENSITIVE_KEY to nifi.properties, create old_key_hash from NIFI_NEW_SENSITIVE_KEY
    prop_replace 'nifi.sensitive.props.key'     "${NIFI_NEW_SENSITIVE_KEY}"
    echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
    info "flow.json.gz not exists, re-creating old_key_hash file with NIFI_NEW_SENSITIVE_KEY, skipping re-encrypt..."

  elif [ "${oldKeyHash}" = "${newKeyHash}" ]; then
    #if hash(NIFI_NEW_SENSITIVE_KEY) = old_key_hash then, set NIFI_NEW_SENSITIVE_KEY to nifi.properties
    prop_replace 'nifi.sensitive.props.key'     "${NIFI_NEW_SENSITIVE_KEY}"
    info "oldKeyHash & newKeyHash are same, continue..."
  elif [ "${oldKeyHash}" != "${newKeyHash}" ]; then
    #if hash(NIFI_NEW_SENSITIVE_KEY) != old_key_hash
    # then error -- NIFI_NEW_SENSITIVE_KEY change not allowed
    error "oldKeyHash does not match newKeyHash. Probably NIFI_NEW_SENSITIVE_KEY is different from previously used key. Check NIFI_NEW_SENSITIVE_KEY for correctness. Terminating start-up..."
    sleep 10
    exit 3;
  fi
else
  #old_key_hash file is not present (i.e. upgrade from older releases)

  if [ ! -f "${flow_dir}/flow.json.gz" ]; then
    # if ${flow_dir}/flow.json.gz is not present (first install),
    # then, set NIFI_NEW_SENSITIVE_KEY to nifi.properties, create old_key_hash from NIFI_NEW_SENSITIVE_KEY
    prop_replace 'nifi.sensitive.props.key'     "${NIFI_NEW_SENSITIVE_KEY}"
    echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
    info "flow.json.gz, old_key_hash not exists, creating old_key_hash file, skipping re-encrypt..."

  elif [ -z "${OLD_SENSITIVE_KEY}" ] || [ "${OLD_SENSITIVE_KEY}" = '<empty>' ]; then
    #previous deploy was partially successful (post_deploy did not work properly)
    if [ "${SENSITIVE_KEY}" = "${NIFI_NEW_SENSITIVE_KEY}" ]; then
        #if NIFI_NEW_SENSITIVE_KEY is the same as SENSITIVE_KEY, then go ahead:
        prop_replace 'nifi.sensitive.props.key'     "${NIFI_NEW_SENSITIVE_KEY}"
        info "OLD_SENSITIVE_KEY is empty, using NIFI_NEW_SENSITIVE_KEY as sensitive key."
        echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
    else
        #error, if NIFI_NEW_SENSITIVE_KEY != SENSITIVE_KEY
        error "OLD_SENSITIVE_KEY is not set, NIFI_NEW_SENSITIVE_KEY does not equal SENSITIVE_KEY. Set NIFI_NEW_SENSITIVE_KEY = SENSITIVE_KEY. Terminating start-up..."
        sleep 10
        exit 3;
    fi
  elif [ -n "${OLD_SENSITIVE_KEY}" ]; then
    # OLD_SENSITIVE_KEY is not null

    if [ "${OLD_SENSITIVE_KEY}" = "${SENSITIVE_KEY}" ]; then
      #SENSITIVE_KEY = OLD_SENSITIVE_KEY
      if [ "${SENSITIVE_KEY}" = "${NIFI_NEW_SENSITIVE_KEY}" ]; then
          #if NIFI_NEW_SENSITIVE_KEY is the same as SENSITIVE_KEY, then go ahead:
          prop_replace 'nifi.sensitive.props.key'     "${NIFI_NEW_SENSITIVE_KEY}"
          info "OLD_SENSITIVE_KEY=SENSITIVE_KEY, using NIFI_NEW_SENSITIVE_KEY as sensitive key."
          echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
      else
          #error, if NIFI_NEW_SENSITIVE_KEY != SENSITIVE_KEY
          error "OLD_SENSITIVE_KEY=SENSITIVE_KEY, NIFI_NEW_SENSITIVE_KEY does not equal SENSITIVE_KEY. Set NIFI_NEW_SENSITIVE_KEY = SENSITIVE_KEY. Terminating start-up..."
          sleep 10
          exit 3;
      fi
    else
      #SENSITIVE_KEY != OLD_SENSITIVE_KEY
      if [ "${SENSITIVE_KEY}" = "${NIFI_NEW_SENSITIVE_KEY}" ]; then
          #if NIFI_NEW_SENSITIVE_KEY is the same as SENSITIVE_KEY, then go ahead:
          prop_replace 'nifi.sensitive.props.key'     "${NIFI_NEW_SENSITIVE_KEY}"
          info "OLD_SENSITIVE_KEY!=SENSITIVE_KEY, SENSITIVE_KEY=NIFI_NEW_SENSITIVE_KEY, using NIFI_NEW_SENSITIVE_KEY as sensitive key."
          echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
      elif [ "${OLD_SENSITIVE_KEY}" = "${NIFI_NEW_SENSITIVE_KEY}" ]; then
          #if NIFI_NEW_SENSITIVE_KEY is the same as OLD_SENSITIVE_KEY, then try to go ahead:
          prop_replace 'nifi.sensitive.props.key'     "${NIFI_NEW_SENSITIVE_KEY}"
          info "OLD_SENSITIVE_KEY!=SENSITIVE_KEY, OLD_SENSITIVE_KEY=NIFI_NEW_SENSITIVE_KEY, using NIFI_NEW_SENSITIVE_KEY as sensitive key."
          echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
      else
          #error, if NIFI_NEW_SENSITIVE_KEY != SENSITIVE_KEY != OLD_SENSITIVE_KEY
          error "OLD_SENSITIVE_KEY != SENSITIVE_KEY != NIFI_NEW_SENSITIVE_KEY. Set correct NIFI_NEW_SENSITIVE_KEY, SENSITIVE_KEY and OLD_SENSITIVE_KEY parameters. Terminating start-up..."
          sleep 10
          exit 3;
      fi
    fi
  fi
fi
info "re_encrypt_sensitive_keys.sh end"
