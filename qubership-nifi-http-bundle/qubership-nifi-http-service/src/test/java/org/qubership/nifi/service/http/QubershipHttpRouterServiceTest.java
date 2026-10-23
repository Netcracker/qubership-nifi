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
import org.awaitility.Awaitility;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    private static final int QUEUE_SIZE = 5;
    private static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHORT_QUEUE_TIMEOUT = Duration.ofMillis(200);
    private static final long LONGER_THAN_EXPIRATION_CHECK_MILLIS = 1500;
    private static final long LONGER_THAN_IDLE_TIMEOUT_MILLIS = 2000;
    private static final long POLL_INTERVAL_MILLIS = 10;
    private static final long POLL_TIMEOUT_MILLIS = 200;
    private static final int IDLE_CHECK_MILLIS = 5000;
    private static final String LOOPBACK = "127.0.0.1";
    private static final String OTHER_LOOPBACK = "127.0.0.2";
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
        service.registerProcessor("orders", Pattern.compile("/orders(/.*)?"), Set.of("POST"),
                QUEUE_SIZE, QUEUE_TIMEOUT);

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
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        service.registerProcessor("users", Pattern.compile("/users"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/users");
        final HttpRequestContainer container = awaitRequest("users");

        assertAll(
                () -> assertEquals("/users", container.request().getRequestURI(), "URI in the queue of users"),
                () -> assertNull(pollNow("orders"), "pollRequest(orders) after GET /users")
        );
        answer(container, pending);
    }

    @Test
    void theQueryStringIsNotPartOfThePathThatIsMatched() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders?id=17");
        final HttpRequestContainer container = awaitRequest("orders");

        assertEquals("id=17", container.request().getQueryString(), "query string of the queued request");
        answer(container, pending);
    }

    @Test
    void whenTwoPatternsMatchAPathTheProcessorRegisteredFirstReceivesTheRequest() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("any-order", Pattern.compile("/orders/.*"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        service.registerProcessor("numbered-order", Pattern.compile("/orders/\\d+"), Set.of("GET"),
                QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders/17");
        final HttpRequestContainer container = awaitRequest("any-order");

        assertNull(pollNow("numbered-order"), "pollRequest(numbered-order) after GET /orders/17");
        answer(container, pending);
    }

    /**
     * The request is written to a socket, so that no client resolves the dot segments before the server sees them.
     */
    @Test
    void aPathWithDotSegmentsIsRoutedByThePathItResolvesTo() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("public", Pattern.compile("/public/.*"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        service.registerProcessor("admin", Pattern.compile("/admin"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        try (Socket socket = sendRawGet("/public/../admin")) {
            final HttpRequestContainer container = awaitRequest("admin");

            assertNull(pollNow("public"), "pollRequest(public) after GET /public/../admin");
            container.response().setStatus(200);
            container.context().complete();
            readResponseHead(socket.getInputStream());
        }
    }

    @Test
    void aPercentEncodedPathIsMatchedInItsDecodedForm() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders/\\d+"), Set.of("GET"), QUEUE_SIZE,
                QUEUE_TIMEOUT);

        try (Socket socket = sendRawGet("/orders/%31")) {
            final HttpRequestContainer container = awaitRequest("orders");

            assertEquals("/orders/%31", container.request().getRequestURI(), "URI of the queued request");
            container.response().setStatus(200);
            container.context().complete();
            readResponseHead(socket.getInputStream());
        }
    }

    @Test
    void aMethodRegisteredInLowerCaseIsAllowed() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("get"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        final HttpRequestContainer container = awaitRequest("orders");

        assertEquals("GET", container.request().getMethod(), "method of the queued request");
        answer(container, pending);
    }

    @Test
    void aPathThatNoProcessorPatternMatchesIsAnsweredWith404() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final HttpResponse<String> response = sendAndWait("GET", "/orders/17");

        assertEquals(404, response.statusCode(), "GET /orders/17 with pattern /orders");
    }

    @Test
    void aMethodTheMatchingProcessorDoesNotAllowIsAnsweredWith405AndNotQueued() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final HttpResponse<String> response = sendAndWait("DELETE", "/orders");

        assertAll(
                () -> assertEquals(405, response.statusCode(), "DELETE /orders with methods [GET]"),
                () -> assertEquals(Optional.of("GET"), response.headers().firstValue("Allow"), "Allow of the 405"),
                () -> assertNull(pollNow("orders"), "pollRequest(orders) after the 405")
        );
    }

    /**
     * RFC 9110 section 10.2.1: an empty Allow value means the resource allows no methods.
     */
    @Test
    void aPathWhoseOnlyMatchingProcessorAllowsNoMethodIsAnsweredWith405AndAnEmptyAllow() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of(), QUEUE_SIZE, QUEUE_TIMEOUT);

        final HttpResponse<String> response = sendAndWait("GET", "/orders");

        assertAll(
                () -> assertEquals(405, response.statusCode(), "GET /orders with methods []"),
                () -> assertEquals(Optional.of(""), response.headers().firstValue("Allow"), "Allow of the 405")
        );
    }

    @Test
    void aMethodThatOnlyALaterMatchingProcessorAllowsIsRoutedToThatProcessor() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("read-orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        service.registerProcessor("write-orders", Pattern.compile("/orders/?"), Set.of("POST"),
                QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = send("POST", "/orders");
        final HttpRequestContainer container = awaitRequest("write-orders");

        assertNull(pollNow("read-orders"), "pollRequest(read-orders) after POST /orders");
        answer(container, pending);
    }

    @Test
    void the405ForAMethodNoMatchingProcessorAllowsListsTheMethodsOfEveryMatchingProcessor() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("read-orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        service.registerProcessor("write-orders", Pattern.compile("/orders/?"), Set.of("POST"),
                QUEUE_SIZE, QUEUE_TIMEOUT);
        service.registerProcessor("users", Pattern.compile("/users"), Set.of("PUT"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final HttpResponse<String> response = sendAndWait("DELETE", "/orders");

        assertAll(
                () -> assertEquals(405, response.statusCode(), "DELETE /orders with methods [GET] and [POST]"),
                () -> assertEquals(Optional.of("GET, POST"), response.headers().firstValue("Allow"),
                        "Allow of the 405, without PUT of /users")
        );
    }

    @Test
    void aRequestForAFullQueueIsAnsweredWith503AndTheQueuedRequestStays() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders/.*"), Set.of("GET"), 1, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> first = send("GET", "/orders/1");
        final CompletableFuture<HttpResponse<String>> second = send("GET", "/orders/2");
        final HttpResponse<?> refused = (HttpResponse<?>) await(CompletableFuture.anyOf(first, second),
                "one of two requests to be refused by a queue of size 1");
        final CompletableFuture<HttpResponse<String>> queued = first.isDone() ? second : first;
        final HttpRequestContainer container = awaitRequest("orders");

        assertAll(
                () -> assertEquals(503, refused.statusCode(), "status of the request that found the queue full"),
                () -> assertNull(pollNow("orders"), "pollRequest(orders) after the only queued request")
        );
        answer(container, queued);
    }

    @Test
    void pollRequestReturnsNullForAnEmptyQueue() {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        assertNull(pollNow("orders"), "pollRequest(orders) with no request sent");
    }

    @Test
    void pollRequestOnAnEmptyQueueReturnsNullAfterTheTimeout() {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final long start = System.nanoTime();
        final HttpRequestContainer container = service.pollRequest("orders", POLL_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS);
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertAll(
                () -> assertNull(container, "pollRequest(orders) with no request sent"),
                () -> assertTrue(elapsedMillis >= POLL_TIMEOUT_MILLIS, "pollRequest(orders, " + POLL_TIMEOUT_MILLIS
                        + " ms) returned after " + elapsedMillis + " ms")
        );
    }

    /**
     * The request is sent once the polling thread waits, so a poll that does not wait returns null before the
     * request arrives.
     */
    @Test
    void pollRequestReturnsARequestThatArrivesWhileItWaits() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpRequestContainer> polled = new CompletableFuture<>();
        final Thread poller = new Thread(
                () -> polled.complete(service.pollRequest("orders", TIMEOUT_SECONDS, TimeUnit.SECONDS)));
        poller.start();
        Awaitility.await("pollRequest(orders) waiting for a request")
                .atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> poller.getState() == Thread.State.TIMED_WAITING);
        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        final HttpRequestContainer container = await(polled, "pollRequest(orders) waiting for a request");

        assertNotNull(container, "request returned by pollRequest(orders) that was waiting when GET /orders arrived");
        assertEquals("/orders", container.request().getRequestURI(), "URI of the request returned by pollRequest");
        answer(container, pending);
    }

    /**
     * Nothing polls the queue, so only the periodic check of the service can answer the request.
     */
    @Test
    void aRequestThatWaitsLongerThanTheQueueTimeoutIsAnsweredWith503AndFreesItsPlace() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders/.*"), Set.of("GET"), 1, SHORT_QUEUE_TIMEOUT);

        final HttpResponse<String> expired = await(send("GET", "/orders/1"), "the response to the request left in "
                + "a queue with a timeout of " + SHORT_QUEUE_TIMEOUT.toMillis() + " ms");
        final CompletableFuture<HttpResponse<String>> next = send("GET", "/orders/2");
        final HttpRequestContainer container = awaitRequest("orders");

        assertAll(
                () -> assertEquals(503, expired.statusCode(), "status of the request left in the queue"),
                () -> assertEquals("/orders/2", container.request().getRequestURI(),
                        "URI queued after the expired request left the queue of size 1")
        );
        answer(container, next);
    }

    @Test
    void theQueueTimeoutDoesNotLimitARequestTheProcessorHasTaken() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                SHORT_QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        final HttpRequestContainer container = awaitRequest("orders");
        assertStillOpen(pending, Duration.ofMillis(LONGER_THAN_EXPIRATION_CHECK_MILLIS),
                "the taken request, with a queue timeout of " + SHORT_QUEUE_TIMEOUT.toMillis() + " ms");
        container.response().setStatus(200);
        container.context().complete();

        assertEquals(200, await(pending, "the response to the taken request").statusCode(),
                "status of a request answered " + LONGER_THAN_EXPIRATION_CHECK_MILLIS + " ms after it was taken, "
                        + "with a queue timeout of " + SHORT_QUEUE_TIMEOUT.toMillis() + " ms");
    }

    /**
     * The periodic check is turned off, so only pollRequest can answer the expired request.
     */
    @Test
    void pollRequestAnswersAnExpiredRequestWith503InsteadOfReturningIt() throws Exception {
        final QubershipHttpRouterService uncheckedService = new QubershipHttpRouterService() {
            @Override
            long getExpirationCheckMillis() {
                return TimeUnit.HOURS.toMillis(1);
            }
        };
        runner.addControllerService("unchecked-router", uncheckedService);
        runner.setProperty(uncheckedService, QubershipHttpRouterService.PORT, Integer.toString(port));
        runner.enableControllerService(uncheckedService);
        try {
            uncheckedService.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE,
                    SHORT_QUEUE_TIMEOUT);
            final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
            assertStillOpen(pending, SHORT_QUEUE_TIMEOUT.multipliedBy(2),
                    "the request in the queue, with the periodic check turned off");

            final HttpRequestContainer polled = uncheckedService.pollRequest("orders", 0, TimeUnit.MILLISECONDS);
            final HttpResponse<String> response = await(pending, "the response to the expired request");

            assertAll(
                    () -> assertNull(polled, "request returned by pollRequest after its queue timeout"),
                    () -> assertEquals(503, response.statusCode(), "status of the expired request")
            );
        } finally {
            runner.disableControllerService(uncheckedService);
        }
    }

    @Test
    void anInterruptedPollRequestReturnsNullAndKeepsTheInterruptFlag() {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        Thread.currentThread().interrupt();
        final HttpRequestContainer polled;
        final boolean interruptedAfterPoll;
        try {
            polled = service.pollRequest("orders", TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            interruptedAfterPoll = Thread.interrupted();
        }

        assertAll(
                () -> assertNull(polled, "request returned by pollRequest on an interrupted thread"),
                () -> assertTrue(interruptedAfterPoll, "interrupt flag of the thread after pollRequest")
        );
    }

    @Test
    void pollRequestThrowsForAProcessorThatIsNotRegistered() {
        runner.enableControllerService(service);

        assertThrows(IllegalStateException.class, () -> pollNow("unknown"), "pollRequest(unknown)");
    }

    @Test
    void unregisterProcessorAnswersTheQueuedRequestWith503() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders/.*"), Set.of("GET"), 1, QUEUE_TIMEOUT);
        final CompletableFuture<HttpResponse<String>> queued = sendUntilOneRequestIsQueued("/orders/1", "/orders/2");

        service.unregisterProcessor("orders");

        assertEquals(503, await(queued, "the response to the queued request").statusCode(),
                "status of the request that was queued when the processor unregistered");
    }

    @Test
    void afterUnregisterProcessorItsPathIsAnsweredWith404AndItsQueueIsGone() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        service.unregisterProcessor("orders");
        final HttpResponse<String> response = sendAndWait("GET", "/orders");

        assertAll(
                () -> assertEquals(404, response.statusCode(), "GET /orders after unregisterProcessor(orders)"),
                () -> assertThrows(IllegalStateException.class, () -> pollNow("orders"),
                        "pollRequest(orders) after unregisterProcessor")
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
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        service.unregisterProcessor("orders");

        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        final HttpRequestContainer container = awaitRequest("orders");

        assertEquals("/orders", container.request().getRequestURI(), "URI queued after the second registration");
        answer(container, pending);
    }

    @Test
    void aProcessorCanRegisterAgainAfterTheServiceWasDisabledAndEnabled() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        runner.disableControllerService(service);
        runner.enableControllerService(service);

        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);
        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        final HttpRequestContainer container = awaitRequest("orders");

        assertEquals("/orders", container.request().getRequestURI(), "URI queued after the service was re-enabled");
        answer(container, pending);
    }

    @Test
    void disablingTheServiceAnswersTheQueuedRequestWith503AndReleasesThePort() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders/.*"), Set.of("GET"), 1, QUEUE_TIMEOUT);
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
        stoppingService.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"),
                QUEUE_SIZE, QUEUE_TIMEOUT);

        runner.disableControllerService(stoppingService);

        assertEquals(List.of(503), statusesDuringStop,
                "statuses of GET /orders sent after the registrations were removed and before the server stopped");
    }

    /**
     * The request waits longer than the Idle Timeout twice: in the queue, and after the processor has taken it.
     */
    @Test
    void theIdleTimeoutDoesNotLimitARequestThatWaitsInTheQueueOrForAnAnswer() throws Exception {
        runner.setProperty(service, QubershipHttpRouterService.IDLE_TIMEOUT, "1 sec");
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        assertStillOpen(pending, Duration.ofMillis(LONGER_THAN_IDLE_TIMEOUT_MILLIS),
                "the request in the queue, with an Idle Timeout of 1 sec");
        final HttpRequestContainer container = awaitRequest("orders");
        assertStillOpen(pending, Duration.ofMillis(LONGER_THAN_IDLE_TIMEOUT_MILLIS),
                "the taken request, with an Idle Timeout of 1 sec");
        container.response().setStatus(200);
        container.context().complete();

        assertEquals(200, await(pending, "the response to the request").statusCode(),
                "status of a request answered after " + LONGER_THAN_IDLE_TIMEOUT_MILLIS + " ms in the queue and "
                        + LONGER_THAN_IDLE_TIMEOUT_MILLIS + " ms after it was taken, with an Idle Timeout of 1 sec");
    }

    /**
     * Jetty gives an async context a time limit of 30 s by default, which would answer a request that waits longer
     * in the queue or for the flow before the queue timeout or the HTTP Context Map does.
     */
    @Test
    void theAsyncContextOfAQueuedRequestHasNoTimeLimit() throws Exception {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders");
        final HttpRequestContainer container = awaitRequest("orders");
        final long asyncTimeout = container.context().getTimeout();
        answer(container, pending);

        assertEquals(0, asyncTimeout, "time limit of the async context of the queued request, in ms");
    }

    @Test
    void aRequestWhoseHeadersExceedRequestHeaderMaximumSizeIsAnsweredWith431AndNotQueued() throws Exception {
        runner.setProperty(service, QubershipHttpRouterService.REQUEST_HEADER_MAX_SIZE, "1 KB");
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final HttpResponse<String> response = await(sendWithHeader("X-Padding", "a".repeat(2000)),
                "the response to GET /orders with a 2000-byte header");

        assertAll(
                () -> assertEquals(431, response.statusCode(), "GET /orders with a header over 1 KB"),
                () -> assertNull(pollNow("orders"), "pollRequest(orders) after the 431")
        );
    }

    @Test
    void aRequestWhoseHeadersFitRequestHeaderMaximumSizeIsQueued() throws Exception {
        runner.setProperty(service, QubershipHttpRouterService.REQUEST_HEADER_MAX_SIZE, "1 KB");
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final CompletableFuture<HttpResponse<String>> pending = sendWithHeader("X-Padding", "a".repeat(500));
        final HttpRequestContainer container = awaitRequest("orders");

        assertEquals("/orders", container.request().getRequestURI(), "URI of the request with a 500-byte header");
        answer(container, pending);
    }

    /**
     * Every 127.0.0.0/8 address is a loopback address, so 127.0.0.2 reaches a server bound to all hosts.
     */
    @Test
    void withoutAHostnameTheServerAcceptsConnectionsOnEveryLocalAddress() {
        assumeOtherLoopbackIsLocal();
        runner.enableControllerService(service);

        assertAll(
                () -> assertDoesNotThrow(() -> new Socket(LOOPBACK, port).close(), "connecting to " + LOOPBACK),
                () -> assertDoesNotThrow(() -> new Socket(OTHER_LOOPBACK, port).close(),
                        "connecting to " + OTHER_LOOPBACK)
        );
    }

    @Test
    void withAHostnameTheServerAcceptsConnectionsOnlyOnThatAddress() {
        assumeOtherLoopbackIsLocal();
        runner.setProperty(service, QubershipHttpRouterService.HOSTNAME, LOOPBACK);
        runner.enableControllerService(service);

        assertAll(
                () -> assertDoesNotThrow(() -> new Socket(LOOPBACK, port).close(), "connecting to " + LOOPBACK),
                () -> assertThrows(ConnectException.class, () -> new Socket(OTHER_LOOPBACK, port).close(),
                        "connecting to " + OTHER_LOOPBACK + " with Hostname " + LOOPBACK)
        );
    }

    /**
     * Jetty closes a keep-alive connection after 30 s of idleness by default, so a connection closed within 5 s
     * shows that the server applied the Idle Timeout of 1 s.
     */
    @Test
    void theServerClosesAConnectionIdleForLongerThanIdleTimeout() throws Exception {
        runner.setProperty(service, QubershipHttpRouterService.IDLE_TIMEOUT, "1 sec");
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

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

            assertEquals(-1, input.read(), "read on the connection idle after the response, with an Idle Timeout "
                    + "of 1 sec; -1 means the server closed the connection");
        }
    }

    @Test
    void registerProcessorIsRefusedWhileTheServiceIsNotEnabled() {
        final Pattern paths = Pattern.compile("/orders");
        final Set<String> methods = Set.of("GET");

        assertThrows(IllegalStateException.class,
                () -> service.registerProcessor("orders", paths, methods, QUEUE_SIZE, QUEUE_TIMEOUT));
    }

    @Test
    void registerProcessorIsRefusedForAProcessorThatIsAlreadyRegistered() {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final Pattern paths = Pattern.compile("/users");
        final Set<String> methods = Set.of("GET");

        assertThrows(IllegalStateException.class,
                () -> service.registerProcessor("orders", paths, methods, QUEUE_SIZE, QUEUE_TIMEOUT));
    }

    @Test
    void registerProcessorIsRefusedForAPathPatternAnotherProcessorRegistered() {
        runner.enableControllerService(service);
        service.registerProcessor("orders", Pattern.compile("/orders"), Set.of("GET"), QUEUE_SIZE, QUEUE_TIMEOUT);

        final Pattern paths = Pattern.compile("/orders");
        final Set<String> methods = Set.of("POST");

        assertThrows(IllegalStateException.class,
                () -> service.registerProcessor("orders-copy", paths, methods, QUEUE_SIZE, QUEUE_TIMEOUT));
    }

    @Test
    void registerProcessorIsRefusedForAQueueSizeOfZero() {
        runner.enableControllerService(service);

        final Pattern paths = Pattern.compile("/orders");
        final Set<String> methods = Set.of("GET");

        assertThrows(IllegalArgumentException.class,
                () -> service.registerProcessor("orders", paths, methods, 0, QUEUE_TIMEOUT));
    }

    @Test
    void registerProcessorIsRefusedForAQueueTimeoutOfZero() {
        runner.enableControllerService(service);

        final Pattern paths = Pattern.compile("/orders");
        final Set<String> methods = Set.of("GET");

        assertThrows(IllegalArgumentException.class,
                () -> service.registerProcessor("orders", paths, methods, QUEUE_SIZE, Duration.ZERO));
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
    void anIdleTimeoutWithoutAUnitIsInvalid() {
        runner.setProperty(service, QubershipHttpRouterService.IDLE_TIMEOUT, "60");

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

    private CompletableFuture<HttpResponse<String>> sendWithHeader(final String name, final String value) {
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
                .header(name, value)
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Skips the test where 127.0.0.2 is not a local address. Linux and Windows route all of 127.0.0.0/8 to the
     * loopback interface, while macOS has only 127.0.0.1 unless an alias is added.
     */
    private static void assumeOtherLoopbackIsLocal() {
        boolean local;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(OTHER_LOOPBACK))) {
            local = true;
        } catch (final IOException e) {
            local = false;
        }
        assumeTrue(local, OTHER_LOOPBACK + " is not a local address on this machine");
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

    /**
     * Writes a GET request with the path exactly as given to a new connection.
     *
     * @param path request target, sent without normalization
     * @return the open connection, from which the response can be read
     */
    private Socket sendRawGet(final String path) throws IOException {
        final Socket socket = new Socket("localhost", port);
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        final String request = """
                GET %s HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """.formatted(path);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private HttpRequestContainer pollNow(final String processorId) {
        return service.pollRequest(processorId, 0, TimeUnit.MILLISECONDS);
    }

    private HttpRequestContainer awaitRequest(final String processorId) {
        return Awaitility.await("a request in the queue of processor " + processorId)
                .atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .pollInterval(Duration.ofMillis(POLL_INTERVAL_MILLIS))
                .until(() -> pollNow(processorId), Objects::nonNull);
    }

    /**
     * Fails unless the request stays without a response, and its connection open, for the whole period.
     *
     * @param pending the request
     * @param period how long the request has to stay open
     * @param request what the request is, for the failure message
     */
    private static void assertStillOpen(final CompletableFuture<?> pending, final Duration period,
            final String request) {
        Awaitility.await(request + " to stay open for " + period.toMillis() + " ms")
                .during(period)
                .atMost(period.plusSeconds(TIMEOUT_SECONDS))
                .until(() -> !pending.isDone());
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
