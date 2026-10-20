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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Controller service that starts one Jetty server on one port and routes each incoming request to the queue of the
 * registered processor whose path pattern matches the path of the request.
 *
 * <p>The service answers a request itself when it cannot put the request in the queue of a processor: with 404 when
 * the path does not match the pattern of any processor, with 405 when the matching processor does not allow the
 * method, and with 503 when the queue of the processor is full, when the request has waited in the queue longer than
 * the queue timeout of the processor, when the processor is being unregistered, or when the service is being
 * disabled. When the patterns of several processors match a path, the processor that registered first receives the
 * request.
 *
 * <p>A request waits in the queue until the processor takes it, until its queue timeout passes, until the processor
 * is unregistered, or until the service is disabled. pollRequest never returns an expired request, and the service
 * removes the expired requests of every queue about once a second, so an expired request is answered even while no
 * processor polls its queue.
 */
@Tags({"http", "https", "server", "router", "listen"})
@CapabilityDescription("Starts one HTTP server on one port and routes each incoming request to the processor whose "
        + "path pattern matches the path of the request. This lets several flows share one port.")
public class QubershipHttpRouterService extends AbstractControllerService implements HttpRouterService {

    private static final long MINIMUM_THREADS = 8;
    private static final long EXPIRATION_CHECK_MILLIS = 1000;
    private static final long MAXIMUM_THREADS_LIMIT = 1000;

    /**
     * Listening Port property descriptor.
     */
    public static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
            .name("listening-port")
            .displayName("Listening Port")
            .description("The port the server listens on")
            .required(true)
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .defaultValue("8081")
            .build();

    /**
     * Hostname property descriptor.
     */
    public static final PropertyDescriptor HOSTNAME = new PropertyDescriptor.Builder()
            .name("hostname")
            .displayName("Hostname")
            .description("The hostname to bind to. If not set, the server binds to all hosts")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * SSL Context Service property descriptor.
     */
    public static final PropertyDescriptor SSL_CONTEXT = new PropertyDescriptor.Builder()
            .name("ssl-context-service")
            .displayName("SSL Context Service")
            .description("The SSL Context Service to use to secure the server. If set, the server accepts only "
                    + "HTTPS requests; otherwise it accepts only HTTP requests")
            .required(false)
            .identifiesControllerService(SSLContextProvider.class)
            .build();

    /**
     * HTTP Protocols property descriptor.
     */
    public static final PropertyDescriptor HTTP_PROTOCOL_STRATEGY = new PropertyDescriptor.Builder()
            .name("http-protocols")
            .displayName("HTTP Protocols")
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
            .name("maximum-threads")
            .displayName("Maximum Threads")
            .description("The maximum number of threads the server uses for handling requests")
            .required(true)
            .defaultValue("200")
            .addValidator(StandardValidators.createLongValidator(MINIMUM_THREADS, MAXIMUM_THREADS_LIMIT, true))
            .build();

    /**
     * Request Header Maximum Size property descriptor.
     */
    public static final PropertyDescriptor REQUEST_HEADER_MAX_SIZE = new PropertyDescriptor.Builder()
            .name("request-header-maximum-size")
            .displayName("Request Header Maximum Size")
            .description("The maximum supported size of HTTP headers in requests sent to the server")
            .required(true)
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .defaultValue("8 KB")
            .build();

    /**
     * Idle Timeout property descriptor.
     */
    public static final PropertyDescriptor IDLE_TIMEOUT = new PropertyDescriptor.Builder()
            .name("idle-timeout")
            .displayName("Idle Timeout")
            .description("The maximum time the server waits for data from a client, while reading a request or its "
                    + "body or between requests on a keep-alive connection, before it closes the connection. A request "
                    + "that waits in a queue or for the flow to answer is not limited by this time")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .defaultValue("1 min")
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS = List.of(
            PORT,
            HOSTNAME,
            SSL_CONTEXT,
            HTTP_PROTOCOL_STRATEGY,
            MAXIMUM_THREADS,
            REQUEST_HEADER_MAX_SIZE,
            IDLE_TIMEOUT
    );

    private final List<ProcessorRegistration> registrations = new CopyOnWriteArrayList<>();
    private final Map<String, ProcessorRegistration> registrationsById = new ConcurrentHashMap<>();
    private final Map<String, ProcessorRegistration> registrationsByPattern = new ConcurrentHashMap<>();
    private volatile Server server;
    private volatile ScheduledExecutorService expirationExecutor;
    private volatile boolean disabling;

