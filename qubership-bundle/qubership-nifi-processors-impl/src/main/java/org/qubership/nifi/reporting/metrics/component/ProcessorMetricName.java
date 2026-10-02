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

public enum ProcessorMetricName {

    /**
     * nc_nifi_processor_tasks_time_total metric.
     */
    TASKS_TIME_TOTAL_METRIC_NAME("nc_nifi_processor_tasks_time_total"),
    /**
     * nc_nifi_processor_tasks_count metric.
     */
    TASKS_COUNT_METRIC_NAME("nc_nifi_processor_tasks_count"),
    /**
     * nc_nifi_processor_cpu_duration metric.
     */
    CPU_DURATION_METRIC_NAME("nc_nifi_processor_cpu_duration"),
    /**
     * nc_nifi_processor_content_read_duration metric.
     */
    CONTENT_READ_DURATION_METRIC_NAME("nc_nifi_processor_content_read_duration"),
    /**
     * nc_nifi_processor_content_write_duration metric.
     */
    CONTENT_WRITE_DURATION_METRIC_NAME("nc_nifi_processor_content_write_duration"),
    /**
     * nc_nifi_processor_session_commit_duration metric.
     */
    SESSION_COMMIT_DURATION_METRIC_NAME("nc_nifi_processor_session_commit_duration"),
    /**
     * nc_nifi_processor_gc_duration metric.
     */
    GC_DURATION_METRIC_NAME("nc_nifi_processor_gc_duration");

    private final String name;

    /**
     * Create instance of ProcessorMetricName enum.
     * @param metricName metric name.
     */
    ProcessorMetricName(final String metricName) {
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
