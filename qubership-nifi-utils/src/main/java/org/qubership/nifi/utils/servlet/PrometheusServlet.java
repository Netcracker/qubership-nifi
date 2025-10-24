package org.qubership.nifi.utils.servlet;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.nifi.logging.ComponentLog;

import java.io.IOException;
import java.io.Writer;

/**
 * Servlet for exposing prometheus metrics.
 */
public class PrometheusServlet extends HttpServlet {
    private final PrometheusMeterRegistry meterRegistry;
    private final ComponentLog log;

    /**
     * Default constructor.
     * @param mr meter registry
     */
    public PrometheusServlet(final PrometheusMeterRegistry mr, final ComponentLog logger) {
        this.meterRegistry = mr;
        this.log = logger;
    }

    /**
     * Handles get requests for the server. Gets metrics in prometheus format.
     * @param req http request
     * @param resp http response
     */
    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(TextFormat.CONTENT_TYPE_004);
        try (Writer writer = resp.getWriter()) {
            TextFormat.write004(writer, meterRegistry.getPrometheusRegistry().metricFamilySamples());
            writer.flush();
        } catch (IOException e) {
            log.error("Error while scraping metrics {}", e);
            //reset response and write error response:
            resp.reset();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType("application/text");
            try (Writer writer = resp.getWriter()) {
                writer.write("Failed to write metrics in response. See logs for more details.");
                writer.flush();
            } catch (IOException ex) {
                log.error("Error while writing error response {}", ex);
            }
        }
    }
}
