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

package org.qubership.nifi.reporting;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.AbstractReportingTask;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.apache.nifi.reporting.ReportingTask;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;

import org.qubership.nifi.service.MeterRegistryProvider;
import org.qubership.nifi.utils.servlet.PrometheusServlet;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Abstract reporting task for exposing monitoring metrics to Prometheus.
 */
public abstract class AbstractPrometheusReportingTask extends AbstractReportingTask implements ReportingTask {

    /**
     * HTTP server.
     */
    protected Server httpServer;
    /**
     * Meter Registry.
     */
    protected PrometheusMeterRegistry meterRegistry;

    /**
     * List of property descriptors.
     */
    protected List<PropertyDescriptor> propertyDescriptors;

    /**
     * K8s namespace.
     */
    protected String namespace;
    /**
     * NiFi hostname.
     */
    protected String hostname;
    /**
     * NiFi instance identifier.
     */
    protected String instance;
    /**
     * Port to expose metrics on.
     */
    protected int port;

    /**
     * Meter Registry Provider.
     */
    protected MeterRegistryProvider meterRegistryProvider;

    /**
     * Server Port property descriptor.
     */
    public static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
            .name("port")
            .displayName("Server Port")
            .description("")
            .defaultValue("9192")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .build();

    /**
     * Reporting Controller Service property descriptor.
     */
    public static final PropertyDescriptor METER_REGISTRY_PROVIDER = new PropertyDescriptor.Builder()
            .name("meter-registry-provider")
            .displayName("Meter Registry Provider")
            .description("Identifier of Controller Services, which is used to obtain the Meter Registry.")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Initializes list of property descriptors supported by this reporting task.
     * @return list of property descriptors
     */
    protected List<PropertyDescriptor> initProperties() {
        final List<PropertyDescriptor> prop = new ArrayList<>();
        prop.add(PORT);
        prop.add(METER_REGISTRY_PROVIDER);
        return prop;
    }

    /**
     * Gets namespace used to run nifi service.
     * @return namespace
     */
    protected String getNamespace() {
        return System.getenv("NAMESPACE");
    }

    /**
     * Gets hostname used to run nifi service.
     * @return hostname
     */
    protected String getHostname() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException ex) {
            getLogger().warn("Error while getting host name {}", new Object[]{ex.getLocalizedMessage()}, ex);
            return "cloud-data-migration-nifi";
        }
    }

    /**
     * Initializes reporting task before it's started.
     * @param context reporting context
     */
    @OnScheduled
    public void onScheduled(final ConfigurationContext context) {
        meterRegistryProvider = context.getProperty(METER_REGISTRY_PROVIDER)
                .asControllerService(MeterRegistryProvider.class);
        namespace = getNamespace();
        hostname = getHostname();
        instance = namespace + "_" + hostname;
        if (meterRegistryProvider != null) {
            meterRegistry = meterRegistryProvider.getMeterRegistry();
        } else {
            meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            port = context.getProperty(PORT).asInteger();

            try {
                httpServer = new Server(port);
                ServletContextHandler servletContextHandler = new ServletContextHandler();
                servletContextHandler.setContextPath("/");
                servletContextHandler.addServlet(new ServletHolder(
                        new PrometheusServlet(meterRegistry, getLogger())), "/metrics");
                httpServer.setHandler(servletContextHandler);

                httpServer.start();
            } catch (Exception e) {
                getLogger().error("Error while starting Jetty server {}", e);
                throw new ProcessException("Error while starting Jetty server {}", e);
            }
        }
    }

    /**
     * Initializes reporting task's property descriptors.
     */
    @Override
    protected void init(ReportingInitializationContext config) {
        final List<PropertyDescriptor> prop = initProperties();
        propertyDescriptors = Collections.unmodifiableList(prop);
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    /**
     * Stops jetty server and releases all resources.
     */
    @OnStopped
    public void onStopped() throws Exception {
        if (httpServer != null) {
            getLogger().info("Jetty server is stopping");
            httpServer.stop();
        }
    }

    /**
     * Stops jetty server and releases all resources.
     */
    @OnShutdown
    public void onShutDown() throws Exception {
        if (httpServer != null) {
            getLogger().info("Jetty server is shutting down");
            httpServer.stop();
        }
    }

    /**
     * This method is periodically called to update metrics in meter registry.
     * @param context  reporting context
     */
    @Override
    public void onTrigger(final ReportingContext context) {
        registerMetrics(context);
    }

    /**
     * Registers metrics in meter registry and updates their values.
     * @param context reporting context
     */
    public abstract void registerMetrics(ReportingContext context);

    /**
     * Gets prometheus meter registry.
     * @return PrometheusMeterRegistry object
     */
    public PrometheusMeterRegistry getMeterRegistry() {
        if (meterRegistryProvider != null) {
            return meterRegistryProvider.getMeterRegistry();
        } else {
            return meterRegistry;
        }
    }

    /**
     * Gets instance name.
     * @return instance name
     */
    public String getInstance() {
        return instance;
    }

    /**
     * Gets server port.
     * @return server port number
     */
    public int getPort() {
        return port;
    }
}