    /**
     * Returns the properties of the service.
     *
     * @return the property descriptors, in the order the UI lists them
     */
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
    public void onEnabled(final ConfigurationContext context) throws Exception {
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
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "QubershipHttpRouterService-" + getIdentifier() + "-expiration");
            thread.setDaemon(true);
            return thread;
        });
        final long checkMillis = getExpirationCheckMillis();
        executor.scheduleWithFixedDelay(this::answerExpiredRequests, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
        this.expirationExecutor = executor;

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
    public void onDisabled() throws Exception {
        final Server currentServer = this.server;
        if (currentServer == null) {
            return;
        }
        this.server = null;
        disabling = true;
        final ScheduledExecutorService executor = this.expirationExecutor;
        if (executor != null) {
            executor.shutdownNow();
            this.expirationExecutor = null;
        }
        for (final ProcessorRegistration registration : registrations) {
            closeRegistration(registration);
        }
        registrations.clear();
        registrationsById.clear();
        registrationsByPattern.clear();
        stopServer(currentServer);

        for (final Connector connector : currentServer.getConnectors()) {
            getLogger().info("Stopped Connector {}", connector);
        }
    }

    /**
     * Registers the paths and methods a processor handles and creates its request queue.
     *
     * <p>Two path patterns conflict only when their strings are equal. When the patterns of several processors match
     * a path, the processor that registered first receives the request.
     *
     * @param processorId identifier of the processor
     * @param paths regular expression that a request path must match to be routed to the processor
     * @param methods HTTP methods the processor handles, in any case
     * @param queueSize maximum number of requests the queue of the processor holds
     * @param queueTimeout longest time a request waits in the queue of the processor before the service answers it
     *                     with 503
     * @throws IllegalArgumentException if queueSize or queueTimeout is not positive
     * @throws IllegalStateException if the service is not enabled, if the processor is already registered, or if
     *                               another processor registered the same path pattern
     */
    @Override
    public synchronized void registerProcessor(final String processorId, final Pattern paths,
            final Set<String> methods, final int queueSize, final Duration queueTimeout) {
        Objects.requireNonNull(processorId, "processorId");
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(queueTimeout, "queueTimeout");
        if (queueSize <= 0) {
            throw new IllegalArgumentException(
                    "Cannot register processor " + processorId + ": queue size " + queueSize + " is not positive.");
        }
        if (queueTimeout.isNegative() || queueTimeout.isZero()) {
            throw new IllegalArgumentException("Cannot register processor " + processorId + ": queue timeout "
                    + queueTimeout + " is not positive.");
        }
        final Server currentServer = this.server;
        if (currentServer == null) {
            throw new IllegalStateException("Cannot register processor " + processorId
                    + ": the HTTP server is not started. Enable the controller service first.");
        }
        if (registrationsById.containsKey(processorId)) {
            throw new IllegalStateException("Cannot register processor " + processorId
                    + ": it is already registered. Unregister it first.");
        }
        final ProcessorRegistration samePattern = registrationsByPattern.get(paths.pattern());
        if (samePattern != null) {
            throw new IllegalStateException("Cannot register processor " + processorId + ": its path pattern '"
                    + paths.pattern() + "' is already registered by processor " + samePattern.getProcessorId()
                    + ". Use a different path pattern for one of them.");
        }

        final Set<String> upperCaseMethods = methods.stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        final ProcessorRegistration registration =
                new ProcessorRegistration(processorId, paths, upperCaseMethods, queueSize, queueTimeout);
        registrations.add(registration);
        registrationsById.put(processorId, registration);
        registrationsByPattern.put(paths.pattern(), registration);
        getLogger().info("Registered processor {} with path pattern [{}] and methods {}",
                processorId, paths.pattern(), upperCaseMethods);
    }

    /**
     * Removes the registration of a processor and answers every request still waiting in its queue with 503. Does
     * nothing if the processor is not registered.
     *
     * @param processorId identifier the processor was registered with
     */
    @Override
    public synchronized void unregisterProcessor(final String processorId) {
        final ProcessorRegistration registration = registrationsById.remove(processorId);
        if (registration == null) {
            return;
        }
        registrationsByPattern.remove(registration.getPaths().pattern());
        registrations.remove(registration);
        closeRegistration(registration);
        getLogger().info("Unregistered processor {}", processorId);
    }

    /**
     * Returns the next request in the queue of a processor, waiting up to the given time for one to arrive when the
     * queue is empty. An expired request taken from the queue on the way is answered with 503 and not returned. When
     * the calling thread is interrupted while it waits, the method returns null and leaves the interrupt flag of the
     * thread set.
     *
     * @param processorId identifier the processor was registered with
     * @param timeout how long to wait for a request; zero or less returns at once
     * @param unit unit of timeout
     * @return the next request, or null if no request arrives within the timeout or the processor is not registered
     */
    @Override
    public HttpRequestContainer pollRequest(final String processorId, final long timeout, final TimeUnit unit) {
        final ProcessorRegistration registration = registrationsById.get(processorId);
        if (registration == null) {
            return null;
        }
        final List<HttpRequestContainer> expired = new ArrayList<>();
        try {
            return registration.poll(timeout, unit, expired);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            for (final HttpRequestContainer container : expired) {
                sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Request Queue Timeout", container);
            }
        }
    }

    /**
     * Returns the interval at which the service removes and answers the expired requests of every queue.
     *
     * <p>The method is package-private so that a test can turn the periodic check off and observe the expired requests
     * that pollRequest answers.
     *
     * @return the interval, in milliseconds
     */
    long getExpirationCheckMillis() {
        return EXPIRATION_CHECK_MILLIS;
    }

    private void answerExpiredRequests() {
        try {
            final long now = System.nanoTime();
            for (final ProcessorRegistration registration : registrations) {
                for (final HttpRequestContainer container : registration.removeExpired(now)) {
                    sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Request Queue Timeout", container);
                }
            }
        } catch (final RuntimeException e) {
            getLogger().error("Answering expired requests failed", e);
        }
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
        connector.setIdleTimeout(context.getProperty(IDLE_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS));
        final String host = context.getProperty(HOSTNAME).getValue();
        if (host != null && !host.isBlank()) {
            connector.setHost(host);
        }
        return connector;
    }

    /**
     * Stops the server and waits until it has stopped.
     *
     * <p>The method is package-private so that a test can send a request after the registrations are removed and
     * before the server stops accepting requests.
     *
     * @param serverToStop the server to stop
     * @throws Exception if the server fails to stop
     */
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
