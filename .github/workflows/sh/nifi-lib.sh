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
