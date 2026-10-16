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

import org.apache.nifi.util.NoOpProcessor;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A request reaches the queue of the registered processor whose path pattern and methods match it, and the service
 * answers every other request itself with 404, 405, or 503.
 *
 * <p>Each test starts the service on a free local port and sends real HTTP requests to it. A request that the
 * service puts in a queue stays open, so a test that needs a request waiting in a queue fills a queue of size 1 and
 * waits for the 503 that the next request receives.
 */
class QubershipHttpRouterServiceTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final long REQUEST_TIMEOUT_MILLIS = 30000;
    private static final int QUEUE_SIZE = 5;
    private static final long POLL_INTERVAL_MILLIS = 10;
    private static final int IDLE_CHECK_MILLIS = 32000;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();
    private TestRunner runner;
    private QubershipHttpRouterService service;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        service = new QubershipHttpRouterService();
        runner = TestRunners.newTestRunner(NoOpProcessor.class);
        runner.addControllerService("router", service);
        runner.setProperty(service, QubershipHttpRouterService.PORT, Integer.toString(port));
    }

    @AfterEach
    void tearDown() {
        if (runner.isControllerServiceEnabled(service)) {
            runner.disableControllerService(service);
        }
        client.close();
    }

    @Test
    void aRequestOnARegisteredPathIsQueuedAndAnsweredThroughItsContainer() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders(/.*)?"), Set.of("POST"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        final CompletableFuture<HttpResponse<String>> pending = send("POST", "/orders/17", "order body");
        final HttpRequestContainer container = awaitRequest("orders");
        final String queuedMethod = container.request().getMethod();
        final String queuedUri = container.request().getRequestURI();
        final String queuedBody = new String(container.request().getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        container.response().setStatus(201);
        container.response().getWriter().write("created");
        container.context().complete();
        final HttpResponse<String> response = await(pending, "the response to POST /orders/17");

        assertAll(
                () -> assertEquals("POST", queuedMethod, "method of the queued request"),
                () -> assertEquals("/orders/17", queuedUri, "URI of the queued request"),
                () -> assertEquals("order body", queuedBody, "body read from the queued request"),
                () -> assertEquals(201, response.statusCode(), "status the client received"),
                () -> assertEquals("created", response.body(), "body the client received")
        );
    }

    @Test
    void aRequestIsQueuedOnlyForTheProcessorWhosePatternMatchesItsPath() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);
        service.registerProcessor("users", Pattern.compile("/users"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/users");
        final HttpRequestContainer container = awaitRequest("users");

        assertAll(
                () -> assertEquals("/users", container.request().getRequestURI(), "URI in the queue of users"),
                () -> assertNull(service.pollRequest("orders"), "pollRequest(orders) after GET /users")
        );
        answer(container, pending);
    }

    @Test
    void theQueryStringIsNotPartOfThePathThatIsMatched() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders?id=17");
        final HttpRequestContainer container = awaitRequest("orders");

        assertEquals("id=17", container.request().getQueryString(), "query string of the queued request");
        answer(container, pending);
    }

    @Test
    void whenTwoPatternsMatchAPathTheProcessorRegisteredFirstReceivesTheRequest() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("any-order", Pattern.compile("/orders/.*"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);
        service.registerProcessor("numbered-order", Pattern.compile("/orders/\\d+"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders/17");
        final HttpRequestContainer container = awaitRequest("any-order");

        assertNull(service.pollRequest("numbered-order"), "pollRequest(numbered-order) after GET /orders/17");
        answer(container, pending);
    }

    @Test
    void aMethodRegisteredInLowerCaseIsAllowed() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("get"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        final HttpRequestContainer container = awaitRequest("orders");

        assertEquals("GET", container.request().getMethod(), "method of the queued request");
        answer(container, pending);
    }

    @Test
    void aPathThatNoProcessorPatternMatchesIsAnsweredWith404() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        final HttpResponse<String> response = sendAndWait("GET", "/orders/17");

        assertEquals(404, response.statusCode(), "GET /orders/17 with pattern /orders");
    }

    @Test
    void aMethodTheMatchingProcessorDoesNotAllowIsAnsweredWith405AndNotQueued() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        final HttpResponse<String> response = sendAndWait("DELETE", "/orders");

        assertAll(
                () -> assertEquals(405, response.statusCode(), "DELETE /orders with methods [GET]"),
                () -> assertNull(service.pollRequest("orders"), "pollRequest(orders) after the 405")
        );
    }

    @Test
    void aRequestForAFullQueueIsAnsweredWith503AndTheQueuedRequestStays() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders/.*"), Set.of("GET"), 1,
                REQUEST_TIMEOUT_MILLIS);

        final CompletableFuture<HttpResponse<String>> first = send("GET", "/orders/1");
        final CompletableFuture<HttpResponse<String>> second = send("GET", "/orders/2");
        final HttpResponse<?> refused = (HttpResponse<?>) await(CompletableFuture.anyOf(first, second),
                "one of two requests to be refused by a queue of size 1");
        final CompletableFuture<HttpResponse<String>> queued = first.isDone() ? second : first;
        final HttpRequestContainer container = awaitRequest("orders");

        assertAll(
                () -> assertEquals(503, refused.statusCode(), "status of the request that found the queue full"),
                () -> assertNull(service.pollRequest("orders"), "pollRequest(orders) after the only queued request")
        );
        answer(container, queued);
    }

    @Test
    void pollRequestReturnsNullForAnEmptyQueue() {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        assertNull(service.pollRequest("orders"), "pollRequest(orders) with no request sent");
    }

    @Test
    void pollRequestReturnsNullForAProcessorThatIsNotRegistered() {
        runner.enableControllerService(service);

        assertNull(service.pollRequest("unknown"), "pollRequest(unknown)");
    }

    @Test
    void unregisterProcessorAnswersTheQueuedRequestWith503() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders/.*"), Set.of("GET"), 1,
                REQUEST_TIMEOUT_MILLIS);
        final CompletableFuture<HttpResponse<String>> queued = sendUntilOneRequestIsQueued("/orders/1", "/orders/2");

        service.unregisterProcessor("orders");

        assertEquals(503, await(queued, "the response to the queued request").statusCode(),
                "status of the request that was queued when the processor unregistered");
    }

    @Test
    void afterUnregisterProcessorItsPathIsAnsweredWith404AndItsQueueIsGone() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        service.unregisterProcessor("orders");
        final HttpResponse<String> response = sendAndWait("GET", "/orders");

        assertAll(
                () -> assertEquals(404, response.statusCode(), "GET /orders after unregisterProcessor(orders)"),
                () -> assertNull(service.pollRequest("orders"), "pollRequest(orders) after unregisterProcessor")
        );
    }

    @Test
    void unregisterProcessorAcceptsAProcessorThatIsNotRegistered() {
        runner.enableControllerService(service);

        assertDoesNotThrow(() -> service.unregisterProcessor("unknown"), "unregisterProcessor(unknown)");
    }

    @Test
    void aProcessorCanRegisterAgainAfterItUnregistered() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);
        service.unregisterProcessor("orders");

        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);
        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        final HttpRequestContainer container = awaitRequest("orders");

        assertEquals("/orders", container.request().getRequestURI(), "URI queued after the second registration");
        answer(container, pending);
    }

    @Test
    void disablingTheServiceAnswersTheQueuedRequestWith503AndReleasesThePort() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders/.*"), Set.of("GET"), 1,
                REQUEST_TIMEOUT_MILLIS);
        final CompletableFuture<HttpResponse<String>> queued = sendUntilOneRequestIsQueued("/orders/1", "/orders/2");

        runner.disableControllerService(service);

        assertAll(
                () -> assertEquals(503, await(queued, "the response to the queued request").statusCode(),
                        "status of the request that was queued when the service was disabled"),
                () -> assertDoesNotThrow(() -> new ServerSocket(port).close(), "binding the port after disabling")
        );
    }

    /**
     * The test sends its request from the server stop: by then the service has removed the registrations, and the
     * server still accepts requests.
     */
    @Test
    void aRequestThatArrivesWhileTheServiceIsBeingDisabledIsAnsweredWith503() throws Exception {
        final List<Integer> statusesDuringStop = new CopyOnWriteArrayList<>();
        final QubershipHttpRouterService stoppingService = new QubershipHttpRouterService() {
            @Override
            void stopServer(final Server serverToStop) throws Exception {
                statusesDuringStop.add(sendAndWait("GET", "/orders").statusCode());
                super.stopServer(serverToStop);
            }
        };
        runner.addControllerService("stopping-router", stoppingService);
        runner.setProperty(stoppingService, QubershipHttpRouterService.PORT, Integer.toString(port));
        runner.enableControllerService(stoppingService);
        stoppingService.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        runner.disableControllerService(stoppingService);

        assertEquals(List.of(503), statusesDuringStop,
                "statuses of GET /orders sent after the registrations were removed and before the server stopped");
    }

    /**
     * Jetty closes a keep-alive connection after 30 s of idleness by default, so the test keeps an answered
     * connection idle for 32 s and takes that long to run.
     */
    @Test
    void anIdleConnectionStaysOpenForTheRequestTimeoutOfARegisteredProcessor() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, 60000);

        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write("GET /orders HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            final HttpRequestContainer container = awaitRequest("orders");
            container.response().setStatus(200);
            container.context().complete();
            final InputStream input = socket.getInputStream();
            readResponseHead(input);
            socket.setSoTimeout(IDLE_CHECK_MILLIS);

            assertThrows(SocketTimeoutException.class, input::read,
                    "read on the connection idle for " + IDLE_CHECK_MILLIS + " ms after the response, with a "
                            + "request timeout of 60000 ms; returning -1 means the server closed the connection");
        }
    }

    @Test
    void registerProcessorIsRefusedWhileTheServiceIsNotEnabled() {
        assertThrows(IllegalStateException.class,
                () -> service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                        REQUEST_TIMEOUT_MILLIS));
    }

    @Test
    void registerProcessorIsRefusedForAProcessorThatIsAlreadyRegistered() {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        assertThrows(IllegalStateException.class,
                () -> service.registerProcessor("orders", Pattern.compile("/users"), Set.of("GET"), QUEUE_SIZE,
                        REQUEST_TIMEOUT_MILLIS));
    }

    @Test
    void registerProcessorIsRefusedForAPathPatternAnotherProcessorRegistered() {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                REQUEST_TIMEOUT_MILLIS);

        assertThrows(IllegalStateException.class,
                () -> service.registerProcessor("orders-copy", Pattern.compile("/orders"), Set.of("POST"),
                        QUEUE_SIZE, REQUEST_TIMEOUT_MILLIS));
    }

    @Test
    void registerProcessorIsRefusedForAQueueSizeOfZero() {
        runner.enableControllerService(service);

        assertThrows(IllegalArgumentException.class,
                () -> service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), 0,
                        REQUEST_TIMEOUT_MILLIS));
    }

    @Test
    void theDefaultConfigurationIsValid() {
        runner.assertValid(service);
    }

    @Test
    void maximumThreadsOf8IsValid() {
        runner.setProperty(service, QubershipHttpRouterService.MAXIMUM_THREADS, "8");

        runner.assertValid(service);
    }

    @Test
    void maximumThreadsOf7IsInvalid() {
        runner.setProperty(service, QubershipHttpRouterService.MAXIMUM_THREADS, "7");

        runner.assertNotValid(service);
    }

    @Test
    void maximumThreadsOf1000IsValid() {
        runner.setProperty(service, QubershipHttpRouterService.MAXIMUM_THREADS, "1000");

        runner.assertValid(service);
    }

    @Test
    void maximumThreadsOf1001IsInvalid() {
        runner.setProperty(service, QubershipHttpRouterService.MAXIMUM_THREADS, "1001");

        runner.assertNotValid(service);
    }

    @Test
    void aListeningPortAbove65535IsInvalid() {
        runner.setProperty(service, QubershipHttpRouterService.PORT, "65536");

        runner.assertNotValid(service);
    }

    @Test
    void aRequestHeaderMaximumSizeWithoutAUnitIsInvalid() {
        runner.setProperty(service, QubershipHttpRouterService.REQUEST_HEADER_MAX_SIZE, "8");

        runner.assertNotValid(service);
    }

    private CompletableFuture<HttpResponse<String>> send(final String method, final String path) {
        return send(method, path, "");
    }

    private CompletableFuture<HttpResponse<String>> send(final String method, final String path,
            final String body) {
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendAndWait(final String method, final String path) throws Exception {
        return await(send(method, path), "the response to " + method + " " + path);
    }

    /**
     * Sends two requests to a processor whose queue holds one request and waits until one of them is refused.
     *
     * @param firstPath path of one request
     * @param secondPath path of the other request
     * @return the request that is waiting in the queue
     */
    private CompletableFuture<HttpResponse<String>> sendUntilOneRequestIsQueued(final String firstPath,
            final String secondPath) throws Exception {
        final CompletableFuture<HttpResponse<String>> first = send("GET", firstPath);
        final CompletableFuture<HttpResponse<String>> second = send("GET", secondPath);
        await(CompletableFuture.anyOf(first, second), "one of two requests to be refused by a queue of size 1");
        return first.isDone() ? second : first;
    }

    private HttpRequestContainer awaitRequest(final String processorId) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            final HttpRequestContainer container = service.pollRequest(processorId);
            if (container != null) {
                return container;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return fail("Timed out after " + TIMEOUT_SECONDS + " s waiting for a request in the queue of processor "
                + processorId + "; pollRequest returned null every time");
    }

    private void answer(final HttpRequestContainer container,
            final CompletableFuture<HttpResponse<String>> pending) throws Exception {
        container.response().setStatus(200);
        container.context().complete();
        await(pending, "the response to the answered request");
    }

    private static void readResponseHead(final InputStream input) throws IOException {
        final String headEnd = "\r\n\r\n";
        final StringBuilder head = new StringBuilder();
        while (head.indexOf(headEnd) == -1) {
            final int next = input.read();
            if (next == -1) {
                fail("The server closed the connection before the end of the response head; read so far: " + head);
            }
            head.append((char) next);
        }
    }

    private static <T> T await(final CompletableFuture<T> future, final String awaited) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            return fail("Timed out after " + TIMEOUT_SECONDS + " s waiting for " + awaited
                    + "; the request is still open", e);
        }
    }
}
