package org.qubership.nifi.utils.servlet;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.nifi.logging.ComponentLog;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrometheusServletTest {
    private PrometheusMeterRegistry meterRegistry;
    private Server prometheusServer;
    private ComponentLog log;
    private static final Logger LOG = LoggerFactory.getLogger(PrometheusServletTest.class);
    private static final int SERVER_PORT = 12345;
    private static final String SERVER_URL = "http://localhost:" + SERVER_PORT + "/metrics";
    private OkHttpClient client;

    @BeforeEach
    public void setUp() {
        log = new TestLoggerWrapper(LOG);
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        prometheusServer = new Server(SERVER_PORT);
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/");
        servletContextHandler.addServlet(new ServletHolder(
                new PrometheusServlet(meterRegistry, log)), "/metrics");
        prometheusServer.setHandler(servletContextHandler);
        try {
            prometheusServer.start();
        } catch (Exception e) {
            Assertions.fail("Failed to start prometheus server", e);
        }
        //
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.connectTimeout(2, TimeUnit.SECONDS);
        builder.readTimeout(2, TimeUnit.SECONDS);
        client = builder.build();
    }

    @AfterEach
    public void tearDown() {
        if (prometheusServer != null) {
            if (prometheusServer.isRunning()) {
                try {
                    prometheusServer.stop();
                } catch (Exception e) {
                    Assertions.fail("Failed to stop prometheus server", e);
                }
            }
        }
    }

    private static class ValueHolder {
        private double value;
        private long waitDelay;

        ValueHolder(final double initialValue) {
            this(initialValue, 0);
        }

        ValueHolder(final double initialValue, final long newWaitDelay) {
            this.value = initialValue;
            this.waitDelay = newWaitDelay;
        }

        double getValue() {
            if (waitDelay > 0) {
                try {
                    Thread.sleep(waitDelay);
                } catch (InterruptedException e) {
                    LOG.warn("Wait interrupted", e);
                    throw new RuntimeException(e);
                }
            }
            return value;
        }

        void setValue(double newValue) {
            this.value = newValue;
        }
    }

    @Test
    public void testSuccess() {
        //add metric:
        ValueHolder metricValue001 = new ValueHolder(1.5);
        Gauge.builder("test_metric_name001", metricValue001, ValueHolder::getValue)
                .tags("tag1", "tagValue1", "tag2", "tagValue2")
                .register(meterRegistry);
        //call server 1st time:
        Request request = new Request.Builder().url(SERVER_URL).get().build();
        try {
            try (Response resp = client.newCall(request).execute()) {
                assertTrue(resp.isSuccessful());
                assertEquals(TextFormat.CONTENT_TYPE_004, resp.header("Content-Type"));
                assertNotNull(resp.body());
                String responseBody = resp.body().string();
                log.debug("Response = {}", responseBody);
                assertTrue(responseBody.contains("test_metric_name001{tag1=\"tagValue1\",tag2=\"tagValue2\",} 1.5"));
            }
        } catch (IOException e) {
            Assertions.fail("Failed to call metrics endpoint", e);
        }
        //change value and call server 2nd time:
        metricValue001.setValue(2.5);
        try {
            try (Response resp = client.newCall(request).execute()) {
                assertTrue(resp.isSuccessful());
                assertEquals(TextFormat.CONTENT_TYPE_004, resp.header("Content-Type"));
                assertNotNull(resp.body());
                String responseBody = resp.body().string();
                log.debug("Response = {}", responseBody);
                assertTrue(responseBody.contains("test_metric_name001{tag1=\"tagValue1\",tag2=\"tagValue2\",} 2.5"));
            }
        } catch (IOException e) {
            Assertions.fail("Failed to call metrics endpoint", e);
        }
    }

    @Test
    public void testError() {
        //add metric with 10s delay in getting value > timeout = 5s:
        ValueHolder metricValue001 = new ValueHolder(1.5, 4000);
        Gauge.builder("test_metric_name001", metricValue001, ValueHolder::getValue)
                .tags("tag1", "tagValue1", "tag2", "tagValue2")
                .register(meterRegistry);
        //call server:
        Request request = new Request.Builder().url(SERVER_URL).get().build();
        Assertions.assertThrows(SocketTimeoutException.class, () -> {
            try (Response resp = client.newCall(request).execute()) {
                assertFalse(resp.isSuccessful());
            }
        });

        //sleep to wait for servlet completion:
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            LOG.warn("Wait interrupted", e);
        }
    }
}
