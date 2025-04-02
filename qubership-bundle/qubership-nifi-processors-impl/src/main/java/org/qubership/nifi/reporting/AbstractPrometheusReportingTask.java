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
import io.prometheus.client.exporter.common.TextFormat;
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
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class AbstractPrometheusReportingTask extends AbstractReportingTask implements ReportingTask {

    protected Server httpServer;
    protected PrometheusMeterRegistry meterRegistry;

    protected List<PropertyDescriptor> propertyDescriptors;

    protected String namespace;
    protected String hostname;
    protected String instance;
    protected int port;


    public static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
            .name("port")
            .displayName("Server Port")
            .description("")
            .required(true)
            .defaultValue("9192")
            .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
            .build();

    protected List<PropertyDescriptor> initProperties() {
        final List<PropertyDescriptor> prop = new ArrayList<>();
        prop.add(PORT);
        return prop;
    }

    protected String getNamespace() {
        return System.getenv("NAMESPACE");
    }

    protected String getHostname() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException ex) {
            getLogger().warn("Error while getting host name {}", new Object[]{ex.getLocalizedMessage()}, ex);
            return "cloud-data-migration-nifi";
        }
    }

    @OnScheduled
    public void onScheduled(final ConfigurationContext context) {
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        port = context.getProperty(PORT).asInteger();
        namespace = getNamespace();
        hostname = getHostname();
        instance = namespace + "_" + hostname;

        try {
            httpServer = new Server(port);
            ServletContextHandler servletContextHandler = new ServletContextHandler();
            servletContextHandler.setContextPath("/");
            servletContextHandler.addServlet(new ServletHolder(new PrometheusServlet()), "/metrics");
            httpServer.setHandler(servletContextHandler);

            httpServer.start();
        } catch (Exception e) {
            getLogger().error("Error while starting Jetty server {}", e);
            throw new ProcessException("Error while starting Jetty server {}", e);
        }
    }

    @Override
    protected void init(ReportingInitializationContext config) {
        final List<PropertyDescriptor> prop = initProperties();
        propertyDescriptors = Collections.unmodifiableList(prop);
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }


    @OnStopped
    public void onStopped() throws Exception {
        if (httpServer != null) {
            getLogger().info("Jetty server is stopping");
            httpServer.stop();
        }
    }

    @OnShutdown
    public void onShutDown() throws Exception {
        if (httpServer != null) {
            getLogger().info("Jetty server is shutting down");
            httpServer.stop();
        }
    }

    @Override
    public void onTrigger(final ReportingContext context) {
        registerMetrics(context);
    }

    public abstract void registerMetrics(ReportingContext context);

    protected class PrometheusServlet extends HttpServlet {

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType(TextFormat.CONTENT_TYPE_004);

            try (Writer writer = resp.getWriter()) {
                TextFormat.write004(writer, meterRegistry.getPrometheusRegistry().metricFamilySamples());
                writer.flush();
            } catch (IOException e) {
                getLogger().error("Error while scraping metrics {}", e);
                throw new ProcessException("Error while scraping metrics {}", e);
            }
        }
    }

    public PrometheusMeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    public void setMeterRegistry(PrometheusMeterRegistry newMeterRegistry) {
        this.meterRegistry = newMeterRegistry;
    }

    public void setNamespace(String newNamespace) {
        this.namespace = newNamespace;
    }

    public void setHostname(String newHostname) {
        this.hostname = newHostname;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String newInstance) {
        this.instance = newInstance;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int newPort) {
        this.port = newPort;
    }

}
