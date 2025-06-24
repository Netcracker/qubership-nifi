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
# shellcheck disable=SC2016
. /opt/nifi/scripts/logging_api.sh

scripts_dir='/opt/nifi/scripts'

[ -f "${scripts_dir}/common.sh" ] && . "${scripts_dir}/common.sh"

[ -f "${scripts_dir}/qubership_secure_add_funct.sh" ] && . "${scripts_dir}/qubership_secure_add_funct.sh"

# escaping & in url
esc_OIDC_DISCOVERY_URL_NEW="${OIDC_DISCOVERY_URL_NEW//&/\\&}"

# Setup OpenId Connect SSO Properties
prop_replace 'nifi.security.user.oidc.discovery.url' "${esc_OIDC_DISCOVERY_URL_NEW}"
prop_replace 'nifi.security.user.oidc.client.id' "${OIDC_CLIENT_ID}"
prop_replace 'nifi.security.user.oidc.client.secret' "${OIDC_CLIENT_SECRET}"

# Setup Identity Mapping
sed -i -e "s|^\#\s*nifi\.security\.identity\.mapping\.pattern\.dn=|nifi\.security\.identity\.mapping\.pattern\.dn=|" "${NIFI_HOME}"/conf/nifi.properties
sed -i -e "s|^\#\s*nifi\.security\.identity\.mapping\.value\.dn=|nifi\.security\.identity\.mapping\.value\.dn=|" "${NIFI_HOME}"/conf/nifi.properties
sed -i -e "s|^\#\s*nifi\.security\.identity\.mapping\.transform\.dn=|nifi\.security\.identity\.mapping\.transform\.dn=|" "${NIFI_HOME}"/conf/nifi.properties
prop_replace 'nifi.security.identity.mapping.pattern.dn' '\^\.\*EMAILADDRESS=\(\[\^,\]\*\)\.\*\$'
prop_replace 'nifi.security.identity.mapping.value.dn' "\$1"

{
    echo " "
    echo "nifi.security.identity.mapping.pattern.dn2=^CN=(.*?), .*$"
    echo "nifi.security.identity.mapping.value.dn2=\$1"
} >>"${NIFI_HOME}"/conf/nifi.properties

# Establish initial user and an associated admin identity
sed -i -e 's|<property name="Initial User Identity 1"></property>|<property name="Initial User Identity 1">'"${INITIAL_ADMIN_IDENTITY}"'</property>|' "${NIFI_HOME}"/conf/authorizers.xml
sed -i '/"Initial User Identity 1">'"${INITIAL_ADMIN_IDENTITY}"'/a <property name="Initial User Identity 2">'"${INITIAL_USER_IDENTITY}"'</property>' "${NIFI_HOME}"/conf/authorizers.xml
sed -i -e 's|<property name="Initial Admin Identity"></property>|<property name="Initial Admin Identity">'"${INITIAL_ADMIN_IDENTITY}"'</property>|' "${NIFI_HOME}"/conf/authorizers.xml

if [ "${NIFI_CLUSTER_IS_NODE}" == "true" ]; then

    maxNode="$MAX_NODE_COUNT"

    if [ -z "$maxNode" ]; then
        warn "MAX_NODE_COUNT is not set. The default value = 10 is used for configuration."
        maxNode=10
    fi

    #Setting common cluster properties
    prop_replace 'nifi.cluster.protocol.is.secure' "${NIFI_CLUSTER_IS_NODE}"

    # Priority:
    # 1. Env variable
    # 2. nifi.properties from Consul
    # 3. default nifi.properties
    if [ -n "$NIFI_ZOOKEEPER_CONNECT_TIMEOUT" ]; then
        prop_replace 'nifi.zookeeper.connect.timeout' "${NIFI_ZOOKEEPER_CONNECT_TIMEOUT}"
    fi
    if [ -n "$NIFI_CLUSTER_NODE_READ_TIMEOUT" ]; then
        prop_replace 'nifi.cluster.node.read.timeout'  "${NIFI_CLUSTER_NODE_READ_TIMEOUT}"
    fi

    sed -i -e 's|<property name="Node Identity 1"></property>|<property name="Node Identity 1">'"${MICROSERVICE_NAME}-0.${NAMESPACE}"'</property>|' "${NIFI_HOME}"/conf/authorizers.xml

    for (( i=1; i <= maxNode-1; i++ )); do
        sed -i -e '/"Node Identity 1">'".*"'/a <property name="Node Identity '"$((i+1))"'">'"${MICROSERVICE_NAME}-$i.${NAMESPACE}"'</property>' "${NIFI_HOME}"/conf/authorizers.xml
    done

    for (( i=0; i <= maxNode-1; i++ )); do
        sed -i '/"Initial User Identity 1">'"${INITIAL_ADMIN_IDENTITY}"'/a <property name="Initial User Identity '"$((i+3))"'">'"${MICROSERVICE_NAME}-$i.${NAMESPACE}"'</property>' "${NIFI_HOME}"/conf/authorizers.xml
    done
