#!/bin/bash -e

. /opt/nifi/scripts/logging_api.sh

set_additional_properties(){
    :;
}

call_additional_libs(){
    :;
}

set_additional_properties2(){
    :;
}

load_additional_resources(){
    :;
}

redirect_logs(){
	tail -qF "${NIFI_HOME}/logs/nifi-app.log" 2> /dev/null &
	tail_pid="$!"
}

check_java_ret_code(){
    :;
}