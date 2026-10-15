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

package org.qubership.nifi.service.http;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.jetty.configuration.connector.StandardServerConnectorFactory;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.ssl.SSLContextProvider;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Controller service that starts one Jetty server on one port and routes each incoming request to the queue of the
 * registered processor whose path pattern matches the path of the request.
 *
 * <p>The service answers a request itself when it cannot put the request in the queue of a processor: with 404 when
 * the path does not match the pattern of any processor, with 405 when the matching processor does not allow the
 * method, and with 503 when the queue of the processor is full, the processor is being unregistered, or the service
 * is being disabled. When the patterns of several processors match a path, the processor that registered first
 * receives the request.
 *
 * <p>A request waits in the queue with no time limit until the processor takes it, the processor is unregistered, or
 * the service is disabled.
 */
@Tags({"http", "https", "server", "router", "listen"})
@CapabilityDescription("Starts one HTTP server on one port and routes each incoming request to the processor whose "
        + "path pattern matches the path of the request. This lets several flows share one port.")
public class QubershipHttpRouterService extends AbstractControllerService implements HttpRouterService {

    private static final long MINIMUM_THREADS = 8;
    private static final long MAXIMUM_THREADS_LIMIT = 1000;

    /**
     * Listening Port property descriptor.
     */
    public static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
            .name("Listening Port")
            .description("The port the server listens on")
            .required(true)
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .defaultValue("80")
            .build();

    /**
     * Hostname property descriptor.
     */
    public static final PropertyDescriptor HOSTNAME = new PropertyDescriptor.Builder()
            .name("Hostname")
            .description("The hostname to bind to. If not set, the server binds to all hosts")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * SSL Context Service property descriptor.
     */
    public static final PropertyDescriptor SSL_CONTEXT = new PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("The SSL Context Service to use to secure the server. If set, the server accepts only "
                    + "HTTPS requests; otherwise it accepts only HTTP requests")
            .required(false)
            .identifiesControllerService(SSLContextProvider.class)
            .build();

    /**
     * HTTP Protocols property descriptor.
     */
    public static final PropertyDescriptor HTTP_PROTOCOL_STRATEGY = new PropertyDescriptor.Builder()
            .name("HTTP Protocols")
            .description("The HTTP protocols the server supports for Application Layer Protocol Negotiation with TLS")
            .required(true)
            .allowableValues(HttpProtocolStrategy.class)
            .defaultValue(HttpProtocolStrategy.HTTP_1_1)
            .dependsOn(SSL_CONTEXT)
            .build();

    /**
     * Maximum Threads property descriptor.
     */
    public static final PropertyDescriptor MAXIMUM_THREADS = new PropertyDescriptor.Builder()
            .name("Maximum Threads")
            .description("The maximum number of threads the server uses for handling requests")
            .required(true)
            .defaultValue("200")
            .addValidator(StandardValidators.createLongValidator(MINIMUM_THREADS, MAXIMUM_THREADS_LIMIT, true))
            .build();

    /**
     * Request Header Maximum Size property descriptor.
     */
    public static final PropertyDescriptor REQUEST_HEADER_MAX_SIZE = new PropertyDescriptor.Builder()
            .name("Request Header Maximum Size")
            .description("The maximum supported size of HTTP headers in requests sent to the server")
            .required(true)
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .defaultValue("8 KB")
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS = List.of(
            PORT,
            HOSTNAME,
            SSL_CONTEXT,
            HTTP_PROTOCOL_STRATEGY,
            MAXIMUM_THREADS,
            REQUEST_HEADER_MAX_SIZE
    );

    private final List<ProcessorRegistration> registrations = new CopyOnWriteArrayList<>();
    private volatile Server server;
    private volatile boolean disabling;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    /**
     * Starts the server with the configured port, hostname, thread pool, and TLS settings.
     *
     * @param context configuration of the service
     * @throws Exception if the server fails to start, for example when the port is in use
     */
    @OnEnabled
    public synchronized void onEnabled(final ConfigurationContext context) throws Exception {
        disabling = false;
        final Server newServer = createServer(context);
        newServer.addConnector(createConnector(newServer, context));

        final ServletContextHandler handler = new ServletContextHandler();
        handler.addServlet(new RouterServlet(), "/");
        newServer.setHandler(handler);

        try {
            newServer.start();
        } catch (final Exception e) {
            stopServer(newServer);
            throw e;
        }
        this.server = newServer;

        for (final Connector connector : newServer.getConnectors()) {
            getLogger().info("Started Connector {}", connector);
        }
    }

