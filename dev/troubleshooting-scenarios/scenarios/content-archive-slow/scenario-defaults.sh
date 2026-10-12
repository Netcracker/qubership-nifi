#!/bin/bash
# Copyright 2020-2026 NetCracker Technology Corporation
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

# shellcheck disable=SC2034
# Defaults for the content-archive-slow scenario. A variable already set in the environment wins.
# Storage of the content repository: nfs (an NFS export you provide) or local (a Docker volume, for a baseline).
STORAGE="${STORAGE:-nfs}"
# NFS server address and exported path. Required when STORAGE=nfs.
NFS_ADDR="${NFS_ADDR:-}"
NFS_EXPORT="${NFS_EXPORT:-}"
# NFS mount options, added after addr=<NFS_ADDR>.
NFS_OPTS="${NFS_OPTS:-nfsvers=4,rw}"
# Value of nifi.content.repository.archive.max.retention.period. A short period gives archive cleanup work every run.
RETENTION="${RETENTION:-2 mins}"
# Seconds the flow writes and drops content. It writes 100 FlowFiles of 110 KB per second, one claim file each.
LOAD_DURATION="${LOAD_DURATION:-600}"
