#!/bin/bash -e

#    Licensed to the Apache Software Foundation (ASF) under one or more
#    contributor license agreements.  See the NOTICE file distributed with
#    this work for additional information regarding copyright ownership.
#    The ASF licenses this file to You under the Apache License, Version 2.0
#    (the "License"); you may not use this file except in compliance with
#    the License.  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.

# shellcheck source=/dev/null
# shellcheck disable=SC2154
. /opt/nifi/scripts/logging_api.sh

#create temp directory
mkdir -p /tmp/tmp-nifi
mkdir -p /tmp/db-init-scripts
mkdir -p /tmp/db-init-scripts-custom

scripts_dir='/opt/nifi/scripts'
#run before start operations:
[ -f "${scripts_dir}/before_start.sh" ] && . "$scripts_dir/before_start.sh"

h2_versions=('2.2.220' '2.1.214' '2.1.210')

[ -f "${scripts_dir}/start_additional_func.sh" ] && . "${scripts_dir}/start_additional_func.sh"
[ -f "${scripts_dir}/common.sh" ] && . "${scripts_dir}/common.sh"

cp "${NIFI_HOME}"/nifi-config-template/* "${NIFI_HOME}"/conf/
cp "${NIFI_HOME}"/nifi-config-template-custom/bootstrap.conf "${NIFI_HOME}"/conf/
cp "${NIFI_HOME}"/nifi-config-template-custom/config-client-template.json "${NIFI_HOME}"/conf/

generate_random_hex_password(){
    #args -- letters, numbers
    echo "$(tr -dc A-F < /dev/urandom | head -c "$1")""$(tr -dc 0-9 < /dev/urandom | head -c "$2")" | fold -w 1 | shuf | tr -d '\n'
}

rm -rf /tmp/initial-config-completed.txt

#Prepare nifi.properties:
#Prepare logging config:
. "${scripts_dir}/start_consul_app.sh"

while true; do
    if ! test -d /proc/"$(consul_pid)"; then
        error "ERROR: Consul app java process has terminated prematurely. See logs for details..."
        exit 1
    fi

    info "Checking if consul app main execution completed"
    if [ -e "/tmp/initial-config-completed.txt" ]; then
        info "Completion file exists. Consul App main execution completed."
        break
    fi
    sleep 1
done

if [ -f "${NIFI_HOME}/conf/custom.properties" ]; then
    # IFS is the 'internal field separator'. In this case, file uses '='
    IFS="="
    while read -r key value
    do
        if [[ "$key" == "nifi.http-auth-proxying-disabled-schemes" ]]; then
          if [[ "$value" != "Default" ]]; then
            if [[ "$value" == "null" ]]; then
                info "HTTP_AUTH_PROXYING_DISABLED_SCHEMES = null, setting as empty string"
                HTTP_AUTH_PROXYING_DISABLED_SCHEMES=""
            else
                info "HTTP_AUTH_PROXYING_DISABLED_SCHEMES = $value"
                HTTP_AUTH_PROXYING_DISABLED_SCHEMES="$value"
            fi
          fi
        fi
        if [[ "$key" == "nifi.http-auth-tunneling-disabled-schemes" ]]; then
          if [[ "$value" != "Default" ]]; then
            if [[ "$value" == "null" ]]; then
                info "HTTP_AUTH_TUNNELING_DISABLED_SCHEMES = null, setting as empty string"
                HTTP_AUTH_TUNNELING_DISABLED_SCHEMES=""
            else
                info "HTTP_AUTH_TUNNELING_DISABLED_SCHEMES = $value"
                HTTP_AUTH_TUNNELING_DISABLED_SCHEMES="$value"
            fi
          fi
        fi
        if [[ "$key" == "nifi.conf.clean-db-repository" ]]; then
          info "nifi.conf.clean-db-repository = $value; NIFI_CONF_PV_CLEAN_DB_REPO = $NIFI_CONF_PV_CLEAN_DB_REPO"
          if [ -z "$NIFI_CONF_PV_CLEAN_DB_REPO" ]; then
              export NIFI_CONF_PV_CLEAN_DB_REPO="$value"
          fi
        fi
        if [[ "$key" == "nifi.conf.clean-configuration" ]]; then
          info "nifi.conf.clean-configuration = $value; NIFI_CONF_PV_CLEAN_CONF = $NIFI_CONF_PV_CLEAN_CONF"
          if [ -z "$NIFI_CONF_PV_CLEAN_CONF" ]; then
              export NIFI_CONF_PV_CLEAN_CONF="$value"
          fi
        fi
        if [[ "$key" == "nifi.nifi-registry.nar-provider-enabled" ]]; then
          info "NIFI_REG_NAR_PROVIDER_ENABLED = $value"
          NIFI_REG_NAR_PROVIDER_ENABLED="$value"
        fi
        if [[ "$key" == "nifi.cluster.base-node-count" ]]; then
          info "BASE_NODE_COUNT = $value"
          BASE_NODE_COUNT="$value"
        fi
        if [[ "$key" == "nifi.cluster.start-mode" ]]; then
          info "START_MODE_CLUSTER = $value"
          START_MODE_CLUSTER="$value"
        fi
    done < "${NIFI_HOME}"/conf/custom.properties
    unset IFS
    
    set_additional_properties
fi

# Override JVM memory settings
if [ -n "${NIFI_JVM_HEAP_INIT}" ]; then
    prop_replace 'java.arg.2'       "-Xms${NIFI_JVM_HEAP_INIT}" "${nifi_bootstrap_file}"
fi

if [ -n "${NIFI_JVM_HEAP_MAX}" ]; then
    prop_replace 'java.arg.3'       "-Xmx${NIFI_JVM_HEAP_MAX}" "${nifi_bootstrap_file}"
fi

if [ -n "${NIFI_JVM_DEBUGGER}" ]; then
    uncomment "java.arg.debug" "${nifi_bootstrap_file}"
fi

if [ -n "${NIFI_TLS_DEBUG}" ]; then
    sed -i -e "s|^\#java\.arg\.20|java\.arg\.20|" "${NIFI_HOME}"/conf/bootstrap.conf
fi

if [ -n "${NIFI_DEBUG_NATIVE_MEMORY}" ]; then
    sed -i -e "s|^\#java\.arg\.21|java\.arg\.21|" "${NIFI_HOME}"/conf/bootstrap.conf
fi

if [ -n "${NIFI_DEBUG_JIT_LOGGING}" ]; then
    sed -i -e "s|^\#java\.arg\.22|java\.arg\.22|" "${NIFI_HOME}"/conf/bootstrap.conf
fi

if [ -n "${NIFI_ENABLE_NASHORN_JIT}" ]; then
    sed -i -e "s|^java\.arg\.23|\#java\.arg\.23|" "${NIFI_HOME}"/conf/bootstrap.conf
fi

if [ -n "${HTTP_AUTH_PROXYING_DISABLED_SCHEMES+x}" ]; then
    sed -i -e "s|^\#java\.arg\.24|java\.arg\.24|" "${nifi_bootstrap_file}"
    prop_replace 'java.arg.24' "-Djdk.http.auth.proxying.disabledSchemes=${HTTP_AUTH_PROXYING_DISABLED_SCHEMES}" "${nifi_bootstrap_file}"
fi

if [ -n "${HTTP_AUTH_TUNNELING_DISABLED_SCHEMES+x}" ]; then
    sed -i -e "s|^\#java\.arg\.25|java\.arg\.25|" "${nifi_bootstrap_file}"
    prop_replace 'java.arg.25' "-Djdk.http.auth.tunneling.disabledSchemes=${HTTP_AUTH_TUNNELING_DISABLED_SCHEMES}" "${nifi_bootstrap_file}"
fi

if [ -n "${NIFI_ADDITIONAL_JVM_ARGS}" ] && [ "${NIFI_ADDITIONAL_JVM_ARGS}" != "${ENV_NIFI_ADDITIONAL_JVM_ARGS}" ]; then
    i=27
    read -r -a addJvmArgArr <<< "$NIFI_ADDITIONAL_JVM_ARGS"
    for addJvmArg in "${addJvmArgArr[@]}"; do
        info "Add $addJvmArg in bootstrap.conf"
        echo "java.arg.$i=$addJvmArg" >> "${NIFI_HOME}"/conf/bootstrap.conf
        echo "" >> "${NIFI_HOME}"/conf/bootstrap.conf
        i=$((i+1))
    done
fi

call_additional_libs

if [ -n "${X_JAVA_ARGS}" ]; then
    if [ -z "$i" ]; then
        i=26
    fi
    read -r -a addJvmArgArr2 <<< "$X_JAVA_ARGS"
    for addJvmArg in "${addJvmArgArr2[@]}"; do
        info "Add $addJvmArg in bootstrap.conf"
        echo "java.arg.$i=$addJvmArg" >> "${NIFI_HOME}"/conf/bootstrap.conf
        echo "" >> "${NIFI_HOME}"/conf/bootstrap.conf
        i=$((i+1))
    done
fi


# Establish baseline properties
prop_replace 'nifi.web.https.port'              "${NIFI_WEB_HTTPS_PORT:-8443}"
prop_replace 'nifi.web.https.host'              "${NIFI_WEB_HTTPS_HOST:-$HOSTNAME}"
prop_replace 'nifi.web.proxy.host'              "${NIFI_WEB_PROXY_HOST}"
prop_replace 'nifi.remote.input.host'           "${NIFI_REMOTE_INPUT_HOST:-$HOSTNAME}"
prop_replace 'nifi.remote.input.socket.port'    "${NIFI_REMOTE_INPUT_SOCKET_PORT:-10000}"
prop_replace 'nifi.remote.input.secure'         'true'
prop_replace 'nifi.cluster.protocol.is.secure'  'true'

# Set nifi-toolkit properties files and baseUrl
export HOME="/opt/nifi/nifi-current/conf/"
"${scripts_dir}/toolkit.sh"
prop_replace 'baseUrl' "https://${NIFI_WEB_HTTPS_HOST:-$HOSTNAME}:${NIFI_WEB_HTTPS_PORT:-8443}" "${nifi_toolkit_props_file}"

prop_replace 'keystore'           "${NIFI_HOME}/conf/keystore.p12"      "${nifi_toolkit_props_file}"
prop_replace 'keystoreType'       "PKCS12"                              "${nifi_toolkit_props_file}"
prop_replace 'truststore'         "${NIFI_HOME}/conf/truststore.p12"    "${nifi_toolkit_props_file}"
prop_replace 'truststoreType'     "PKCS12"                              "${nifi_toolkit_props_file}"

if [ -n "${NIFI_WEB_HTTP_PORT}" ]; then
    prop_replace 'nifi.web.https.port'                        ''
    prop_replace 'nifi.web.https.host'                        ''
    prop_replace 'nifi.web.http.port'                         "${NIFI_WEB_HTTP_PORT}"
    prop_replace 'nifi.web.http.host'                         "${NIFI_WEB_HTTP_HOST:-$HOSTNAME}"
    prop_replace 'nifi.remote.input.secure'                   'false'
    prop_replace 'nifi.cluster.protocol.is.secure'            'false'
    prop_replace 'nifi.security.keystore'                     ''
    prop_replace 'nifi.security.keystoreType'                 ''
    prop_replace 'nifi.security.truststore'                   ''
    prop_replace 'nifi.security.truststoreType'               ''
    prop_replace 'nifi.security.user.login.identity.provider' ''
    prop_replace 'keystore'                                   '' "${nifi_toolkit_props_file}"
    prop_replace 'keystoreType'                               '' "${nifi_toolkit_props_file}"
    prop_replace 'truststore'                                 '' "${nifi_toolkit_props_file}"
    prop_replace 'truststoreType'                             '' "${nifi_toolkit_props_file}"
    prop_replace 'baseUrl' "http://${NIFI_WEB_HTTP_HOST:-$HOSTNAME}:${NIFI_WEB_HTTP_PORT}" "${nifi_toolkit_props_file}"

    if [ -n "${NIFI_WEB_PROXY_HOST}" ]; then
        info 'NIFI_WEB_PROXY_HOST was set but NiFi is not configured to run in a secure mode. Unsetting nifi.web.proxy.host.'
        prop_replace 'nifi.web.proxy.host' ''
    fi
else
    if [ -z "${NIFI_WEB_PROXY_HOST}" ]; then
        info 'NIFI_WEB_PROXY_HOST was not set but NiFi is configured to run in a secure mode. The NiFi UI may be inaccessible if using port mapping or connecting through a proxy.'
    fi
fi

export HOME="/opt/nifi/nifi-current/"

# Set custom flow files storage
prop_replace 'nifi.flow.configuration.file'               "${NIFI_FLOW_CONF_FILE:-./persistent_conf/conf/flow.xml.gz}"
prop_replace 'nifi.flow.configuration.json.file'          "${NIFI_FLOW_CONF_JSON_FILE:-./persistent_conf/conf/flow.json.gz}"
prop_replace 'nifi.flow.configuration.archive.dir'        "${NIFI_FLOW_CONF_ARCH_DIR:-./persistent_conf/conf/archive/}"
prop_replace 'nifi.templates.directory'                   "${NIFI_TEMPLATES_DIR:-./persistent_conf/conf/templates/}"
prop_replace 'nifi.database.directory'                    "${NIFI_DB_REPOSITORY_DIR:-./persistent_conf/database_repository}"
prop_replace 'nifi.flowfile.repository.directory'         "${NIFI_FLOW_FILE_REPO_DIR:-./flowfile_repository}"
prop_replace 'nifi.content.repository.directory.default'  "${NIFI_CONTENT_REPO_DIR:-./persistent_data/content_repository}"
prop_replace 'nifi.provenance.repository.directory.default' "${NIFI_PROVENANCE_REPO_DIR:-./provenance_repository/provenance_repository}"

# Migration to new directories:
# If persistent_conf/conf doesn't exist, but persistent_data/conf exists:
if [ ! -d "${NIFI_HOME}/persistent_conf/conf" ] && [ -d "${NIFI_HOME}/persistent_data/conf" ];
then
    mv "${NIFI_HOME}/persistent_data/conf" "${NIFI_HOME}/persistent_conf/" || { error "ERROR: failed to move configuration data"; sleep 15; exit 1; }
fi

mkdir -p "${NIFI_HOME}/persistent_conf/conf"
mkdir -p "${NIFI_HOME}/persistent_conf/conf-restore"
mkdir -p "${NIFI_HOME}/provenance_repository/provenance_repository"
mkdir -p "${NIFI_HOME}/persistent_conf/database_repository"

bash "${scripts_dir}/restore_nifi_configurations.sh"

# Set nifi-toolkit properties files and baseUrl
export HOME="/opt/nifi/nifi-current/conf/"
"${scripts_dir}/toolkit.sh"
prop_replace 'baseUrl' "http://${NIFI_WEB_HTTP_HOST:-$HOSTNAME}:${NIFI_WEB_HTTP_PORT:-8080}" "${nifi_toolkit_props_file}"

export HOME="/opt/nifi/nifi-current/"

prop_replace 'nifi.variable.registry.properties'    "${NIFI_VARIABLE_REGISTRY_PROPERTIES:-}"
prop_replace 'nifi.cluster.is.node'                         "${NIFI_CLUSTER_IS_NODE:-false}"
#prop_replace 'nifi.cluster.node.address'                    "${NIFI_CLUSTER_ADDRESS:-$HOSTNAME}"

if [ "$NIFI_CLUSTER_IS_NODE" == "true" ]; then 
    clusterHostName=$(hostname -f)
    prop_replace 'nifi.cluster.node.address'                    "${NIFI_CLUSTER_ADDRESS:-$clusterHostName}"
fi  
prop_replace 'nifi.cluster.node.protocol.port'              "${NIFI_CLUSTER_NODE_PROTOCOL_PORT:-}"
prop_replace 'nifi.cluster.node.protocol.max.threads'       "${NIFI_CLUSTER_NODE_PROTOCOL_MAX_THREADS:-50}"
prop_replace 'nifi.cluster.load.balance.host'               "${NIFI_CLUSTER_LOAD_BALANCE_HOST:-}"
prop_replace 'nifi.zookeeper.connect.string'                "${ZOOKEEPER_ADDRESS:-}"
prop_replace 'nifi.zookeeper.root.node'                     "${NIFI_ZK_ROOT_NODE:-/nifi}"
prop_replace 'nifi.cluster.flow.election.max.wait.time'     "${NIFI_ELECTION_MAX_WAIT:-5 mins}"
prop_replace 'nifi.cluster.flow.election.max.candidates'    "${NIFI_ELECTION_MAX_CANDIDATES:-}"
prop_replace 'nifi.web.proxy.context.path'                  "${NIFI_WEB_PROXY_CONTEXT_PATH:-}"

prop_replace 'nifi.provenance.repository.indexed.attributes' "${NIFI_INDEXED_ATTRIBUTES}"

# Set analytics properties
prop_replace 'nifi.analytics.predict.enabled'                   "${NIFI_ANALYTICS_PREDICT_ENABLED:-false}"
prop_replace 'nifi.analytics.predict.interval'                  "${NIFI_ANALYTICS_PREDICT_INTERVAL:-3 mins}"
prop_replace 'nifi.analytics.query.interval'                    "${NIFI_ANALYTICS_QUERY_INTERVAL:-5 mins}"
prop_replace 'nifi.analytics.connection.model.implementation'   "${NIFI_ANALYTICS_MODEL_IMPLEMENTATION:-org.apache.nifi.controller.status.analytics.models.OrdinaryLeastSquares}"
prop_replace 'nifi.analytics.connection.model.score.name'       "${NIFI_ANALYTICS_MODEL_SCORE_NAME:-rSquared}"
prop_replace 'nifi.analytics.connection.model.score.threshold'  "${NIFI_ANALYTICS_MODEL_SCORE_THRESHOLD:-.90}"

if [ "${NIFI_REG_NAR_PROVIDER_ENABLED}" == "true" ]; then
	{
	  echo ""
	  echo "#default nifi registry nar provider"
	  echo 'nifi.nar.library.provider.default-nifi-registry.implementation=org.apache.nifi.registry.extension.NiFiRegistryNarProvider'
	  echo 'nifi.nar.library.provider.default-nifi-registry.url=https://cloud-data-migration-nifi-registry:8080'
	  echo ""
	} >> "${NIFI_HOME}"/conf/nifi.properties
fi

if [ "${NIFI_CONF_PV_CLEAN_CONF}" == "true" ]; then
    info "NIFI_CONF_PV_CLEAN_CONF = $NIFI_CONF_PV_CLEAN_CONF"
    info "Cleaning configuration files from NiFi Configuration PV..."
    info "Saving record to clean install log..."
    numFiles=0
    startCleanTime=$(date +%Y-%m-%dT%H:%M:%S)
    [ ! -f "./persistent_conf/clean_install.log" ] && echo "" > ./persistent_conf/clean_install.log
    info "$startCleanTime Started configuration clean"
    info "$startCleanTime Started configuration clean" >> ./persistent_conf/clean_install.log
    #
    info "Removing ./persistent_conf/conf/flow.xml.gz..."
    numDeleted=$(rm -rfv ./persistent_conf/conf/flow.xml.gz | wc -l)
    numFiles=$((numFiles+numDeleted))
    info "Removing ./persistent_conf/conf/flow.json.gz..."
    numDeleted=$(rm -rfv ./persistent_conf/conf/flow.json.gz | wc -l)
    numFiles=$((numFiles+numDeleted))
    info "Removing ./persistent_conf/conf/authorizations.xml..."
    numDeleted=$(rm -rfv ./persistent_conf/conf/authorizations.xml | wc -l)
    numFiles=$((numFiles+numDeleted))
    info "Removing ./persistent_conf/conf/users.xml..."
    numDeleted=$(rm -rfv ./persistent_conf/conf/users.xml | wc -l)
    numFiles=$((numFiles+numDeleted))
    info "Removing ./persistent_conf/conf-restore/authorizations.xml..."
    numDeleted=$(rm -rfv ./persistent_conf/conf-restore/authorizations.xml | wc -l)
    numFiles=$((numFiles+numDeleted))
    info "Removing ./persistent_conf/conf-restore/users.xml..."
    numDeleted=$(rm -rfv ./persistent_conf/conf-restore/users.xml | wc -l)
    numFiles=$((numFiles+numDeleted))
    #
    endCleanTime=$(date +%Y-%m-%dT%H:%M:%S)
    info "$endCleanTime Finished configuration clean. Removed files: $numFiles"
    info "$endCleanTime Finished configuration clean. Removed files: $numFiles" >> ./persistent_conf/clean_install.log
fi

if [ "${NIFI_CONF_PV_CLEAN_DB_REPO}" == "true" ]; then
    info "NIFI_CONF_PV_CLEAN_DB_REPO = $NIFI_CONF_PV_CLEAN_DB_REPO"
    info "Cleaning database repository in NiFi Configuration PV..."
    info "Saving record to clean install log..."
    startCleanTime=$(date +%Y-%m-%dT%H:%M:%S)
    numFiles=0
    [ ! -f "./persistent_conf/clean_install.log" ] && echo "" > ./persistent_conf/clean_install.log
    info "$startCleanTime Started db repository clean"
    info "$startCleanTime Started db repository clean" >> ./persistent_conf/clean_install.log
    #
    info "Removing ./persistent_conf/database_repository/nifi-flow-audit.mv.db..."
    numDeleted=$(rm -rfv ./persistent_conf/database_repository/nifi-flow-audit.mv.db | wc -l)
    numFiles=$((numFiles+numDeleted))
    info "Removing ./persistent_conf/database_repository/nifi-identity-providers.mv.db..."
    numDeleted=$(rm -rfv ./persistent_conf/database_repository/nifi-identity-providers.mv.db | wc -l)
    numFiles=$((numFiles+numDeleted))
    #
    endCleanTime=$(date +%Y-%m-%dT%H:%M:%S)
    info "$endCleanTime Finished db repository clean. Removed files: $numFiles"
    info "$endCleanTime Finished db repository clean. Removed files: $numFiles" >> ./persistent_conf/clean_install.log
else
   info "Checking if any h2 db is corrupt..."
   verscount=${#h2_versions[@]}
   newDbFile=(./persistent_conf/database_repository/*.sh)
   numDbFiles=${#newDbFile[@]}   
  if [[ -f "./persistent_conf/database_repository/nifi-flow-audit.mv.db" && "$numDbFiles" == "0" ]]; then
   info "Checking if nifi-flow-audit h2 db is corrupt..."
   count=$verscount
   for version in "${h2_versions[@]}"
   do
       h2jar=${NIFI_HOME}/utility-lib/h2-$version.jar
       info "h2 jar path: $h2jar"
       errormessage=$("$JAVA_HOME"/bin/java -cp "$h2jar" org.h2.tools.Script -url jdbc:h2:./persistent_conf/database_repository/nifi-flow-audit -user "nf" -password "nf" -script /tmp/tmp-nifi/nifi-flow-audit-bkp.sql -options NODATA 2>&1 || echo 'H2 script call failed for nifi-flow-audit.mv.db')
       if [ -n "$errormessage" ]
       then
              info "$errormessage"
              if [[ "$errormessage" == *"The write format"*"than the supported format"* ]]; then
                  count=$((count-1))
                  info "Unsupported h2 version: $version"
              else
                 info "Renaming nifi-flow-audit.mv.db as it is found to be corrupt"
                 now=$(date +%Y-%m-%dT%H:%M:%S)
                 mv "./persistent_conf/database_repository/nifi-flow-audit.mv.db" "./persistent_conf/database_repository/nifi-flow-audit.mv.db.bk_$now"
                 break
              fi
       else
         break
       fi

   done

   if [[ $count == 0 ]]; then
     info "Renaming nifi-flow-audit.mv.db as it is not readable by any of the available h2 versions"
     now=$(date +%Y-%m-%dT%H:%M:%S)
     mv "./persistent_conf/database_repository/nifi-flow-audit.mv.db" "./persistent_conf/database_repository/nifi-flow-audit.mv.db.bk_$now"
   fi

 rm -rf /tmp/tmp-nifi/nifi-flow-audit-bkp.sql
 fi

  if [ -f "./persistent_conf/database_repository/nifi-identity-providers.mv.db" ]; then
    info "Checking if nifi-identity-providers h2 db is corrupt..."
    count=$verscount
    for version in "${h2_versions[@]}"
    do
        h2jar=${NIFI_HOME}/utility-lib/h2-$version.jar
        info "h2 jar path: $h2jar"
        errormessage=$("$JAVA_HOME"/bin/java -cp "$h2jar" org.h2.tools.Script -url jdbc:h2:./persistent_conf/database_repository/nifi-identity-providers -user "nf" -password "nf" -script /tmp/tmp-nifi/nifi-identity-providers-bkp.sql -options NODATA 2>&1 || echo 'H2 script call failed for nifi-identity-providers.mv.db')
        if [ -n "$errormessage" ]
        then
               info "$errormessage"
               if [[ "$errormessage" == *"The write format"*"than the supported format"* ]]; then
                   count=$((count-1))
                   info "Unsupported h2 version: $version"
               else
                  info "Renaming nifi-identity-providers.mv.db as it is found to be corrupt"
                  now=$(date +%Y-%m-%dT%H:%M:%S)
                  mv "./persistent_conf/database_repository/nifi-identity-providers.mv.db" "./persistent_conf/database_repository/nifi-identity-providers.mv.db.bk_$now"
                  break
               fi
        else
          break
        fi
    done

    if [[ $count == 0 ]]; then
         info "Renaming nifi-identity-providers.mv.db as it is not readable by any of the available h2 versions"
         now=$(date +%Y-%m-%dT%H:%M:%S)
         mv "./persistent_conf/database_repository/nifi-identity-providers.mv.db" "./persistent_conf/database_repository/nifi-identity-providers.mv.db.bk_$now"
    fi
  rm -rf /tmp/tmp-nifi/nifi-identity-providers-bkp.sql

  fi

fi

if [ "${NIFI_CONF_PV_CLEAN_INSTALL_SHOW_LOG}" == "true" ]; then
    info "Getting clean install log contents..."
    [ -f "./persistent_conf/clean_install.log" ] && cat ./persistent_conf/clean_install.log
    [ ! -f "./persistent_conf/clean_install.log" ] && info "No clean install log records found."
fi

if [ "${NIFI_CONF_PV_CLEAN_INSTALL_DELETE_LOG}" == "true" ]; then
    info "Deleting clean install log..."
    if [ -f "./persistent_conf/clean_install.log" ]; then
        info "Getting clean install log contents..."
        cat ./persistent_conf/clean_install.log
        info "Removing ./persistent_conf/clean_install.log..."
        rm -rf ./persistent_conf/clean_install.log
    else
        info "No clean install log records found."
    fi
fi

if [ -n "${NIFI_SENSITIVE_PROPS_KEY}" ]; then
    prop_replace 'nifi.sensitive.props.key' "${NIFI_SENSITIVE_PROPS_KEY}"
fi

if [ -n "${SINGLE_USER_CREDENTIALS_USERNAME}" ] && [ -n "${SINGLE_USER_CREDENTIALS_PASSWORD}" ]; then
    "${NIFI_HOME}"/bin/nifi.sh set-single-user-credentials "${SINGLE_USER_CREDENTIALS_USERNAME}" "${SINGLE_USER_CREDENTIALS_PASSWORD}"
fi

if [ "$NIFI_CLUSTER_IS_NODE" == "true" ]; then
    startMode="$START_MODE_CLUSTER"
    
    numberNode=${HOSTNAME##"$MICROSERVICE_NAME"-}
    baseNode="$BASE_NODE_COUNT"
    
    if [ "$numberNode" -gt "$((baseNode-1))" ]; then
        if [ "$startMode" == "delete" ]; then
            rm -f "${NIFI_HOME}/persistent_conf/conf/flow.xml.gz"
            rm -f "${NIFI_HOME}/persistent_conf/conf/flow.json.gz"
            rm -f "${NIFI_HOME}/persistent_conf/conf/authorizations.xml"
            rm -f "${NIFI_HOME}/persistent_conf/conf/users.xml"
            rm -f "${NIFI_HOME}/persistent_conf/conf-restore/authorizations.xml"
            rm -f "${NIFI_HOME}/persistent_conf/conf-restore/users.xml"
        fi
        if [ "$startMode" == "backup" ]; then
            now=$(date +'"%d-%b-%Y"')
            if [ -f "${NIFI_HOME}/persistent_conf/conf/flow.xml.gz" ]; then
                mv "${NIFI_HOME}/persistent_conf/conf/flow.xml.gz" "${NIFI_HOME}/persistent_conf/conf/flow.xml.gz_bk_$now"
            fi
            if [ -f "${NIFI_HOME}/persistent_conf/conf/flow.json.gz" ]; then
                mv "${NIFI_HOME}/persistent_conf/conf/flow.json.gz" "${NIFI_HOME}/persistent_conf/conf/flow.json.gz_bk_$now"
            fi
            if [ -f "${NIFI_HOME}/persistent_conf/conf/authorizations.xml" ]; then
                mv "${NIFI_HOME}/persistent_conf/conf/authorizations.xml" "${NIFI_HOME}/persistent_conf/conf/authorizations.xml_bk_$now"
            fi
            if [ -f "${NIFI_HOME}/persistent_conf/conf/users.xml" ]; then
                mv "${NIFI_HOME}/persistent_conf/conf/users.xml" "${NIFI_HOME}/persistent_conf/conf/users.xml_bk_$now"
            fi
        fi
    fi
fi

[ -f "${scripts_dir}/remove_duplicate_reg_clients_in_flow.sh" ] && bash "${scripts_dir}/remove_duplicate_reg_clients_in_flow.sh"

#Sensitive key

bash "${scripts_dir}/pre_set_algorithm.sh"

. "${scripts_dir}/re_encrypt_sensitive_keys.sh"

bash "${scripts_dir}/set_algorithm.sh"

. "${scripts_dir}/update_cluster_state_management.sh"

if [ -n "${NIFI_CONTENT_ARCHIVE_DISABLED}" ]; then
    # disable content repository archive
    prop_replace 'nifi.content.repository.archive.enabled'                  "false"
fi

if [ -n "${NIFI_CONTENT_ARCHIVE_MAX_USAGE}" ]; then
    # set max usage for Content Archive
    prop_replace 'nifi.content.repository.archive.max.usage.percentage'                  "${NIFI_CONTENT_ARCHIVE_MAX_USAGE}"
fi

set_additional_properties2

# Check if we are secured or unsecured
case ${AUTH} in
    oidc)
        info 'Enabling Two-Way SSL and OIDC user authentication'
        . "${scripts_dir}/qubership_secure.sh"
        . "${scripts_dir}/secure.sh"
        ;;
    tls)
        info 'Enabling Two-Way SSL user authentication'
        . "${scripts_dir}/secure.sh"
        ;;
    ldap)
        info 'Enabling LDAP user authentication'
        # Reference ldap-provider in properties
        export NIFI_SECURITY_USER_LOGIN_IDENTITY_PROVIDER="ldap-provider"

        . "${scripts_dir}/secure.sh"
        . "${scripts_dir}/update_login_providers.sh"
        ;;
esac

#Set up bootstrap sensitive key: letters=11, numbers=21
NIFI_BOOTSTRAP_SENSITIVE_KEY=$(generate_random_hex_password 11 21)

if [ -n "${NIFI_BOOTSTRAP_SENSITIVE_KEY}" ]; then
    info "Setting bootstrap sensitive key..."
    /opt/nifi/nifi-toolkit-current/bin/encrypt-config.sh -n "${NIFI_HOME}"/conf/nifi.properties -b "${NIFI_HOME}"/conf/bootstrap.conf -k "${NIFI_BOOTSTRAP_SENSITIVE_KEY}"
fi

load_additional_resources

. "${scripts_dir}/clear_sensitive_env_vars.sh"

# Add gcompat lib to avoid compatibility issues between netty and Alpine Linux:
export LD_PRELOAD=/lib/libgcompat.so.0:$LD_PRELOAD

# Continuously provide logs so that 'docker logs' can    produce them
"${NIFI_HOME}/bin/nifi.sh" run &
nifi_pid="$!"

redirect_logs

trap 'echo Received trapped signal, beginning shutdown...;./bin/nifi.sh stop;kill -9 "$tail_pid";exit 0;' TERM HUP INT;
trap ":" EXIT

info "NiFi running with PID ${nifi_pid}."
wait ${nifi_pid}

javaRetCode=$?
check_java_ret_code "$javaRetCode"