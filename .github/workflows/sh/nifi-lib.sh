#!/bin/bash -e

wait_for_service() {
    local serviceHost="$1"
    local servicePort="$2"
    local apiUrlToCheck="$3"
    local timeout="$4"
    local isTls="$5"
    local tlsAdditionalCAs="$6"
    local tlsClientKeystore="$7"
    local tlsClientPassword="$8"

    if [ -z "$timeout" ]; then
        echo "Using default timeout = 180 seconds"
        timeout=180
    fi
    if [ -z "$serviceHost" ]; then
        echo "Using default host = 127.0.0.1"
        serviceHost="127.0.0.1"
    fi
    if [ -z "$servicePort" ]; then
        echo "Using default port = 8080"
        servicePort="8080"
    fi
    if [ -z "$apiUrlToCheck" ]; then
        echo "Using default apiUrlToCheck = /nifi-api/controller/config"
        apiUrlToCheck='/nifi-api/controller/config'
    fi

    serviceUrl=""
    tlsArgs=""
    if [ "${isTls}" == "true" ]; then
        echo "Using TLS mode..."
        echo "Waiting for service to be available on port $servicePort with timeout = $timeout"
        serviceUrl="https://$serviceHost:$servicePort$apiUrlToCheck"
        echo "Client keystore: $tlsClientKeystore (p12), ca cert = $tlsAdditionalCAs"
        tlsArgs=" --cert '$tlsClientKeystore:$tlsClientPassword' --cert-type P12 --cacert $tlsAdditionalCAs"
    else
        echo "Using plain mode..."
        echo "Waiting for service to be available on port 8080 with timeout = $timeout"
        serviceUrl="http://$serviceHost:$servicePort$apiUrlToCheck"
    fi

    startTime=$(date +%s)
    endTime=$((startTime + timeout))
    remainingTime="$timeout"
    res=1
    while [ "$res" != "0" ]; do
        echo "Waiting for service to be available under URL = $serviceUrl, remaining time = $remainingTime"
        res=0
        resp_code=""
        resp_code=$(eval curl -sS -w '%{response_code}' -o ./temp-resp.json --connect-timeout 5 --max-time 10 "$tlsArgs" "$serviceUrl") || {
            res="$?"
            echo "Failed to call service API, continue waiting..."
        }
        if [ "$res" == "0" ]; then
            if [ "$resp_code" == '409' ]; then
                echo "Got response with code = $resp_code and body: "
                cat ./temp-resp.json
                echo "Continue waiting..."
                res="1"
            elif [ "$resp_code" != '200' ]; then
                echo "Got response with code = $resp_code and body: "
                cat ./temp-resp.json
            fi
        fi
        echo ""
        currentTime=$(date +%s)
        remainingTime=$((endTime - currentTime))
        if ((currentTime > endTime)); then
            echo "ERROR: timeout reached; failed to wait"
            return 1
        fi
        sleep 2
    done
    echo "Wait finished successfully. Service is available."
}

generate_random_hex_password() {
    #args -- letters, numbers
    echo "$(tr -dc A-F </dev/urandom | head -c "$1")""$(tr -dc 0-9 </dev/urandom | head -c "$2")" | fold -w 1 | shuf | tr -d '\n'
}

generate_random_password() {
    #args -- letters, numbers, special characters
    echo "$(tr -dc '[:lower:]''[:upper:]' </dev/urandom | head -c "$1")""$(tr -dc 0-9 </dev/urandom | head -c "$2")""\
$(tr -dc '!@#%^*()-+{}=`~,<>./?' </dev/urandom | head -c "$3")" | fold -w 1 | shuf | tr -d '\n'
}

generate_uuid() {
    head=$(head -c 16 /dev/urandom | od -An -t x1 | tr -d ' ')
    echo "${head:0:8}-${head:8:4}-${head:12:4}-${head:16:4}-${head:20:12}"
}

