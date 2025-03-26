#!/bin/bash -e

wait_for_nifi(){
    local isTls="$1"
    local timeout="$2"
    local nifiHost="$3"
    local nifiPort="$4"
    
    if [ -z "$timeout" ]; then
        echo "Using default timeout = 180 seconds"
        timeout=180
    fi
    if [ -z "$nifiHost" ]; then
        echo "Using default nifi host = 127.0.0.1"
        nifiHost="127.0.0.1"
    fi
    if [ -z "$nifiPort" ]; then
        echo "Using default nifi port = 8080"
        nifiPort="8080"
    fi
    
    nifiUrl=""
    #TODO: additionalArguments=""
    if [ "${isTls}" == "true" ]; then
        echo "Using TLS mode..."
        echo "Waiting for nifi to be available on port 8080 with timeout = $timeout"
        nifiUrl="https://$nifiHost:$nifiPort/nifi-api/controller/config"
    else
        echo "Using plain mode..."
        echo "Waiting for nifi to be available on port 8080 with timeout = $timeout"
        nifiUrl="http://$nifiHost:$nifiPort/nifi-api/controller/config"
    fi
    
    startTime=$(date +%s)
    endTime=$((startTime+timeout))
    remainingTime="$timeout"
    res=1
    while [ "$res" != "0" ]; do
        echo "Waiting for nifi to be available under URL = $nifiUrl, remaining time = $remainingTime"
        res=0
        resp_code=""
        resp_code=$(curl -sS -w '%{response_code}' -o ./temp-resp.json --connect-timeout 5 --max-time 10 "$nifiUrl") || { res="$?"; echo "Failed to call NiFi API, continue waiting..."; }
        if [ "$res" == "0" ]; then
            if [ "$resp_code" != '200' ]; then
                echo "Got response with code = $resp_code and body: "
                cat ./temp-resp.json
            fi
        fi
        echo ""
        currentTime=$(date +%s)
        remainingTime=$((endTime-currentTime))
        if ((currentTime > endTime)); then
            echo "ERROR: timeout reached; failed to wait"
            return 1;
        fi
        sleep 2
    done
    echo "Wait finished successfully. NiFi is available."
}

generate_random_hex_password(){
    #args -- letters, numbers
    echo "$(tr -dc A-F < /dev/urandom | head -c "$1")""$(tr -dc 0-9 < /dev/urandom | head -c "$2")" | fold -w 1 | shuf | tr -d '\n'
}


configure_log_level(){
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

test_log_level(){
    local targetPkg='$1'
    local targetLevel='$2'
    local resultsDir='$3'
    resultsPath="./test-results/$resultsDir"
    echo "Testing Consul logging parameters configuration for package = $targetPkg, level = $targetLevel"
    echo "Results path = $resultsPath"
    configure_log_level "$targetPkg" "$targetLevel" || \
       echo "Consul config failed" > "$resultsPath/failed_consul_config.lst"
    echo "Waiting 20 seconds..."
    sleep 20
    echo "Copying logback.xml..."
    docker cp local-nifi-plain:/opt/nifi/nifi-current/conf/logback.xml "$resultsPath/logback.xml"
    res="0"
    grep "$targetPkg" "$resultsPath/logback.xml" | grep 'logger' | grep "$targetLevel" || res="1"
    if [ "$res" == "0" ]; then
        echo "Logback configuration successfully applied"
    else
        echo "Logback configuration failed to apply"
        echo "NiFi logger config update failed" > "$resultsPath/failed_log_config.lst"
    fi
}

prepare_sens_key(){
    echo "Generating temporary sensitive key..."
    export NIFI_SENSITIVE_KEY=$(generate_random_hex_password 14)
    echo "$NIFI_SENSITIVE_KEY" > ./nifi-sens-key.tmp
}

prepare_results_dir(){
    local resultsDir="$1"
    echo "Preparing output directory $resultsDir"
    mkdir -p "./test-results/$resultsDir/"
}

wait_nifi_container(){
    local initialWait="$1"
    local waitTimeout="$2"
    local hostName="$3"
    local portNum="$4"
    local useTls="$5"
    local containerName="$6"
    local resultsDir="$7"
    echo "Sleep for $initialWait seconds..."
    sleep $initialWait
    echo "Waiting for nifi container on $hostName:$portNum (TLS = $useTls, containerName = $containerName) to start..."
    wait_success="1"
    wait_for_nifi "$useTls" "$waitTimeout" "$hostName" "$portNum" || wait_success="0"
    if [ "$wait_success" == '0' ]; then
        echo "Wait failed, nifi not available. Last 500 lines of logs for container:"
        echo "resultsDir=$resultsDir"
        docker logs -n 500 "$containerName" > ./nifi_log_tmp.lst
        cat ./nifi_log_tmp.lst
        echo "Wait failed, nifi not available" > "./test-results/$resultsDir/failed_nifi_wait.lst"
        mv ./nifi_log_tmp.lst "./test-results/$resultsDir/nifi_log_after_wait.log"
    fi
}