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

/**
 * Enum for process group metric names.
 */
public enum ProcessGroupMetricName {

    /**
     * nc_nifi_pg_component_count metric.
     */
    COMPONENT_COUNT_METRIC_NAME("nc_nifi_pg_component_count"),
    /**
     * nc_nifi_pg_bulletin_count metric.
     */
    BULLETIN_COUNT_METRIC_NAME("nc_nifi_pg_bulletin_count"),
    /**
     * nc_nifi_pg_bulletin_cnt metric.
     */
    BULLETIN_CNT_METRIC_NAME("nc_nifi_pg_bulletin_cnt"),
    /**
     * nc_nifi_pg_active_thread_count metric.
     */
    ACTIVE_THREAD_COUNT_METRIC_NAME("nc_nifi_pg_active_thread_count"),
    /**
     * nc_nifi_pg_queued_count metric.
     */
    QUEUED_COUNT_PG_METRIC_NAME("nc_nifi_pg_queued_count"),
    /**
     * nc_nifi_pg_queued_bytes metric.
     */
    QUEUED_BYTES_PG_METRIC_NAME("nc_nifi_pg_queued_bytes"),

    /**
     * nifi_amount_threads_active metric.
     */
    ROOT_ACTIVE_THREAD_COUNT_METRIC_NAME("nifi_amount_threads_active"),
    /**
     * nifi_amount_items_queued metric.
     */
    ROOT_QUEUED_COUNT_PG_METRIC_NAME("nifi_amount_items_queued"),
    /**
     * nifi_size_content_queued_total metric.
     */
    ROOT_QUEUED_BYTES_PG_METRIC_NAME("nifi_size_content_queued_total");

    private final String name;

    /**
     * Create instance of ProcessGroupMetricName enum.
     * @param metricName metric name.
     */
    ProcessGroupMetricName(final String metricName) {
        this.name = metricName;
    }

    /**
     * Get metric name.
     * @return name
     */
    public String getName() {
        return name;
    }
}