get_next_summary_file_name() {
    current_steps_count=$(find "./test-results/$1" -name "summary_*.txt" | wc -l)
    echo "summary_step$(printf %03d $((current_steps_count + 1))).txt"
}

configure_log_level() {
    local targetPkg="$1"
    local targetLevel="$2"
    local secretId="$3"
    local consulUrl="$4"
    local ns="$5"
    if [ -z "$consulUrl" ]; then
        consulUrl='http://localhost:8500'
    fi
    if [ -z "$ns" ]; then
        ns='local'
    fi
    echo "Configuring log level = $targetLevel for $targetPkg..."
    targetPath=$(echo "logger.$targetPkg" | sed 's|\.|/|g')
    echo "Consul URL = $consulUrl, namespace = $ns, targetPath = $targetPath"
    rm -rf ./consul-put-resp.txt
    respCode=$(curl -X PUT -sS --data "$targetLevel" -w '%{response_code}' -o ./consul-put-resp.txt --header "X-Consul-Token: ${secretId}" \
        "$consulUrl/v1/kv/config/$ns/application/$targetPath")
    echo "Response code = $respCode"
    if [ "$respCode" == "200" ]; then
        echo "Successfully set log level in consul"
        rm -rf ./consul-put-resp.txt
    else
        echo "Failed to set log level in Consul. Response code = $respCode. Error message:"
        cat ./consul-put-resp.txt
        return 1
    fi
}

set_configuration_version() {
    local version="$1"
    local secretId="$2"
    local consulUrl="$3"
    local ns="$4"
    if [ -z "$secretId" ]; then
        secretId=""
    fi
    if [ -z "$consulUrl" ]; then
        consulUrl='http://localhost:8500'
    fi
    if [ -z "$ns" ]; then
        ns='local'
    fi
    echo "Configuring version = $version for restore..."
    rm -rf ./consul-put-ver-resp.txt
    respCode=$(curl -X PUT -sS --data "$version" -w '%{response_code}' -o ./consul-put-ver-resp.txt --header "X-Consul-Token: ${secretId}" \
        "$consulUrl/v1/kv/config/$ns/qubership-nifi/nifi-restore-version")
    echo "Response code = $respCode"
    if [ "$respCode" == "200" ]; then
        echo "Successfully set configuring version in consul"
        rm -rf ./consul-put-ver-resp.txt
    else
        echo "Failed to set version for restore in Consul. Response code = $respCode. Error message:"
        cat ./consul-put-ver-resp.txt
        return 1
    fi
}

get_flow_json_version() {
    local dockerComposePath="$1"
    echo "Getting flow.json version from archive folder..."
    CONF_VERSION=$(docker compose -f "$dockerComposePath" --env-file ./docker.env exec nifi find /opt/nifi/nifi-current/persistent_conf/conf/archive -name "*.json.gz" -type f -exec stat --format="%Y %n" '{}' + | sort '-nr' | head -n 1 | cut -d' ' -f2- | xargs basename)
    export CONF_VERSION
    echo "$CONF_VERSION" >./nifi-conf-version.tmp
}

get_flow_json_version_error() {
    echo "Creating a non-existent version of flow.json.gz..."
    CONF_VERSION_ERROR="20850421T211330+0000_flow.json.gz"
    export CONF_VERSION_ERROR
    echo "$CONF_VERSION_ERROR" >./nifi-conf-version.tmp
}

