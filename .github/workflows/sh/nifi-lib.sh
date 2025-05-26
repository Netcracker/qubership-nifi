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
        echo "Waiting for service to be available on port 8080 with timeout = $timeout"
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
            if [ "$resp_code" != '200' ]; then
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
    echo "$(tr -dc A-F < /dev/urandom | head -c "$1")""$(tr -dc 0-9 < /dev/urandom | head -c "$2")" | fold -w 1 | shuf | tr -d '\n'
}


configure_log_level() {
    local targetPkg="$1"
    local targetLevel="$2"
    local consulUrl="$3"
    local ns="$4"
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
    respCode=$(curl -X PUT -sS --data "$targetLevel" -w '%{response_code}' -o ./consul-put-resp.txt \
        "$consulUrl/v1/kv/config/$ns/application/$targetPath")
    echo "Response code = $respCode"
    if [ "$respCode" == "200" ]; then
        echo "Successfully set log level in consul"
        rm -rf ./consul-put-resp.txt
    else
        echo "Failed to set log level in Consul. Response code = $respCode. Error message:"
        cat ./consul-put-resp.txt
        return 1;
    fi
}

set_configuration_version() {
    local version="$1"
    local consulUrl="$2"
    local ns="$3"
    if [ -z "$consulUrl" ]; then
        consulUrl='http://localhost:8500'
    fi
    if [ -z "$ns" ]; then
        ns='local'
    fi
    echo "Configuring version = $version for restore..."
    rm -rf ./consul-put-ver-resp.txt
    respCode=$(curl -X PUT -sS --data "$version" -w '%{response_code}' -o ./consul-put-ver-resp.txt \
        "$consulUrl/v1/kv/config/$ns/qubership-nifi/nifi-restore-version")
    echo "Response code = $respCode"
    if [ "$respCode" == "200" ]; then
        echo "Successfully set configuring version in consul"
        rm -rf ./consul-put-ver-resp.txt
    else
        echo "Failed to set version for restore in Consul. Response code = $respCode. Error message:"
        cat ./consul-put-ver-resp.txt
        return 1;
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
    resultsPath="./test-results/$resultsDir"
    echo "Testing Consul logging parameters configuration for package = $targetPkg, level = $targetLevel"
    echo "Results path = $resultsPath"
    configure_log_level "$targetPkg" "$targetLevel" || \
        echo "Consul config failed" >"$resultsPath/failed_consul_config.lst"
    echo "Waiting 20 seconds..."
    sleep 20
    echo "Copying logback.xml..."
    docker cp "$containerName":/opt/nifi/nifi-current/conf/logback.xml "$resultsPath/logback.xml"
    res="0"
    grep "$targetPkg" "$resultsPath/logback.xml" | grep 'logger' | grep "$targetLevel" || res="1"
    if [ "$res" == "0" ]; then
        echo "Logback configuration successfully applied"
    else
        echo "Logback configuration failed to apply"
        echo "NiFi logger config update failed" >"$resultsPath/failed_log_config.lst"
    fi
}

prepare_sens_key() {
    echo "Generating temporary sensitive key..."
    NIFI_SENSITIVE_KEY=$(generate_random_hex_password 12 4)
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
    if [ "$wait_success" == '0' ]; then
        echo "Wait failed, nifi not available. Last 500 lines of logs for container:"
        echo "resultsDir=$resultsDir"
        docker compose -f "$composeFile" --env-file ./docker.env logs -n 1000 >./nifi_log_tmp.lst
        cat ./nifi_log_tmp.lst
        echo "Wait failed, nifi not available" >"./test-results/$resultsDir/failed_nifi_wait.lst"
        mv ./nifi_log_tmp.lst "./test-results/$resultsDir/nifi_log_after_wait.log"
    fi
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
    if [ "$wait_success" == '0' ]; then
        echo "Wait failed, nifi registry not available. Last 500 lines of logs for container:"
        echo "resultsDir=$resultsDir"
        docker logs -n 500 "$containerName" >./nifi_registry_log_tmp.lst
        cat ./nifi_registry_log_tmp.lst
        echo "Wait failed, nifi registry not available" >"./test-results/$resultsDir/failed_nifi_registry_wait.lst"
        mv ./nifi_registry_log_tmp.lst "./test-results/$resultsDir/nifi_registry_log_after_wait.log"
    fi
}

generate_tls_passwords() {
    echo "Generating passwords..."
    TRUSTSTORE_PASSWORD=$(generate_random_hex_password 8 4)
    KEYSTORE_PASSWORD_NIFI=$(generate_random_hex_password 8 4)
    KEYSTORE_PASSWORD_NIFI_REG=$(generate_random_hex_password 8 4)
    export TRUSTSTORE_PASSWORD
    export KEYSTORE_PASSWORD_NIFI
    export KEYSTORE_PASSWORD_NIFI_REG
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
    jq --arg pass "$DB_PASSWORD" '(.values[] | select(.key == "global.db.pas") | .value) = $pass' \
        "${gitDir}/.github/collections/Global_Vars.postman_globals.json" >"$tmp" && \
        mv "$tmp" "${gitDir}/.github/collections/Global_Vars.postman_globals.json"
}