fi

#Change the location of the users.xml file and authorizations.xml
sed -i -e 's|<property name="Users File">\./conf/users.xml</property>|<property name="Users File">\./persistent_conf/conf/users.xml</property>|' "${NIFI_HOME}"/conf/authorizers.xml
sed -i -e 's|<property name="Authorizations File">\./conf/authorizations.xml</property>|<property name="Authorizations File">\./persistent_conf/conf/authorizations.xml</property>|' "${NIFI_HOME}"/conf/authorizers.xml

# Create conf directory
mkdir -p "${NIFI_HOME}"/persistent_conf/conf

# Generate keystores
if [ -d /tmp/cert ]; then
    if [ -z "${CERTIFICATE_FILE_PASSWORD}" ]; then
        export CERTIFICATE_FILE_PASSWORD="changeit"
    fi
    export CERTIFICATE_KEYSTORE_LOCATION="/etc/ssl/certs/java/cacerts"

    info "Importing certificates from /tmp/cert directory..."
    find /tmp/cert -print | grep -E '\.cer|\.pem' | grep -v '\.\.' | sed -E 's|/tmp/cert/(.*)|/tmp/cert/\1 \1|g' | xargs -n 2 --no-run-if-empty bash -c \
        'keytool -importcert -cacerts -file "$1" -alias "$2" -storepass "${CERTIFICATE_FILE_PASSWORD}" -noprompt' argv0 || warn "Failed to import certificate"
else
    info "Directory /tmp/cert doesn't exist, skipping import."
fi

if [[ "$NIFI_CLUSTER_IS_NODE" == "true" && "$IS_STATEFUL_SET" == "true" ]]; then
    #cluster case with StatefulSet, certificates are numbered and all are mounted by number:
    numberNode=${HOSTNAME##"$MICROSERVICE_NAME"-}
    info "Current cluster node number = $numberNode, looking up certificates for this number..."

    path="/tmp/tls-certs-$numberNode/truststore.p12"
    if [[ -f "$path" ]]; then
        export TRUSTSTORE_PATH="$path"
    else
        error "Cannot find truststore.p12 for node = $numberNode under path $path"
        sleep 10
        exit 1
    fi
    path="/tmp/tls-certs-$numberNode/keystore.p12"
    if [[ -f "$path" ]]; then
        export KEYSTORE_PATH="$path"
    else
        error "cannot find keystore.p12 for node = $numberNode under path $path"
        sleep 10
        exit 1
    fi
else
    #non-cluster case or cluster with Deployments:
    export TRUSTSTORE_PATH="/tmp/tls-certs/truststore.p12"
    export KEYSTORE_PATH="/tmp/tls-certs/keystore.p12"
fi

export TRUSTSTORE_TYPE="PKCS12"
export KEYSTORE_TYPE="PKCS12"
TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD//&/\\&}"
export TRUSTSTORE_PASSWORD
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD//&/\\&}"
export KEYSTORE_PASSWORD
KEY_PASSWORD="${KEY_PASSWORD//&/\\&}"
export KEY_PASSWORD

if [[ "$ZOOKEEPER_SSL_ENABLED" == "true" ]]; then
    info "ZOOKEEPER_SSL_ENABLED = true"
    prop_replace 'nifi.zookeeper.client.secure' "true"
    #use keystore w/o any keys to work w/o client auth (ssl.clientAuth = none):
    prop_replace 'nifi.zookeeper.security.keystore' "/tmp/tls-certs/truststore.p12"
    prop_replace 'nifi.zookeeper.security.keystoreType' "PKCS12"
    prop_replace 'nifi.zookeeper.security.keystorePasswd' "$TRUSTSTORE_PASSWORD"
    if [ -z "${CERTIFICATE_FILE_PASSWORD}" ]; then
        export CERTIFICATE_FILE_PASSWORD="changeit"
    fi
    prop_replace 'nifi.zookeeper.security.truststore' "/etc/ssl/certs/java/cacerts"
    prop_replace 'nifi.zookeeper.security.truststoreType' "JKS"
    prop_replace 'nifi.zookeeper.security.truststorePasswd' "$CERTIFICATE_FILE_PASSWORD"
fi


info "Done creating keystore."
