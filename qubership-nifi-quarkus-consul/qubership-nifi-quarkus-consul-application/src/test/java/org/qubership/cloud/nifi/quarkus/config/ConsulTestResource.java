package org.qubership.cloud.nifi.quarkus.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Map;

/**
 * Quarkus test resource for managing Consul container lifecycle.
 */
public class ConsulTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String CONSUL_IMAGE = "hashicorp/consul:1.20";
    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestResource.class);
    private ConsulContainer consul;

    @Override
    public Map<String, String> start() {
        LOG.info("Starting Consul container for testing...");

        consul = new ConsulContainer(DockerImageName.parse(CONSUL_IMAGE));
        consul.start();

        int consulPort = consul.getMappedPort(8500);
        LOG.info("Consul container started at localhost:{}", consulPort);

        // Fill initial consul data
        populateConsulData();

        return Map.of("quarkus.consul-source-config.agent.url", "http://localhost:" + consulPort);
    }

    @Override
    public void stop() {
        if (consul != null) {
            LOG.info("Stopping Consul container...");
            consul.stop();
        }
    }

    private void populateConsulData() {
        Container.ExecResult res = null;
        try {
            // Configure logging levels:
            res = consul.execInContainer(
                    "consul", "kv", "put", "config/local/application/logger.org.qubership", "DEBUG");
            LOG.debug("Result for put config/local/application/logger.org.qubership = {}", res.getStdout());
            assertSuccess(res, "Failed to set logger.org.qubership");

            res = consul.execInContainer(
                    "consul", "kv", "put",
                    "config/local/application/logger.org.apache.nifi.processors", "DEBUG");
            LOG.debug("Result for put config/local/application/logger.org.apache.nifi.processors = {}",
                    res.getStdout());
            assertSuccess(res, "Failed to set logger.org.apache.nifi.processors");

            // Configure properties:
            // nifi.web.jetty.threads -- with default value
            res = consul.execInContainer(
                    "consul", "kv", "put", "config/local/application/nifi.web.jetty.threads", "400");
            LOG.debug("Result for put config/local/application/nifi.web.jetty.threads = {}", res.getStdout());
            assertSuccess(res, "Failed to set nifi.web.jetty.threads");

            // nifi.cluster.flow.election.max.candidates -- w/o default value
            res = consul.execInContainer(
                    "consul", "kv", "put",
                    "config/local/application/nifi.cluster.flow.election.max.candidates", "10");
            LOG.debug("Result for put config/local/application/nifi.cluster.flow.election.max.candidates = {}",
                    res.getStdout());
            assertSuccess(res, "Failed to set nifi.cluster.flow.election.max.candidates");

            LOG.info("Consul data populated successfully");
        } catch (IOException | InterruptedException e) {
            if (res != null) {
                LOG.error("Last command stdout = {}", res.getStdout());
                LOG.error("Last command stderr = {}", res.getStderr());
            }
            LOG.error("Failed to fill initial consul data", e);
            throw new RuntimeException("Failed to populate Consul data", e);
        }
    }

    private void assertSuccess(Container.ExecResult res, String errorMessage) {
        if (res == null || res.getStdout() == null || !res.getStdout().contains("Success")) {
            throw new RuntimeException(errorMessage + ": " +
                    (res != null ? "stdout=" + res.getStdout() + ", stderr=" + res.getStderr() : "null result"));
        }
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(consul, new TestInjector.
                AnnotatedAndMatchesType(InjectConsulContainer.class, ConsulContainer.class));
    }
}