    /**
     * Answers every request still waiting in a queue with 503 Service Unavailable, removes all registrations, and
     * stops the server. Until the server stops, every new request is answered with 503 as well.
     *
     * @throws Exception if the server fails to stop
     */
    @OnDisabled
    public synchronized void onDisabled() throws Exception {
        final Server currentServer = this.server;
        if (currentServer == null) {
            return;
        }
        this.server = null;
        disabling = true;
        for (final ProcessorRegistration registration : registrations) {
            closeRegistration(registration);
        }
        registrations.clear();
        stopServer(currentServer);

        for (final Connector connector : currentServer.getConnectors()) {
            getLogger().info("Stopped Connector {}", connector);
        }
    }

    @Override
    public synchronized void registerProcessor(final String processorId, final Pattern paths,
            final Set<String> methods, final int queueSize, final long requestTimeoutMillis) {
        Objects.requireNonNull(processorId, "processorId");
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(methods, "methods");
        if (queueSize <= 0) {
            throw new IllegalArgumentException(
                    "Cannot register processor " + processorId + ": queue size " + queueSize + " is not positive.");
        }
        final Server currentServer = this.server;
        if (currentServer == null) {
            throw new IllegalStateException("Cannot register processor " + processorId
                    + ": the HTTP server is not started. Enable the controller service first.");
        }
        for (final ProcessorRegistration registration : registrations) {
            if (registration.getProcessorId().equals(processorId)) {
                throw new IllegalStateException("Cannot register processor " + processorId
                        + ": it is already registered. Unregister it first.");
            }
            if (registration.getPaths().pattern().equals(paths.pattern())) {
                throw new IllegalStateException("Cannot register processor " + processorId + ": its path pattern '"
                        + paths.pattern() + "' is already registered by processor " + registration.getProcessorId()
                        + ". Use a different path pattern for one of them.");
            }
        }

        final Set<String> upperCaseMethods = methods.stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        registrations.add(new ProcessorRegistration(processorId, paths, upperCaseMethods, queueSize));

        for (final Connector connector : currentServer.getConnectors()) {
            if (connector instanceof final ServerConnector serverConnector) {
                serverConnector.setIdleTimeout(Math.max(serverConnector.getIdleTimeout(), requestTimeoutMillis));
            }
        }
        getLogger().info("Registered processor {} with path pattern [{}] and methods {}",
                processorId, paths.pattern(), upperCaseMethods);
    }

    @Override
    public synchronized void unregisterProcessor(final String processorId) {
        final ProcessorRegistration registration = findRegistration(processorId);
        if (registration == null) {
            return;
        }
        registrations.remove(registration);
        closeRegistration(registration);
        getLogger().info("Unregistered processor {}", processorId);
    }

    @Override
    public HttpRequestContainer pollRequest(final String processorId) {
        final ProcessorRegistration registration = findRegistration(processorId);
        return registration == null ? null : registration.poll();
    }

    private ProcessorRegistration findRegistration(final String processorId) {
        for (final ProcessorRegistration registration : registrations) {
            if (registration.getProcessorId().equals(processorId)) {
                return registration;
            }
        }
        return null;
    }

    private void closeRegistration(final ProcessorRegistration registration) {
        final List<HttpRequestContainer> pending = registration.close();
        if (!pending.isEmpty()) {
            getLogger().warn("Pending Requests Queued [{}] for processor {}", pending.size(),
                    registration.getProcessorId());
        }
        for (final HttpRequestContainer container : pending) {
            sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Stopping Processor", container);
        }
    }

    private Server createServer(final ConfigurationContext context) {
        final int maximumThreads = context.getProperty(MAXIMUM_THREADS).asInteger();
        final QueuedThreadPool threadPool = new QueuedThreadPool(maximumThreads);
        threadPool.setName(String.format("%s[id=%s] Server", getClass().getSimpleName(), getIdentifier()));
        return new Server(threadPool);
    }

