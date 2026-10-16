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
# Defaults for the content-archive-full scenario. A variable already set in the environment wins.
# Size of the tmpfs that holds the content repository, in docker tmpfs syntax.
CONTENT_REPO_SIZE="${CONTENT_REPO_SIZE:-1g}"
# Share of the content repository, in percent, that queued content occupies at the end of the load.
TARGET_PERCENT="${TARGET_PERCENT:-65}"
# Seconds the archive filler runs before the main load, so the archive has content. It writes 10 MB per second.
ARCHIVE_FILLER_SECONDS="${ARCHIVE_FILLER_SECONDS:-10}"
# Seconds the archive filler runs again after the target is reached, to show what new writes run into.
AFTER_TARGET_SECONDS="${AFTER_TARGET_SECONDS:-180}"
