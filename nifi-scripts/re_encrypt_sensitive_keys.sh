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
  NIFI_PREVIOUS_SENSITIVE_KEY_HASH="$(echo -n "${NIFI_PREVIOUS_SENSITIVE_KEY}" | sha256sum )"

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

  elif [ "${oldKeyHash}" != "${newKeyHash}" ] &&\
    [ "${NIFI_PREVIOUS_SENSITIVE_KEY}" != "" ] && [ -n "${NIFI_PREVIOUS_SENSITIVE_KEY}" ] &&\
    [ "${oldKeyHash}" = "${NIFI_PREVIOUS_SENSITIVE_KEY_HASH}" ]; then
    # if hash(NIFI_NEW_SENSITIVE_KEY) != old_key_hash && NIFI_PREVIOUS_SENSITIVE_KEY != null &&
    # hash(NIFI_PREVIOUS_SENSITIVE_KEY) = old_key_hash then,
    # re-encrypt from NIFI_PREVIOUS_SENSITIVE_KEY to NIFI_NEW_SENSITIVE_KEY, create old_key_hash from NIFI_NEW_SENSITIVE_KEY
    prop_replace 'nifi.sensitive.props.key'     "${NIFI_PREVIOUS_SENSITIVE_KEY}"
    info "setting OLD_SENSITIVE_KEY with NIFI_NEW_SENSITIVE_KEY for re-encryption."
    echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
    info "Result is ok, continue..."

  elif [ "${oldKeyHash}" != "${newKeyHash}" ] &&\
  [ -z "${NIFI_PREVIOUS_SENSITIVE_KEY}" ] || [ "${NIFI_PREVIOUS_SENSITIVE_KEY}" = '<empty>' ] || [ "${NIFI_PREVIOUS_SENSITIVE_KEY}" = ""  ]; then
    #if hash(NIFI_NEW_SENSITIVE_KEY) != old_key_hash && NIFI_PREVIOUS_SENSITIVE_KEY == null
    # then error -- NIFI_PREVIOUS_SENSITIVE_KEY is required, if NIFI_NEW_SENSITIVE_KEY changes
    error "oldKeyHash file does not match with newKeyHash and NIFI_PREVIOUS_SENSITIVE_KEY is empty. Terminating start-up..."
    sleep 10
    exit 3;
  elif [ "${oldKeyHash}" != "${newKeyHash}" ] &&\
   [ "${NIFI_PREVIOUS_SENSITIVE_KEY}" != "" ] && [ -n "${NIFI_PREVIOUS_SENSITIVE_KEY}" ] &&\
   [ "${oldKeyHash}" != "${NIFI_PREVIOUS_SENSITIVE_KEY_HASH}" ]; then
    #if hash(NIFI_NEW_SENSITIVE_KEY) != old_key_hash && NIFI_PREVIOUS_SENSITIVE_KEY != null
    # && hash(NIFI_PREVIOUS_SENSITIVE_KEY) != old_key_hash
    # then error -- NIFI_PREVIOUS_SENSITIVE_KEY must be old sensitive key, if NIFI_NEW_SENSITIVE_KEY changes
    error "oldKeyHash file does not match with newKeyHash or with NIFI_PREVIOUS_SENSITIVE_KEY_HASH. NIFI_PREVIOUS_SENSITIVE_KEY must be old sensitive key, if NIFI_NEW_SENSITIVE_KEY is changed. Terminating start-up..."
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
    #then, re-encrypt from SENSITIVE_KEY to NIFI_NEW_SENSITIVE_KEY, create old_key_hash from NIFI_NEW_SENSITIVE_KEY
    prop_replace 'nifi.sensitive.props.key'     "${SENSITIVE_KEY}"
    info "OLD_SENSITIVE_KEY is empty, setting it with new NIFI_NEW_SENSITIVE_KEY for re-encryption."
    echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
    info "Result is ok, continue..."

  elif [ -n "${OLD_SENSITIVE_KEY}" ]; then
    # OLD_SENSITIVE_KEY is not null

    if [ "${OLD_SENSITIVE_KEY}" = "${SENSITIVE_KEY}" ]; then
      #SENSITIVE_KEY = OLD_SENSITIVE_KEY
      # then re-encrypt from SENSITIVE_KEY to NIFI_NEW_SENSITIVE_KEY, create old_key_hash from NIFI_NEW_SENSITIVE_KEY
      prop_replace 'nifi.sensitive.props.key'     "${SENSITIVE_KEY}"
      info "setting OLD_SENSITIVE_KEY with new NIFI_NEW_SENSITIVE_KEY for re-encryption."
      echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
      info "Result is ok, continue..."

    else
      #SENSITIVE_KEY != OLD_SENSITIVE_KEY
      #then, re-encrypt from SENSITIVE_KEY to NIFI_NEW_SENSITIVE_KEY, create old_key_hash from NIFI_NEW_SENSITIVE_KEY
      prop_replace 'nifi.sensitive.props.key'     "${SENSITIVE_KEY}"
      info "OLD_SENSITIVE_KEY != SENSITIVE_KEY, so setting OLD_SENSITIVE_KEY with new NIFI_NEW_SENSITIVE_KEY for re-encryption."
      try_current="0"
      echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
      info "Result is ok, continue..."

      if [ "$try_current" = '1' ]; then
        warn "Failed to re-encrypt flow.json.gz with key using SENSITIVE_KEY. Trying re-encrypt from OLD_SENSITIVE_KEY to NIFI_NEW_SENSITIVE_KEY..."

        prop_replace 'nifi.sensitive.props.key'     "${OLD_SENSITIVE_KEY}"
        echo "${newKeyHash}" > "${NIFI_HOME}/persistent_conf/old_key_hash"
        info "re-encrypt from OLD_SENSITIVE_KEY to NIFI_NEW_SENSITIVE_KEY is done. Result ok, continue..."
      fi
    fi
  fi
fi
info "re_encrypt_sensitive_keys.sh end"