test_log_level() {
    local targetPkg="$1"
    local targetLevel="$2"
    local resultsDir="$3"
    local containerName="$4"
    local secretId="$5"
    resultsPath="./test-results/$resultsDir"
    echo "Testing Consul logging parameters configuration for package = $targetPkg, level = $targetLevel"
    echo "Results path = $resultsPath"
    configure_log_level "$targetPkg" "$targetLevel" "$secretId" ||
        echo "Consul config failed" >"$resultsPath/failed_consul_config.lst"
    echo "Waiting 20 seconds..."
    sleep 20
    echo "Copying logback.xml..."
    docker cp "$containerName":/opt/nifi/nifi-current/conf/logback.xml "$resultsPath/logback.xml"
    res="0"
    grep "$targetPkg" "$resultsPath/logback.xml" | grep 'logger' | grep "$targetLevel" || res="1"
    summaryFileName=$(get_next_summary_file_name "$resultsDir")
    if [ "$res" == "0" ]; then
        echo "Logback configuration successfully applied"
        echo "| Logging levels configuration                   | Success :white_check_mark: |" >"$resultsPath/$summaryFileName"
    else
        echo "Logback configuration failed to apply"
        echo "NiFi logger config update failed" >"$resultsPath/failed_log_config.lst"
        echo "| Logging levels configuration                   | Failed :x:                 |" >"$resultsPath/$summaryFileName"
    fi
}

prepare_sens_key() {
    echo "Generating temporary sensitive key..."
    NIFI_SENSITIVE_KEY=$(generate_random_password 12 4 4)
    export NIFI_SENSITIVE_KEY
    echo "$NIFI_SENSITIVE_KEY" >./nifi-sens-key.tmp
}

prepare_results_dir() {
    local resultsDir="$1"
    echo "Preparing output directory $resultsDir"
    mkdir -p "./test-results/$resultsDir/"
}

wait_nifi_container() {
    local initialWait="$1"
    local waitTimeout="$2"
    local hostName="$3"
    local portNum="$4"
    local useTls="$5"
    local composeFile="$6"
    local resultsDir="$7"
    local caCert="$8"
    local clientKeystore="$9"
    local clientPassword="${10}"
    local apiUrl="/nifi-api/controller/config"
    echo "Sleep for $initialWait seconds..."
    sleep "$initialWait"
    echo "Waiting for nifi on $hostName:$portNum (TLS = $useTls, apiUrl=$apiUrl) to start..."
    wait_success="1"
    wait_for_service "$hostName" "$portNum" "$apiUrl" "$waitTimeout" "$useTls" \
        "$caCert" "$clientKeystore" "$clientPassword" || wait_success="0"
    summaryFileName=$(get_next_summary_file_name "$resultsDir")
    if [ "$wait_success" == '0' ]; then
        echo "Wait failed, nifi not available. Last 500 lines of logs for container:"
        echo "resultsDir=$resultsDir"
        docker compose -f "$composeFile" --env-file ./docker.env logs -n 1000 >./nifi_log_tmp.lst
        cat ./nifi_log_tmp.lst
        echo "Wait failed, nifi not available" >"./test-results/$resultsDir/failed_nifi_wait.lst"
        mv ./nifi_log_tmp.lst "./test-results/$resultsDir/nifi_log_after_wait.log"
        echo "| Wait for container start                       | Failed :x:                 |" >"./test-results/$resultsDir/$summaryFileName"
        return 1
    fi
    echo "| Wait for container start                       | Success :white_check_mark: |" >"./test-results/$resultsDir/$summaryFileName"
    return 0
}

wait_nifi_reg_container() {
    local hostName="$1"
    local portNum="$2"
    local composeFile="$3"
    local useTls="$4"
    local initialWait="$5"
    local waitTimeout="$6"
    local resultsDir="$7"
    local caCert="$8"
    local clientKeystore="$9"
    local clientPassword="${10}"
    local apiUrl='/nifi-registry-api/config'

    echo "Sleep for $initialWait seconds..."
    sleep "$initialWait"
    echo "Waiting for nifi registry on $hostName:$portNum (TLS = $useTls, url = $apiUrl) to start..."
    wait_success="1"
    wait_for_service "$hostName" "$portNum" "$apiUrl" "$waitTimeout" "$useTls" \
        "$caCert" "$clientKeystore" "$clientPassword" || wait_success="0"
    summaryFileName=$(get_next_summary_file_name "$resultsDir")
    if [ "$wait_success" == '0' ]; then
        echo "Wait failed, nifi registry not available. Last 500 lines of logs for container:"
        echo "resultsDir=$resultsDir"
        docker compose -f "$composeFile" --env-file ./docker.env logs -n 1000 >./nifi_registry_log_tmp.lst
        cat ./nifi_registry_log_tmp.lst
        echo "Wait failed, nifi registry not available" >"./test-results/$resultsDir/failed_nifi_registry_wait.lst"
        mv ./nifi_registry_log_tmp.lst "./test-results/$resultsDir/nifi_registry_log_after_wait.log"
        echo "| Wait for nifi registry container start         | Failed :x:                 |" >"./test-results/$resultsDir/$summaryFileName"
        return 1
    fi
    echo "| Wait for nifi registry container start         | Success :white_check_mark: |" >"./test-results/$resultsDir/$summaryFileName"
    return 0
}

