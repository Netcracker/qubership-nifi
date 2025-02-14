#!/bin/bash
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


info(){
    local message="$1"
    local script="${BASH_SOURCE[1]##*/}"
    local dfmt=""
    printf -v dfmt '%(%Y-%m-%dT%H:%M:%S.000)T' -1
    echo "[$dfmt] [INFO] [request_id=] [tenant_id=] [thread=main] [class=c.n.c.n.extensions.$script] $message"
}

warn(){
    local message="$1"
    local script="${BASH_SOURCE[1]##*/}"
    local dfmt=""
    printf -v dfmt '%(%Y-%m-%dT%H:%M:%S.000)T' -1
    echo "[$dfmt] [WARN] [request_id=] [tenant_id=] [thread=main] [class=c.n.c.n.extensions.$script] $message"
}

error(){
    local message="$1"
    local script="${BASH_SOURCE[1]##*/}"
    local dfmt=""
    printf -v dfmt '%(%Y-%m-%dT%H:%M:%S.000)T' -1
    echo "[$dfmt] [ERROR] [request_id=] [tenant_id=] [thread=main] [class=c.n.c.n.extensions.$script] $message"
}
