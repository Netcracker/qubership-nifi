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

package org.qubership.nifi.processors.http;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.http.HttpContextMap;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.service.http.HttpRequestContainer;
import org.qubership.nifi.service.http.HttpRouterService;
import org.qubership.nifi.service.http.QubershipHttpRouterService;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A request that the router service routes to the processor becomes a FlowFile on success and is registered on the
 * HTTP Context Map, and the paths and methods the processor registers decide which requests reach it.
 *
 * <p>Each test starts a QubershipHttpRouterService on a free local port and sends real HTTP requests to it. The HTTP
 * Context Map is an in-memory fake that records each registered request, so a test answers the request through it
 * the way HandleHttpResponse does.
 */
class QubershipHandleHttpRequestTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final long POLL_INTERVAL_MILLIS = 10;
    private static final long UNSCHEDULED_WAIT_MILLIS = 500;
    private static final String BOUNDARY = "order-boundary";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();
    private TestRunner runner;
    private QubershipHttpRouterService routerService;
    private RecordingHttpContextMap contextMap;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        runner = TestRunners.newTestRunner(QubershipHandleHttpRequest.class);

        routerService = new QubershipHttpRouterService();
        runner.addControllerService("router", routerService);
        runner.setProperty(routerService, QubershipHttpRouterService.PORT, Integer.toString(port));
        runner.enableControllerService(routerService);

        contextMap = new RecordingHttpContextMap();
        runner.addControllerService("context-map", contextMap);
        runner.enableControllerService(contextMap);

        runner.setProperty(QubershipHandleHttpRequest.HTTP_ROUTER_SERVICE, "router");
        runner.setProperty(QubershipHandleHttpRequest.HTTP_CONTEXT_MAP, "context-map");
        runner.setProperty(QubershipHandleHttpRequest.REQUEST_PATH, "/orders(/.*)?");
    }

    @AfterEach
    void tearDown() {
        stopProcessor(runner);
        runner.disableControllerService(routerService);
        client.close();
    }

    @Test
    void aRequestBecomesAFlowFileWithItsBodyAndAttributesAndIsAnsweredThroughTheContextMap() throws Exception {
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                HttpRequest.newBuilder(uri("/orders/17?status=new&note=a%20b"))
                        .header("Content-Type", "text/plain")
                        .header("X-Order-Source", "shop")
                        .POST(HttpRequest.BodyPublishers.ofString("order body"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        final MockFlowFile flowFile = awaitFlowFiles(1).getFirst();
        final String contextId = flowFile.getAttribute("http.context.identifier");
        answer(contextId, 201);
        final HttpResponse<String> response = await(pending, "the response to POST /orders/17");

        assertAll(
                () -> flowFile.assertContentEquals("order body"),
                () -> assertEquals("POST", flowFile.getAttribute("http.method"), "http.method"),
                () -> assertEquals("/orders/17", flowFile.getAttribute("http.request.uri"), "http.request.uri"),
                () -> assertEquals("status=new&note=a b", flowFile.getAttribute("http.query.string"),
                        "http.query.string"),
                () -> assertEquals("new", flowFile.getAttribute("http.query.param.status"),
                        "http.query.param.status"),
                () -> assertEquals("a b", flowFile.getAttribute("http.query.param.note"), "http.query.param.note"),
                () -> assertEquals("text/plain", flowFile.getAttribute("mime.type"), "mime.type"),
                () -> assertEquals("shop", flowFile.getAttribute("http.headers.X-Order-Source"),
                        "http.headers.X-Order-Source"),
                () -> assertEquals(Integer.toString(port), flowFile.getAttribute("http.server.port"),
                        "http.server.port"),
                () -> assertEquals(201, response.statusCode(), "status the client received")
        );
    }

    @Test
    void aMethodWhoseAllowPropertyIsFalseIsAnsweredWith405() throws Exception {
        runner.setProperty(QubershipHandleHttpRequest.ALLOW_GET, "false");
        startProcessor();

        final HttpResponse<String> response = sendAndWait("GET", "/orders");

        assertEquals(405, response.statusCode(), "GET /orders with Allow GET false");
    }

    @Test
    void aMethodListedInAdditionalHttpMethodsReachesTheProcessor() throws Exception {
        runner.setProperty(QubershipHandleHttpRequest.ADDITIONAL_METHODS, "patch, PROPFIND");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = send("PROPFIND", "/orders");
        final MockFlowFile flowFile = awaitFlowFiles(1).getFirst();
        answer(flowFile.getAttribute("http.context.identifier"), 200);
        await(pending, "the response to PROPFIND /orders");

        assertEquals("PROPFIND", flowFile.getAttribute("http.method"), "http.method");
    }

    @Test
    void twoProcessorsOnOnePortEachReceiveOnlyTheRequestsForTheirOwnPath() throws Exception {
        final TestRunner usersRunner = TestRunners.newTestRunner(QubershipHandleHttpRequest.class);
        final DelegatingHttpRouterService sharedRouter = new DelegatingHttpRouterService(routerService);
        usersRunner.addControllerService("router", sharedRouter);
        usersRunner.enableControllerService(sharedRouter);
        final RecordingHttpContextMap usersContextMap = new RecordingHttpContextMap();
        usersRunner.addControllerService("context-map", usersContextMap);
        usersRunner.enableControllerService(usersContextMap);
        usersRunner.setProperty(QubershipHandleHttpRequest.HTTP_ROUTER_SERVICE, "router");
        usersRunner.setProperty(QubershipHandleHttpRequest.HTTP_CONTEXT_MAP, "context-map");
        usersRunner.setProperty(QubershipHandleHttpRequest.REQUEST_PATH, "/users(/.*)?");
        startProcessor();
        usersRunner.run(1, false, true);
        try {
            final CompletableFuture<HttpResponse<String>> ordersPending = send("GET", "/orders/1");
            final CompletableFuture<HttpResponse<String>> usersPending = send("GET", "/users/1");
            final List<MockFlowFile> ordersFlowFiles = awaitFlowFiles(runner, 1);
            final List<MockFlowFile> usersFlowFiles = awaitFlowFiles(usersRunner, 1);
            answer(contextMap, ordersFlowFiles.getFirst().getAttribute("http.context.identifier"), 200);
            answer(usersContextMap, usersFlowFiles.getFirst().getAttribute("http.context.identifier"), 200);
            final HttpResponse<String> ordersResponse = await(ordersPending, "the response to GET /orders/1");
            final HttpResponse<String> usersResponse = await(usersPending, "the response to GET /users/1");

            assertAll(
                    () -> assertEquals(List.of("/orders/1"), requestUris(ordersFlowFiles),
                            "request URIs received by the processor with Request Path /orders(/.*)?"),
                    () -> assertEquals(List.of("/users/1"), requestUris(usersFlowFiles),
                            "request URIs received by the processor with Request Path /users(/.*)?"),
                    () -> assertEquals(200, ordersResponse.statusCode(), "status of GET /orders/1"),
                    () -> assertEquals(200, usersResponse.statusCode(), "status of GET /users/1")
            );
        } finally {
            stopProcessor(usersRunner);
        }
    }

    @Test
    void aPathOutsideRequestPathIsAnsweredWith404() throws Exception {
        startProcessor();

        final HttpResponse<String> response = sendAndWait("GET", "/users");

        assertEquals(404, response.statusCode(), "GET /users with Request Path /orders(/.*)?");
    }

    @Test
    void onlyTheParametersListedInParametersToAttributesBecomeAttributes() throws Exception {
        runner.setProperty(QubershipHandleHttpRequest.PARAMETERS_TO_ATTRIBUTES, "status");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = send("GET", "/orders?status=new&limit=10");
        final MockFlowFile flowFile = awaitFlowFiles(1).getFirst();
        answer(flowFile.getAttribute("http.context.identifier"), 200);
        await(pending, "the response to GET /orders");

        assertAll(
                () -> assertEquals("new", flowFile.getAttribute("http.param.status"), "http.param.status"),
                () -> assertNull(flowFile.getAttribute("http.param.limit"), "http.param.limit")
        );
    }

    @Test
    void aUrlencodedFormBodyStaysInTheContentAndItsFieldsAreNotOutputAsAttributes() throws Exception {
        runner.setProperty(QubershipHandleHttpRequest.PARAMETERS_TO_ATTRIBUTES, "status");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                HttpRequest.newBuilder(uri("/orders"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("status=new"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        final MockFlowFile flowFile = awaitFlowFiles(1).getFirst();
        answer(flowFile.getAttribute("http.context.identifier"), 200);
        await(pending, "the response to the form POST /orders");

        assertAll(
                () -> flowFile.assertContentEquals("status=new"),
                () -> assertNull(flowFile.getAttribute("http.param.status"), "http.param.status")
        );
    }

    @Test
    void aMultipartFieldListedInParametersToAttributesBecomesAnAttribute() throws Exception {
        runner.setProperty(QubershipHandleHttpRequest.PARAMETERS_TO_ATTRIBUTES, "status");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = sendMultipart(multipartBodyWithOneField());
        final MockFlowFile flowFile = awaitFlowFiles(1).getFirst();
        answer(flowFile.getAttribute("http.context.identifier"), 200);
        await(pending, "the response to the multipart POST /orders");

        assertEquals("new", flowFile.getAttribute("http.param.status"), "http.param.status");
    }

    @Test
    void aQueryStringWithMalformedPercentEncodingIsAnsweredWith400AndProducesNoFlowFile() throws Exception {
        startProcessor();

        final CompletableFuture<String> pending = sendRaw("GET /orders?note=%zz HTTP/1.1\r\n"
                + "Host: localhost\r\nConnection: close\r\n\r\n");
        final String statusLine = awaitWhileTriggering(pending, "the response to GET /orders?note=%zz");

        assertAll(
                () -> assertEquals("HTTP/1.1 400 Bad Request", statusLine, "status line of GET /orders?note=%zz"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success"),
                () -> assertEquals(0, contextMap.registeredCount(), "requests registered on the context map")
        );
    }

    /**
     * %C3 is valid hex but only the first byte of a two-byte UTF-8 character. Jetty refuses it only when the
     * processor reads the request parameters, which Parameters to Attributes makes it do.
     */
    @Test
    void aQueryParameterWithAnIncompleteUtf8SequenceIsAnsweredWith400AndProducesNoFlowFile() throws Exception {
        runner.setProperty(QubershipHandleHttpRequest.PARAMETERS_TO_ATTRIBUTES, "x");
        startProcessor();

        final CompletableFuture<String> pending = sendRaw("GET /orders?x=%C3 HTTP/1.1\r\n"
                + "Host: localhost\r\nConnection: close\r\n\r\n");
        final String statusLine = awaitWhileTriggering(pending, "the response to GET /orders?x=%C3");

        assertAll(
                () -> assertEquals("HTTP/1.1 400 Bad Request", statusLine, "status line of GET /orders?x=%C3"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success"),
                () -> assertEquals(0, contextMap.registeredCount(), "requests registered on the context map")
        );
    }

    /**
     * Without Parameters to Attributes the processor does not read the request parameters, and the query string is
     * decoded with the replacement character in place of the incomplete UTF-8 sequence.
     */
    @Test
    void aQueryParameterWithAnIncompleteUtf8SequenceBecomesAFlowFileWithoutParametersToAttributes()
            throws Exception {
        startProcessor();

        final CompletableFuture<String> pending = sendRaw("GET /orders?x=%C3 HTTP/1.1\r\n"
                + "Host: localhost\r\nConnection: close\r\n\r\n");
        final MockFlowFile flowFile = awaitFlowFiles(1).getFirst();
        answer(flowFile.getAttribute("http.context.identifier"), 200);
        final String statusLine = await(pending, "the response to GET /orders?x=%C3");

        assertAll(
                () -> assertEquals("HTTP/1.1 200 OK", statusLine, "status line of GET /orders?x=%C3"),
                () -> assertEquals("\ufffd", flowFile.getAttribute("http.query.param.x"), "http.query.param.x")
        );
    }

    @Test
    void aQueryStringIsDecodedAsUtf8WhenTheRequestDeclaresNoCharset() throws Exception {
        startProcessor();

        final MockFlowFile flowFile = receiveWithContentType("/orders?note=%C3%A9", "text/plain");

        assertEquals("\u00e9", flowFile.getAttribute("http.query.param.note"), "http.query.param.note of %C3%A9");
    }

    @Test
    void aQueryStringIsDecodedWithTheCharsetTheRequestDeclares() throws Exception {
        startProcessor();

        final MockFlowFile flowFile = receiveWithContentType("/orders?note=%E9", "text/plain; charset=ISO-8859-1");

        assertEquals("\u00e9", flowFile.getAttribute("http.query.param.note"),
                "http.query.param.note of %E9 in ISO-8859-1");
    }

    @Test
    void aRequestThatDeclaresAnUnsupportedCharsetIsAnsweredWith400AndProducesNoFlowFile() throws Exception {
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                HttpRequest.newBuilder(uri("/orders"))
                        .header("Content-Type", "text/plain; charset=x-unsupported")
                        .POST(HttpRequest.BodyPublishers.ofString("body"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        final HttpResponse<String> response = awaitWhileTriggering(pending, "the response to POST /orders");

        assertAll(
                () -> assertEquals(400, response.statusCode(), "status of a request with charset x-unsupported"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success")
        );
    }

    @Test
    void theProcessorRegistersItsPathDefaultMethodsAndQueueSize() throws Exception {
        final RecordingHttpRouterService recordingRouter = new RecordingHttpRouterService();
        runner.addControllerService("recording-router", recordingRouter);
        runner.enableControllerService(recordingRouter);
        runner.setProperty(QubershipHandleHttpRequest.HTTP_ROUTER_SERVICE, "recording-router");

        startProcessor();

        assertAll(
                () -> assertEquals("/orders(/.*)?", recordingRouter.paths.pattern(), "registered path pattern"),
                () -> assertEquals(Set.of("GET", "POST", "PUT", "DELETE"), recordingRouter.methods,
                        "registered methods with the default Allow properties"),
                () -> assertEquals(50, recordingRouter.queueSize, "registered queue size"),
                () -> assertEquals(Duration.ofSeconds(30), recordingRouter.queueTimeout,
                        "registered queue timeout with the default Request Queue Timeout")
        );
    }

    @Test
    void onTriggerWaitsForARequestWhenTheQueueIsEmpty() throws Exception {
        final RecordingHttpRouterService recordingRouter = new RecordingHttpRouterService();
        runner.addControllerService("recording-router", recordingRouter);
        runner.enableControllerService(recordingRouter);
        runner.setProperty(QubershipHandleHttpRequest.HTTP_ROUTER_SERVICE, "recording-router");

        startProcessor();

        assertTrue(recordingRouter.pollTimeoutNanos > 0,
                "timeout passed to pollRequest, in ns: " + recordingRouter.pollTimeoutNanos);
    }

    @Test
    void aRequestWhoseBodyEndsEarlyProducesNoFlowFileAndIsNotRegistered() throws Exception {
        startProcessor();

        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(("POST /orders HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 100\r\n\r\nten bytes.").getBytes(StandardCharsets.US_ASCII));
            socket.shutdownOutput();
            awaitErrorLog("Stream Processing Failed");
        }

        assertAll(
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success"),
                () -> assertEquals(0, contextMap.registeredCount(), "requests registered on the context map")
        );
    }

    @Test
    void aMultipartRequestOfExactlyMultipartRequestMaxSizeIsAccepted() throws Exception {
        final String body = multipartBodyWithOneField();
        runner.setProperty(QubershipHandleHttpRequest.MULTIPART_REQUEST_MAX_SIZE, body.length() + " B");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = sendMultipart(body);
        final MockFlowFile flowFile = awaitFlowFiles(1).getFirst();
        answer(flowFile.getAttribute("http.context.identifier"), 200);
        await(pending, "the response to the multipart POST /orders");

        flowFile.assertContentEquals("new");
    }

    @Test
    void aMultipartRequestOneByteOverMultipartRequestMaxSizeIsAnsweredWith400() throws Exception {
        final String body = multipartBodyWithOneField();
        runner.setProperty(QubershipHandleHttpRequest.MULTIPART_REQUEST_MAX_SIZE, (body.length() - 1) + " B");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = sendMultipart(body);
        final HttpResponse<String> response = awaitWhileTriggering(pending, "the response to the multipart POST");

        assertAll(
                () -> assertEquals(400, response.statusCode(), "status of a multipart request over the max size"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success")
        );
    }

    @Test
    void aRequestTheContextMapRefusesIsAnsweredWith503AndProducesNoFlowFile() throws Exception {
        contextMap.refuseRegistrations();
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = send("POST", "/orders");
        final HttpResponse<String> response = awaitWhileTriggering(pending, "the response to POST /orders");

        assertAll(
                () -> assertEquals(503, response.statusCode(), "status of the request the context map refused"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success")
        );
    }

    @Test
    void aMultipartRequestTheContextMapRefusesIsAnsweredWith503AndProducesNoFlowFile() throws Exception {
        contextMap.refuseRegistrations();
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = sendMultipart(multipartBodyWithTwoParts());
        final HttpResponse<String> response = awaitWhileTriggering(pending, "the response to the multipart POST");

        assertAll(
                () -> assertEquals(503, response.statusCode(),
                        "status of the multipart request the context map refused"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success")
        );
    }

    /**
     * Once the context map holds the request, the processor leaves the answer to it: the test answers the request
     * through the context map after onTrigger has thrown.
     */
    @Test
    void anExceptionAfterTheRequestIsRegisteredLeavesTheAnswerToTheContextMap() throws Exception {
        contextMap.failAfterRegistering();
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = send("POST", "/orders");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        AssertionError onTriggerFailure = null;
        while (contextMap.registeredCount() == 0 && System.nanoTime() < deadline) {
            try {
                runner.run(1, false, false);
            } catch (final AssertionError e) {
                onTriggerFailure = e;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        answer(contextMap, contextMap.registeredIdentifier(), 200);
        final HttpResponse<String> response = await(pending, "the response to POST /orders");
        final Throwable failure = onTriggerFailure == null ? null : onTriggerFailure.getCause();

        assertAll(
                () -> assertEquals(200, response.statusCode(), "status set through the context map"),
                () -> assertInstanceOf(ProcessException.class, failure, "exception NiFi received from onTrigger")
        );
    }

    @Test
    void eachPartOfAMultipartRequestBecomesAFlowFileUnderOneContextIdentifier() throws Exception {
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = sendMultipart(multipartBodyWithTwoParts());
        final List<MockFlowFile> flowFiles = awaitFlowFiles(2);
        final MockFlowFile headerPart = flowFiles.get(0);
        final MockFlowFile linesPart = flowFiles.get(1);
        answer(headerPart.getAttribute("http.context.identifier"), 200);
        await(pending, "the response to the multipart POST /orders");

        assertAll(
                () -> headerPart.assertContentEquals("header content"),
                () -> assertEquals("header", headerPart.getAttribute("http.multipart.name"),
                        "http.multipart.name of the header part"),
                () -> assertEquals("1", headerPart.getAttribute("http.multipart.fragments.sequence.number"),
                        "http.multipart.fragments.sequence.number of the header part"),
                () -> linesPart.assertContentEquals("lines content"),
                () -> assertEquals("lines.csv", linesPart.getAttribute("http.multipart.filename"),
                        "http.multipart.filename of the lines part"),
                () -> assertEquals("2", linesPart.getAttribute("http.multipart.fragments.total.number"),
                        "http.multipart.fragments.total.number of the lines part"),
                () -> assertEquals(headerPart.getAttribute("http.context.identifier"),
                        linesPart.getAttribute("http.context.identifier"), "http.context.identifier of both parts"),
                () -> assertEquals(1, contextMap.registeredCount(), "requests registered on the context map")
        );
    }

    @Test
    void stoppingAnswersTheQueuedRequestWith503() throws Exception {
        runner.setProperty(QubershipHandleHttpRequest.CONTAINER_QUEUE_SIZE, "1");
        startProcessor();
        final CompletableFuture<HttpResponse<String>> first = send("GET", "/orders/1");
        final CompletableFuture<HttpResponse<String>> second = send("GET", "/orders/2");
        await(CompletableFuture.anyOf(first, second), "one of two requests to be refused by a queue of size 1");
        final CompletableFuture<HttpResponse<String>> queued = first.isDone() ? second : first;

        stopProcessor(runner);

        assertEquals(503, await(queued, "the response to the queued request").statusCode(),
                "status of the request that was queued when the processor was stopped");
    }

    @Test
    void afterStoppingItsPathIsAnsweredWith404() throws Exception {
        startProcessor();

        stopProcessor(runner);
        final HttpResponse<String> response = sendAndWait("GET", "/orders");

        assertEquals(404, response.statusCode(), "GET /orders after the processor was stopped");
    }

    /**
     * NiFi calls the @OnUnscheduled methods while onTrigger calls may still run, and the @OnStopped methods after the
     * last one has returned, so the processor keeps its registration until it is stopped.
     */
    @Test
    void aQueuedRequestIsAnsweredWhenTheProcessorStopsNotWhenItIsUnscheduled() throws Exception {
        startProcessor();
        final CompletableFuture<HttpResponse<String>> queued = send("GET", "/orders/1");
        Thread.sleep(UNSCHEDULED_WAIT_MILLIS);

        runner.unSchedule();
        Thread.sleep(UNSCHEDULED_WAIT_MILLIS);
        final boolean answeredWhenUnscheduled = queued.isDone();
        runner.stop();
        final HttpResponse<String> response = await(queued, "the response to the queued request");

        assertAll(
                () -> assertFalse(answeredWhenUnscheduled, "queued request answered after unSchedule alone"),
                () -> assertEquals(503, response.statusCode(), "status of the queued request after stop")
        );
    }

    @Test
    void aRequestPathThatIsNotARegularExpressionIsInvalid() {
        runner.setProperty(QubershipHandleHttpRequest.REQUEST_PATH, "/orders(");

        runner.assertNotValid();
    }

    @Test
    void aRequestQueueTimeoutOfZeroIsInvalid() {
        runner.setProperty(QubershipHandleHttpRequest.REQUEST_QUEUE_TIMEOUT, "0 sec");

        runner.assertNotValid();
    }

    @Test
    void aRequestQueueTimeoutOfOneMillisecondIsValid() {
        runner.setProperty(QubershipHandleHttpRequest.REQUEST_QUEUE_TIMEOUT, "1 millis");

        runner.assertValid();
    }

    /**
     * Runs the OnScheduled methods, which register the processor on the router service, and leaves the processor
     * scheduled.
     */
    private void startProcessor() {
        runner.run(1, false, true);
    }

    private static void stopProcessor(final TestRunner processorRunner) {
        processorRunner.unSchedule();
        processorRunner.stop();
    }

    private List<MockFlowFile> awaitFlowFiles(final int count) throws InterruptedException {
        return awaitFlowFiles(runner, count);
    }

    private List<MockFlowFile> awaitFlowFiles(final TestRunner processorRunner, final int count)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        List<MockFlowFile> flowFiles = List.of();
        while (System.nanoTime() < deadline) {
            processorRunner.run(1, false, false);
            flowFiles = processorRunner.getFlowFilesForRelationship(QubershipHandleHttpRequest.REL_SUCCESS);
            if (flowFiles.size() >= count) {
                return flowFiles;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return fail("Timed out after " + TIMEOUT_SECONDS + " s waiting for " + count
                + " FlowFiles on success; last count " + flowFiles.size());
    }

    private <T> T awaitWhileTriggering(final CompletableFuture<T> future, final String awaited)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            runner.run(1, false, false);
            if (future.isDone()) {
                return future.get();
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return fail("Timed out after " + TIMEOUT_SECONDS + " s waiting for " + awaited
                + " while triggering the processor; the request is still open");
    }

    private void answer(final String contextId, final int status) {
        answer(contextMap, contextId, status);
    }

    private static void answer(final HttpContextMap map, final String contextId, final int status) {
        final HttpServletResponse response = map.getResponse(contextId);
        assertNotNull(response, "context map entry for " + contextId);
        response.setStatus(status);
        map.complete(contextId);
    }

    private static List<String> requestUris(final List<MockFlowFile> flowFiles) {
        return flowFiles.stream().map(flowFile -> flowFile.getAttribute("http.request.uri")).toList();
    }

    private MockFlowFile receiveWithContentType(final String pathAndQuery, final String contentType)
            throws Exception {
        final CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                HttpRequest.newBuilder(uri(pathAndQuery))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString("body"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        final MockFlowFile flowFile = awaitFlowFiles(1).getFirst();
        answer(flowFile.getAttribute("http.context.identifier"), 200);
        await(pending, "the response to POST " + pathAndQuery);
        return flowFile;
    }

    private static String multipartBodyWithTwoParts() {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"header\"\r\n\r\n"
                + "header content\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"lines\"; filename=\"lines.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + "lines content\r\n"
                + "--" + BOUNDARY + "--\r\n";
    }

    private static String multipartBodyWithOneField() {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"status\"\r\n\r\n"
                + "new\r\n"
                + "--" + BOUNDARY + "--\r\n";
    }

    private void awaitErrorLog(final String reason) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            runner.run(1, false, false);
            if (runner.getLogger().getErrorMessages().stream().anyMatch(log -> log.getMsg().contains(reason))) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        fail("Timed out after " + TIMEOUT_SECONDS + " s waiting for an error log containing [" + reason
                + "]; error logs: " + runner.getLogger().getErrorMessages());
    }

    private CompletableFuture<HttpResponse<String>> sendMultipart(final String body) {
        return sendMultipart("/orders", body);
    }

    private CompletableFuture<HttpResponse<String>> sendMultipart(final String pathAndQuery, final String body) {
        return client.sendAsync(
                HttpRequest.newBuilder(uri(pathAndQuery))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Writes the request bytes to a new connection as they are and returns the status line of the response.
     *
     * @param request the complete HTTP request, headers and body
     * @return the status line of the response, such as HTTP/1.1 400 Bad Request
     */
    private CompletableFuture<String> sendRaw(final String request) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = new Socket("localhost", port)) {
                socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                        .readLine();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private URI uri(final String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private CompletableFuture<HttpResponse<String>> send(final String method, final String path) {
        final HttpRequest request = HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendAndWait(final String method, final String path) throws Exception {
        return await(send(method, path), "the response to " + method + " " + path);
    }

    private static <T> T await(final CompletableFuture<T> future, final String awaited) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            return fail("Timed out after " + TIMEOUT_SECONDS + " s waiting for " + awaited
                    + "; the request is still open", e);
        }
    }

    @Test
    void aMultipartRequestWithAQueryParameterJettyCannotDecodeIsAnsweredWith400AndProducesNoFlowFile()
            throws Exception {
        runner.setProperty(QubershipHandleHttpRequest.PARAMETERS_TO_ATTRIBUTES, "x");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending =
                sendMultipart("/orders?x=%C3", multipartBodyWithTwoParts());
        final HttpResponse<String> response = awaitWhileTriggering(pending, "the response to the multipart POST");

        assertAll(
                () -> assertEquals(400, response.statusCode(), "status of a multipart POST /orders?x=%C3"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success"),
                () -> assertEquals(0, contextMap.registeredCount(), "requests registered on the context map")
        );
    }

    @Test
    void aMultipartRequestWithNoPartsIsAnsweredWith400AndProducesNoFlowFile() throws Exception {
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = sendMultipart("--" + BOUNDARY + "--\r\n");
        final HttpResponse<String> response = awaitWhileTriggering(pending, "the response to the multipart POST");

        assertAll(
                () -> assertEquals(400, response.statusCode(), "status of a multipart request with no parts"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success"),
                () -> assertEquals(0, contextMap.registeredCount(), "requests registered on the context map")
        );
    }

    /**
     * The first part is written before the second one fails. A processor that registered and transferred each part
     * as soon as it was written would leave that part on success, registered for a request answered with 400.
     */
    @Test
    void aMultipartRequestWhoseSecondPartFailsIsAnsweredWith400ProducesNoFlowFileAndClosesEveryPartStream()
            throws Exception {
        final PartStreamRecordingRouterService recordingRouter =
                new PartStreamRecordingRouterService(routerService, true);
        runner.addControllerService("recording-router", recordingRouter);
        runner.enableControllerService(recordingRouter);
        runner.setProperty(QubershipHandleHttpRequest.HTTP_ROUTER_SERVICE, "recording-router");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = sendMultipart(multipartBodyWithTwoParts());
        final HttpResponse<String> response = awaitWhileTriggering(pending, "the response to the multipart POST");

        assertAll(
                () -> assertEquals(400, response.statusCode(),
                        "status of a multipart request whose second part failed"),
                () -> assertEquals(List.of(), runner.getFlowFilesForRelationship(
                        QubershipHandleHttpRequest.REL_SUCCESS), "FlowFiles on success"),
                () -> assertEquals(0, contextMap.registeredCount(), "requests registered on the context map"),
                () -> assertEquals(List.of(true, true), recordingRouter.closedStates(),
                        "closed state of the stream of each part")
        );
    }

    @Test
    void theStreamOfEveryPartIsClosedAfterAMultipartRequestBecomesFlowFiles() throws Exception {
        final PartStreamRecordingRouterService recordingRouter =
                new PartStreamRecordingRouterService(routerService, false);
        runner.addControllerService("recording-router", recordingRouter);
        runner.enableControllerService(recordingRouter);
        runner.setProperty(QubershipHandleHttpRequest.HTTP_ROUTER_SERVICE, "recording-router");
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = sendMultipart(multipartBodyWithTwoParts());
        final List<MockFlowFile> flowFiles = awaitFlowFiles(2);
        answer(flowFiles.getFirst().getAttribute("http.context.identifier"), 200);
        await(pending, "the response to the multipart POST /orders");

        assertEquals(List.of(true, true), recordingRouter.closedStates(), "closed state of the stream of each part");
    }

    /**
     * The exception comes from the HTTP Context Map, so the request is not registered when it is thrown. Without an
     * answer from the processor, the client would wait until the test times out.
     */
    @Test
    void anExceptionBeforeTheRequestIsRegisteredIsAnsweredWith500AndReachesNiFi() throws Exception {
        contextMap.failRegistrations();
        startProcessor();

        final CompletableFuture<HttpResponse<String>> pending = send("POST", "/orders");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        AssertionError onTriggerFailure = null;
        while (!pending.isDone() && System.nanoTime() < deadline) {
            try {
                runner.run(1, false, false);
            } catch (final AssertionError e) {
                onTriggerFailure = e;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        final HttpResponse<String> response = await(pending, "the response to POST /orders");
        final Throwable failure = onTriggerFailure == null ? null : onTriggerFailure.getCause();

        assertAll(
                () -> assertEquals(500, response.statusCode(), "status of the request whose registration threw"),
                () -> assertInstanceOf(ProcessException.class, failure, "exception NiFi received from onTrigger")
        );
    }

    /**
     * In-memory HTTP Context Map that keeps every registered request until the test completes it.
     */
    private static final class RecordingHttpContextMap extends AbstractControllerService implements HttpContextMap {

        private static final long REQUEST_EXPIRATION_MILLIS = 30000;
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();
        private volatile boolean refusing;
        private volatile boolean failing;
        private volatile boolean failingAfterRegistering;
        private volatile int registered;

        void refuseRegistrations() {
            refusing = true;
        }

        void failRegistrations() {
            failing = true;
        }

        void failAfterRegistering() {
            failingAfterRegistering = true;
        }

        String registeredIdentifier() {
            return entries.keySet().iterator().next();
        }

        int registeredCount() {
            return registered;
        }

        @Override
        public boolean register(final String identifier, final HttpServletRequest request,
                final HttpServletResponse response, final AsyncContext context) {
            if (failing) {
                throw new ProcessException("Registration failed in the test");
            }
            if (refusing) {
                return false;
            }
            entries.put(identifier, new Entry(response, context));
            registered++;
            if (failingAfterRegistering) {
                throw new ProcessException("Registration failed in the test after the request was stored");
            }
            return true;
        }

        @Override
        public HttpServletResponse getResponse(final String identifier) {
            final Entry entry = entries.get(identifier);
            return entry == null ? null : entry.response();
        }

        @Override
        public void complete(final String identifier) {
            final Entry entry = entries.remove(identifier);
            if (entry != null) {
                entry.context().complete();
            }
        }

        @Override
        public long getRequestTimeout(final TimeUnit timeUnit) {
            return timeUnit.convert(REQUEST_EXPIRATION_MILLIS, TimeUnit.MILLISECONDS);
        }

        private record Entry(HttpServletResponse response, AsyncContext context) {
        }
    }

    /**
     * HTTP Router Service that passes every call to a router service enabled in another TestRunner.
     *
     * <p>A second TestRunner cannot enable the same QubershipHttpRouterService, because enabling it starts another
     * server on the port the first one already listens on. Through this service, processors in two TestRunners
     * register on one server.
     */
    private static class DelegatingHttpRouterService extends AbstractControllerService
            implements HttpRouterService {

        private final HttpRouterService target;

        DelegatingHttpRouterService(final HttpRouterService targetService) {
            this.target = targetService;
        }

        @Override
        public void registerProcessor(final String processorId, final Pattern paths, final Set<String> methods,
                final int queueSize, final Duration queueTimeout) {
            target.registerProcessor(processorId, paths, methods, queueSize, queueTimeout);
        }

        @Override
        public void unregisterProcessor(final String processorId) {
            target.unregisterProcessor(processorId);
        }

        @Override
        public HttpRequestContainer pollRequest(final String processorId, final long timeout, final TimeUnit unit) {
            return target.pollRequest(processorId, timeout, unit);
        }
    }

    /**
     * HTTP Router Service that takes the requests of a router service enabled in another TestRunner and records,
     * for each multipart part in order, whether the stream the processor got from it is closed. It can also make
     * reading the stream of the second part fail with IOException.
     */
    private static final class PartStreamRecordingRouterService extends DelegatingHttpRouterService {

        private final boolean failSecondPartRead;
        private final List<RecordingInputStream> streams = new CopyOnWriteArrayList<>();

        PartStreamRecordingRouterService(final HttpRouterService targetService, final boolean failSecondRead) {
            super(targetService);
            this.failSecondPartRead = failSecondRead;
        }

        List<Boolean> closedStates() {
            return streams.stream().map(stream -> stream.closed).toList();
        }

        @Override
        public HttpRequestContainer pollRequest(final String processorId, final long timeout, final TimeUnit unit) {
            final HttpRequestContainer container = super.pollRequest(processorId, timeout, unit);
            if (container == null) {
                return null;
            }
            final HttpServletRequest request = new HttpServletRequestWrapper(container.request()) {
                @Override
                public Collection<Part> getParts() throws IOException, ServletException {
                    final List<Part> parts = new ArrayList<>(super.getParts());
                    for (int i = 0; i < parts.size(); i++) {
                        parts.set(i, recordingPart(parts.get(i), failSecondPartRead && i == 1));
                    }
                    return parts;
                }
            };
            return new HttpRequestContainer(request, container.response(), container.context());
        }

        private Part recordingPart(final Part part, final boolean failRead) {
            return (Part) Proxy.newProxyInstance(Part.class.getClassLoader(), new Class<?>[] {Part.class},
                    (proxy, method, args) -> {
                        if ("getInputStream".equals(method.getName())) {
                            final RecordingInputStream stream =
                                    new RecordingInputStream(part.getInputStream(), failRead);
                            streams.add(stream);
                            return stream;
                        }
                        try {
                            return method.invoke(part, args);
                        } catch (final InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    /**
     * Stream that records whether it is closed and can fail every read with IOException.
     */
    private static final class RecordingInputStream extends FilterInputStream {

        private final boolean failRead;
        private volatile boolean closed;

        RecordingInputStream(final InputStream in, final boolean failEveryRead) {
            super(in);
            this.failRead = failEveryRead;
        }

        @Override
        public int read() throws IOException {
            failIfAsked();
            return super.read();
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            failIfAsked();
            return super.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private void failIfAsked() throws IOException {
            if (failRead) {
                throw new IOException("Reading the part failed in the test");
            }
        }
    }

    /**
     * HTTP Router Service that records the last registration and routes no requests.
     */
    private static final class RecordingHttpRouterService extends AbstractControllerService
            implements HttpRouterService {

        private volatile Pattern paths;
        private volatile Set<String> methods;
        private volatile int queueSize;
        private volatile Duration queueTimeout;
        private volatile long pollTimeoutNanos = -1;

        @Override
        public void registerProcessor(final String processorId, final Pattern registeredPaths,
                final Set<String> registeredMethods, final int registeredQueueSize,
                final Duration registeredQueueTimeout) {
            paths = registeredPaths;
            methods = registeredMethods;
            queueSize = registeredQueueSize;
            queueTimeout = registeredQueueTimeout;
        }

        @Override
        public void unregisterProcessor(final String processorId) {
            // No registration is kept, so there is nothing to remove.
        }

        @Override
        public HttpRequestContainer pollRequest(final String processorId, final long timeout, final TimeUnit unit) {
            pollTimeoutNanos = unit.toNanos(timeout);
            return null;
        }
    }
}