    private ServerConnector createConnector(final Server newServer, final ConfigurationContext context) {
        final int port = context.getProperty(PORT).asInteger();
        final int requestHeaderSize = context.getProperty(REQUEST_HEADER_MAX_SIZE).asDataSize(DataUnit.B).intValue();
        final SSLContextProvider sslContextProvider =
                context.getProperty(SSL_CONTEXT).asControllerService(SSLContextProvider.class);
        final SSLContext sslContext = sslContextProvider == null ? null : sslContextProvider.createContext();
        final HttpProtocolStrategy httpProtocolStrategy = sslContext == null
                ? HttpProtocolStrategy.HTTP_1_1
                : context.getProperty(HTTP_PROTOCOL_STRATEGY).asAllowableValue(HttpProtocolStrategy.class);

        final StandardServerConnectorFactory connectorFactory = new StandardServerConnectorFactory(newServer, port);
        connectorFactory.setRequestHeaderSize(requestHeaderSize);
        connectorFactory.setSslContext(sslContext);
        connectorFactory.setApplicationLayerProtocols(httpProtocolStrategy.getApplicationLayerProtocols());

        final ServerConnector connector = connectorFactory.getServerConnector();
        final String host = context.getProperty(HOSTNAME).getValue();
        if (host != null && !host.isBlank()) {
            connector.setHost(host);
        }
        return connector;
    }

    // Package-private so that a test can send a request after the registrations are removed and before the server
    // stops accepting requests.
    void stopServer(final Server serverToStop) throws Exception {
        serverToStop.stop();
        serverToStop.destroy();
        serverToStop.join();
    }

    private void sendError(final int statusCode, final String message, final HttpRequestContainer container) {
        sendError(statusCode, message, container.request(), container.response());
        try {
            container.context().complete();
        } catch (final RuntimeException e) {
            final HttpServletRequest request = container.request();
            getLogger().error("Complete Request Failed: Method [{}] URI [{}] Address [{}]",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), e);
        }
    }

    private void sendError(final int statusCode, final String message, final HttpServletRequest request,
            final HttpServletResponse response) {
        final String method = request.getMethod();
        final String uri = request.getRequestURI();
        final String remoteAddr = request.getRemoteAddr();
        try {
            response.sendError(statusCode, message);
            getLogger().warn("Send Error Completed: HTTP {} [{}] Method [{}] URI [{}] Address [{}]",
                    statusCode, message, method, uri, remoteAddr);
        } catch (final Exception e) {
            getLogger().error("Send Error Failed: HTTP {} [{}] Method [{}] URI [{}] Address [{}]",
                    statusCode, message, method, uri, remoteAddr, e);
        }
    }

    /**
     * Servlet that receives every request of the server and puts it in the queue of the matching processor.
     */
    private final class RouterServlet extends HttpServlet {

        @Override
        protected void service(final HttpServletRequest request, final HttpServletResponse response) {
            final String path = URI.create(request.getRequestURI()).getPath();
            final ProcessorRegistration registration = findRegistrationByPath(path);
            // Read after the lookup: onDisabled sets the flag before it removes the registrations, so a lookup that
            // finds no registration because of the removal sees the flag.
            if (disabling) {
                sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Disabling Service", request, response);
                return;
            }
            if (registration == null) {
                sendError(HttpServletResponse.SC_NOT_FOUND, "No Processor Registered for Path", request, response);
                return;
            }
            final String method = request.getMethod().toUpperCase(Locale.ROOT);
            if (!registration.allowsMethod(method)) {
                sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed", request, response);
                return;
            }

            final AsyncContext async = request.startAsync();
            // No time limit applies while the request waits in the queue. Once the processor takes it, the HTTP
            // Context Map of the processor expires it.
            async.setTimeout(0);
            final HttpRequestContainer container = new HttpRequestContainer(request, response, async);
            switch (registration.offer(container)) {
                case QUEUED -> getLogger().debug("Request Queued: Method [{}] URI [{}] Address [{}] Processor [{}]",
                        method, request.getRequestURI(), request.getRemoteAddr(), registration.getProcessorId());
                case QUEUE_FULL ->
                        sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Request Queue Full", container);
                case CLOSED ->
                        sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Stopping Processor", container);
                default -> throw new IllegalStateException("Unexpected offer result");
            }
        }

        private ProcessorRegistration findRegistrationByPath(final String path) {
            for (final ProcessorRegistration registration : registrations) {
                if (registration.matchesPath(path)) {
                    return registration;
                }
            }
            return null;
        }
    }
}