generate_tls_passwords() {
    echo "Generating passwords..."
    TRUSTSTORE_PASSWORD=$(generate_random_password 8 4 3)
    KEYSTORE_PASSWORD_NIFI=$(generate_random_password 8 4 3)
    KEYSTORE_PASSWORD_NIFI_REG=$(generate_random_password 8 4 3)
    KEYCLOAK_TLS_PASS=$(generate_random_hex_password 8 4)
    ZK_TLS_PASS=$(generate_random_hex_password 8 4)
    export TRUSTSTORE_PASSWORD
    export KEYSTORE_PASSWORD_NIFI
    export KEYSTORE_PASSWORD_NIFI_REG
    export KEYCLOAK_TLS_PASS
    export ZK_TLS_PASS
}

create_docker_env_file() {
    echo "Generating environment file for docker-compose..."
    echo "TRUSTSTORE_PASSWORD=$TRUSTSTORE_PASSWORD" >./docker.env
    echo "KEYSTORE_PASSWORD_NIFI=$KEYSTORE_PASSWORD_NIFI" >>./docker.env
    echo "KEYSTORE_PASSWORD_NIFI_REG=$KEYSTORE_PASSWORD_NIFI_REG" >>./docker.env
    DB_PASSWORD=$(generate_random_hex_password 8 4)
    export DB_PASSWORD
    echo "DB_PASSWORD=$DB_PASSWORD" >>./docker.env
    KEYCLOAK_ADMIN_PASSWORD=$(generate_random_hex_password 8 4)
    export KEYCLOAK_ADMIN_PASSWORD
    echo "KEYCLOAK_ADMIN_PASSWORD=$KEYCLOAK_ADMIN_PASSWORD" >>./docker.env
    gitDir="$(pwd)"
    echo "BASE_DIR=$gitDir" >>./docker.env
    echo "KEYCLOAK_TLS_PASS=$KEYCLOAK_TLS_PASS" >>./docker.env
    CONSUL_TOKEN=$(generate_uuid)
    echo "$CONSUL_TOKEN" >./consul-acl-token.tmp
    echo "$CONSUL_TOKEN" >./consul-acl-write-token.tmp
    export CONSUL_TOKEN
    CONSUL_READ_TOKEN=$(generate_uuid)
    echo "$CONSUL_READ_TOKEN" >./consul-acl-read-token.tmp
    export CONSUL_READ_TOKEN
    echo "CONSUL_TOKEN=$CONSUL_TOKEN" >>./docker.env
    echo "CONSUL_READ_TOKEN=$CONSUL_READ_TOKEN" >>./docker.env
    REDIS_PASSWORD=$(generate_random_hex_password 8 4)
    export REDIS_PASSWORD
    echo "REDIS_PASSWORD=$REDIS_PASSWORD" >>./docker.env
    export ZK_TLS_PASS
    echo "ZK_TLS_PASS=$ZK_TLS_PASS" >>./docker.env
}

create_docker_env_file_plain() {
    echo "Generating environment file for docker-compose..."
    gitDir="$(pwd)"
    echo "BASE_DIR=$gitDir" >>./docker.env
}

