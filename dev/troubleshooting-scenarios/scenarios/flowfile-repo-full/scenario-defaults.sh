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
# Defaults for the flowfile-repo-full scenario. A variable already set in the environment wins.
# Size of the tmpfs that holds the FlowFile repository, in docker tmpfs syntax.
FLOWFILE_REPO_SIZE="${FLOWFILE_REPO_SIZE:-64m}"
# Seconds the generator keeps running after the first write error, so that the errors repeat and reach bulletins.
AFTER_FULL_SECONDS="${AFTER_FULL_SECONDS:-60}"
