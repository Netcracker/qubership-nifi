package org.qubership.nifi.reporting;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.processor.exception.ProcessException;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.qubership.nifi.service.MeterRegistryProvider;
import org.qubership.nifi.utils.servlet.PrometheusServlet;

public class TestMeterRegistryProvider
        extends AbstractControllerService
        implements MeterRegistryProvider {

    private PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private Server httpServer;

    @OnEnabled
    public void onScheduled(final ConfigurationContext context) {
        try {
            httpServer = new Server(9192);
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

    /**
     * Stops jetty server and releases all resources.
     */
    @OnDisabled
    public void onStopped() throws Exception {
        if (httpServer != null) {
            getLogger().info("Jetty server is shutting down");
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

    @Override
    public PrometheusMeterRegistry getMeterRegistry() {
        return this.meterRegistry;
    }
}