create_global_vars_file() {
    echo "Generating file with global vars for newman..."
    gitDir="$(pwd)"
    tmp=$(mktemp)
    jq --arg pass "$DB_PASSWORD" --arg redisPass "$REDIS_PASSWORD" \
        '(.values[] | select(.key == "global.db.pass") | .value) = $pass | (.values[] | select(.key == "global.redis.pass") | .value) = $redisPass' \
        "${gitDir}/.github/collections/Global_Vars.postman_globals.json" >"$tmp" &&
        mv "$tmp" "${gitDir}/.github/collections/Global_Vars.postman_globals.json"
}

generate_add_nifi_certs() {
    keytool -genkeypair -alias keycloakCA -keypass "$KEYCLOAK_TLS_PASS" -keystore ./temp-vol/tls-cert/keycloak.p12 -storetype PKCS12 \
        -storepass "$KEYCLOAK_TLS_PASS" -keyalg RSA -keysize 2048 -validity 720 -dname "CN=keycloakCA" -ext bc:c
    keytool -genkeypair  -alias keycloakServer -keypass "$KEYCLOAK_TLS_PASS" -keystore ./temp-vol/tls-cert/keycloak.p12 -storetype PKCS12 \
        -storepass "$KEYCLOAK_TLS_PASS" -keyalg RSA -keysize 2048 -validity 720 -dname "CN=keycloak" -signer keycloakCA -signerkeypass \
        "$KEYCLOAK_TLS_PASS" -ext SAN=dns:keycloak,dns:localhost
    keytool -importkeystore -srckeystore ./temp-vol/tls-cert/keycloak.p12 -destkeystore ./temp-vol/tls-cert/keycloak-server.p12 -srcstoretype PKCS12 \
        -deststoretype PKCS12 -srcstorepass "$KEYCLOAK_TLS_PASS" -deststorepass "$KEYCLOAK_TLS_PASS" -srcalias \
        keycloakServer -destalias keycloakServer -srckeypass "$KEYCLOAK_TLS_PASS" -destkeypass "$KEYCLOAK_TLS_PASS"
    keytool -exportcert -keystore ./temp-vol/tls-cert/keycloak.p12 -storetype PKCS12 -storepass "$KEYCLOAK_TLS_PASS" -alias keycloakCA -rfc \
        -file ./temp-vol/tls-cert/ca/keycloak-ca.cer
    keytool -importcert -keystore ./temp-vol/tls-cert/keycloak-server.p12 -storetype PKCS12 -storepass "$KEYCLOAK_TLS_PASS" \
        -file ./temp-vol/tls-cert/ca/keycloak-ca.cer -alias keycloak-ca-cer -noprompt
}

