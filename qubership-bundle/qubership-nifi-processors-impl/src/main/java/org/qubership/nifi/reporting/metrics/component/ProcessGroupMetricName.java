/*
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.nifi.reporting.metrics.component;

public enum ProcessGroupMetricName {

    COMPONENT_COUNT_METRIC_NAME("nc_nifi_pg_component_count"),
    BULLETIN_COUNT_METRIC_NAME("nc_nifi_pg_bulletin_count"),
    BULLETIN_CNT_METRIC_NAME("nc_nifi_pg_bulletin_cnt"),
    ACTIVE_THREAD_COUNT_METRIC_NAME("nc_nifi_pg_active_thread_count"),
    QUEUED_COUNT_PG_METRIC_NAME("nc_nifi_pg_queued_count"),
    QUEUED_BYTES_PG_METRIC_NAME("nc_nifi_pg_queued_bytes"),

    ROOT_ACTIVE_THREAD_COUNT_METRIC_NAME("nifi_amount_threads_active"),
    ROOT_QUEUED_COUNT_PG_METRIC_NAME("nifi_amount_items_queued"),
    ROOT_QUEUED_BYTES_PG_METRIC_NAME("nifi_size_content_queued_total");

    private final String name;

    ProcessGroupMetricName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