generate_zookeeper_certs() {
    keytool -genkeypair -alias zkCA -keypass "$ZK_TLS_PASS" -keystore ./temp-vol/tls-cert/zookeeper-ca-keystore.p12 -storetype PKCS12 \
        -storepass "$ZK_TLS_PASS" -keyalg RSA -keysize 2048 -validity 720 -dname "CN=zkCA" -ext bc:c
    keytool -genkeypair -alias zkServer1 -keypass "$ZK_TLS_PASS" -keystore ./temp-vol/tls-cert/zookeeper-ca-keystore.p12 -storetype PKCS12 \
        -storepass "$ZK_TLS_PASS" -keyalg RSA -keysize 2048 -validity 720 -dname "CN=zookeeper" -signer zkCA -signerkeypass \
        "$ZK_TLS_PASS" -ext SAN=dns:zookeeper,dns:localhost
    keytool -genkeypair -alias zkQuorum1 -keypass "$ZK_TLS_PASS" -keystore ./temp-vol/tls-cert/zookeeper-ca-keystore.p12 -storetype PKCS12 \
        -storepass "$ZK_TLS_PASS" -keyalg RSA -keysize 2048 -validity 720 -dname "CN=zookeeper" -signer zkCA -signerkeypass \
        "$ZK_TLS_PASS" -ext SAN=dns:zookeeper,dns:localhost
    keytool -importkeystore -srckeystore ./temp-vol/tls-cert/zookeeper-ca-keystore.p12 -destkeystore ./temp-vol/tls-cert/zookeeper-server.p12 -srcstoretype PKCS12 \
        -deststoretype PKCS12 -srcstorepass "$ZK_TLS_PASS" -deststorepass "$ZK_TLS_PASS" -srcalias \
        zkServer1 -destalias zkServer1 -srckeypass "$ZK_TLS_PASS" -destkeypass "$ZK_TLS_PASS"
    keytool -importkeystore -srckeystore ./temp-vol/tls-cert/zookeeper-ca-keystore.p12 -destkeystore ./temp-vol/tls-cert/zookeeper-quorum.p12 -srcstoretype PKCS12 \
        -deststoretype PKCS12 -srcstorepass "$ZK_TLS_PASS" -deststorepass "$ZK_TLS_PASS" -srcalias \
        zkQuorum1 -destalias zkQuorum1 -srckeypass "$ZK_TLS_PASS" -destkeypass "$ZK_TLS_PASS"
    keytool -exportcert -keystore ./temp-vol/tls-cert/zookeeper-ca-keystore.p12 -storetype PKCS12 -storepass "$ZK_TLS_PASS" -alias zkCA -rfc \
        -file ./temp-vol/tls-cert/ca/zk-ca.cer
    keytool -importcert -keystore ./temp-vol/tls-cert/zookeeper-server.p12 -storetype PKCS12 -storepass "$ZK_TLS_PASS" \
        -file ./temp-vol/tls-cert/ca/zk-ca.cer -alias zk-ca-cer -noprompt
    keytool -importcert -keystore ./temp-vol/tls-cert/zookeeper-quorum.p12 -storetype PKCS12 -storepass "$ZK_TLS_PASS" \
        -file ./temp-vol/tls-cert/ca/zk-ca.cer -alias zk-ca-cer -noprompt
    keytool -importcert -keystore ./temp-vol/tls-cert/zookeeper-truststore.p12 -storetype PKCS12 -storepass "$ZK_TLS_PASS" \
        -file ./temp-vol/tls-cert/ca/zk-ca.cer -alias zk-ca-cer -noprompt
}

prepare_zookeeper_configuration() {
    if [ -n "$ZK_TLS_PASS" ]; then
        sed -i "s/^ssl.quorum.keyStore.password=.*$/ssl.quorum.keyStore.password=$ZK_TLS_PASS/" \
            ./.github/configuration/zookeeper/zoo.cfg
        sed -i "s/^ssl.quorum.trustStore.password=.*$/ssl.quorum.trustStore.password=$ZK_TLS_PASS/" \
            ./.github/configuration/zookeeper/zoo.cfg
        sed -i "s/^ssl.trustStore.password=.*$/ssl.trustStore.password=$ZK_TLS_PASS/" \
            ./.github/configuration/zookeeper/zoo.cfg
        sed -i "s/^ssl.keyStore.password=.*$/ssl.keyStore.password=$ZK_TLS_PASS/" \
            ./.github/configuration/zookeeper/zoo.cfg
    fi
}

setup_env_before_tests() {
    local runMode="$1"
    #generic case:
    prepare_sens_key
    prepare_results_dir "$runMode"
    if [ "$runMode" == "plain" ]; then
        create_docker_env_file_plain
    else
        generate_tls_passwords
        create_docker_env_file
    fi
    if [[ "$runMode" == "oidc" ]]; then
        create_global_vars_file
    fi
    mkdir -p ./temp-vol/tls-cert/
    mkdir -p ./temp-vol/tls-cert/ca/
    mkdir -p ./temp-vol/tls-cert/nifi/
    mkdir -p ./temp-vol/tls-cert/nifi-registry/
    if [[ "$runMode" == "oidc" ]] || [[ "$runMode" == "cluster"* ]]; then
        mkdir -p ./temp-vol/pg-db/
    fi
    if [[ "$runMode" == "cluster"* ]]; then
        mkdir -p ./temp-vol/tls-cert/qubership-nifi-0/
        mkdir -p ./temp-vol/tls-cert/qubership-nifi-1/
        mkdir -p ./temp-vol/tls-cert/qubership-nifi-2/
        mkdir -p ./temp-vol/nifi-0/per-conf/
        mkdir -p ./temp-vol/nifi-1/per-conf/
        mkdir -p ./temp-vol/nifi-2/per-conf/
        #generate zookeeper certificates:
        generate_zookeeper_certs
    else
        mkdir -p ./temp-vol/nifi/per-conf/
        mkdir -p ./temp-vol/nifi/extensions/
        echo "Copying test NARs to extensions directory"
        cp qubership-test-bundle/qubership-nifi-test-nar/target/qubership-nifi-test-nar-*.nar \
            ./temp-vol/nifi/extensions/
    fi
    chmod -R 777 ./temp-vol
    #generate keycloak certificates:
    if [[ "$runMode" == "oidc" ]] || [[ "$runMode" == "cluster"* ]]; then
        generate_add_nifi_certs
    fi
}

regenerate_consul_token() {
    NEW_CONSUL_TOKEN=$(generate_uuid)
    regex="^CONSUL_TOKEN=.*$"
    if grep -qE "$regex" ./docker.env; then
        sed -i "s/$regex/CONSUL_TOKEN=$NEW_CONSUL_TOKEN/" ./docker.env
    else
        echo "CONSUL_TOKEN=$NEW_CONSUL_TOKEN" >>./docker.env
    fi
}

check_container_not_started() {
    local logMessage="$1"
    local containerName="$2"
    local composeFile="$3"
    local resultsDir="$4"
    local timeout="$5"

    if [ -z "$timeout" ]; then
        echo "Using default timeout = 30 seconds"
        timeout=30
    fi

    startTime=$(date +%s)
    endTime=$((startTime + timeout))
    remainingTime="$timeout"
    res=1
    while [ "$res" != "0" ]; do
        echo "Waiting for container $containerName to be available, remaining time = $remainingTime"
        res=0
        logs=$(docker compose -f "$composeFile" --env-file ./docker.env logs) || {
            res="$?"
            echo "Failed to get logs from $containerName, continue waiting..."
        }
        if [ "$res" == "0" ]; then
            if echo "$logs" | grep -E "$logMessage"; then
                echo "Message '$logMessage' found in container logs '$containerName'."
            else
                echo "Message '$logMessage' not found in container logs '$containerName'."
                res="1"
            fi
        fi
        echo ""
        currentTime=$(date +%s)
        remainingTime=$((endTime - currentTime))
        if ((currentTime > endTime)); then
            echo "ERROR: timeout reached; failed to wait"
            echo "Wait failed, nifi container not available. Last 500 lines of logs for container:"
            echo "resultsDir=$resultsDir"
            docker compose -f "$composeFile" --env-file ./docker.env logs -n 1000 >./nifi_log_tmp.lst
            cat ./nifi_log_tmp.lst
            echo "Wait failed, nifi not available" >"./test-results/$resultsDir/failed_nifi_wait.lst"
            mv ./nifi_log_tmp.lst "./test-results/$resultsDir/nifi_log_after_wait.log"
            echo "| Wait expected error message in container logs                       | Failed :x:                 |" >"./test-results/$resultsDir/$summaryFileName"
            return 1
        fi
        sleep 2
    done
    echo "Wait finished successfully. Expected log message found."
    summaryFileName=$(get_next_summary_file_name "$resultsDir")
    echo "| Wait expected error message in container logs                 | Success :white_check_mark: |" >"./test-results/$resultsDir/$summaryFileName"
    return 0
}
