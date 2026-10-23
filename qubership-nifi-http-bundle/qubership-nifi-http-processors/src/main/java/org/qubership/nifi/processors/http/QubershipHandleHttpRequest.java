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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.http.HttpContextMap;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.qubership.nifi.service.http.HttpRequestContainer;
import org.qubership.nifi.service.http.HttpRouterService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

/**
 * Creates a FlowFile for each HTTP request that an HttpRouterService routes to this processor.
 *
 * <p>When scheduled, the processor registers its path pattern and allowed methods on the router service, and on each
 * trigger it takes one request from the queue the service fills for it. The body of the request becomes the FlowFile
 * content, and the request details become attributes with the same names that HandleHttpRequest writes. A
 * multipart/form-data request produces one FlowFile per part.
 *
 * <p>Each request is registered on the HTTP Context Map under the identifier written to http.context.identifier,
 * so that HandleHttpResponse can answer it. The processor answers a request itself only when it cannot pass the
 * request on: with 400 when the body cannot be read, when the Content-Type header declares a charset that the
 * JVM does not support, when the query string contains a malformed percent-encoded sequence, when Parameters to
 * Attributes is set and Jetty cannot decode a query parameter, such as %C3 that is not complete UTF-8, or when a
 * multipart/form-data request has no parts; with 503 when the HTTP Context Map refuses the registration; and with
 * 500 when an unexpected exception is thrown before the request is registered. When stopped, the processor
 * unregisters, and the router service answers the requests still waiting in its queue with 503.
 *
 * <p>A request waits in the queue of the router service no longer than Request Queue Timeout; the service answers a
 * request that waits longer with 503. The Request Expiration of the HTTP Context Map starts when the processor takes
 * the request from the queue.
 *
 * <p>Query parameters are decoded with the character encoding of the request, or with UTF-8 when the request
 * declares none.
 */
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@Tags({"http", "https", "request", "listen", "ingress", "web service", "router"})
@CapabilityDescription("Receives the HTTP requests that an HTTP Router Service routes to this processor and creates "
        + "a FlowFile for each request, or for each part of a multipart/form-data request. Several processors, "
        + "each with its own paths, share the port of one router service. Use it together with HandleHttpResponse.")
@WritesAttributes({
    @WritesAttribute(attribute = QubershipHandleHttpRequest.HTTP_CONTEXT_ID, description = "The identifier under "
            + "which the request is registered on the HTTP Context Map. HandleHttpResponse uses it to answer "
            + "the request"),
    @WritesAttribute(attribute = "mime.type", description = "The MIME type of the data, taken from the "
            + "Content-Type HTTP header"),
    @WritesAttribute(attribute = "http.servlet.path", description = "The part of the request URL that is "
            + "considered the servlet path"),
    @WritesAttribute(attribute = "http.context.path", description = "The part of the request URL that is "
            + "considered the context path"),
    @WritesAttribute(attribute = "http.method", description = "The HTTP method of the request, such as GET or POST"),
    @WritesAttribute(attribute = "http.local.addr", description = "The IP address of the server interface that "
            + "received the request"),
    @WritesAttribute(attribute = QubershipHandleHttpRequest.HTTP_LOCAL_NAME, description = "The IP address or "
            + "hostname of the server"),
    @WritesAttribute(attribute = QubershipHandleHttpRequest.HTTP_PORT, description = "The listening port of the "
            + "server"),
    @WritesAttribute(attribute = "http.query.string", description = "The decoded query string of the request URL"),
    @WritesAttribute(attribute = QubershipHandleHttpRequest.HTTP_REMOTE_HOST, description = "The hostname of the "
            + "client"),
    @WritesAttribute(attribute = "http.remote.addr", description = "The IP address of the client"),
    @WritesAttribute(attribute = "http.remote.user", description = "The username of the client"),
    @WritesAttribute(attribute = "http.protocol", description = "The protocol of the request, such as HTTP/1.1"),
    @WritesAttribute(attribute = QubershipHandleHttpRequest.HTTP_REQUEST_URI, description = "The request URI"),
    @WritesAttribute(attribute = "http.request.url", description = "The full request URL"),
    @WritesAttribute(attribute = "http.auth.type", description = "The type of HTTP authorization used"),
    @WritesAttribute(attribute = "http.dispatcher.type", description = "The dispatcher type of the request"),
    @WritesAttribute(attribute = "http.character.encoding", description = "The character encoding of the request "
            + "body"),
    @WritesAttribute(attribute = "http.locale", description = "The preferred locale of the client"),
    @WritesAttribute(attribute = "http.server.name", description = "The host name of the server the request was "
            + "sent to"),
    @WritesAttribute(attribute = "http.principal.name", description = "The name of the authenticated user making "
            + "the request"),
    @WritesAttribute(attribute = "http.query.param.XXX", description = "Each query parameter of the request, "
            + "prefixed with \"http.query.param.\""),
    @WritesAttribute(attribute = "http.param.XXX", description = "Each query parameter or multipart/form-data "
            + "field listed in Parameters to Attributes, prefixed with \"http.param.\""),
    @WritesAttribute(attribute = "http.cookie.XXX.value", description = "The value of each cookie of the request. "
            + "The domain, path, max.age, and secure suffixes carry the other cookie fields"),
    @WritesAttribute(attribute = "http.headers.XXX", description = "Each HTTP header of the request, prefixed with "
            + "\"http.headers.\". For example, the header x-my-header is written to http.headers.x-my-header"),
    @WritesAttribute(attribute = "http.headers.multipart.XXX", description = "Each HTTP header of a multipart "
            + "part, prefixed with \"http.headers.multipart.\""),
    @WritesAttribute(attribute = "http.multipart.size", description = "For a multipart/form-data request, the "
            + "content size of the part"),
    @WritesAttribute(attribute = "http.multipart.content.type", description = "For a multipart/form-data request, "
            + "the content type of the part"),
    @WritesAttribute(attribute = "http.multipart.name", description = "For a multipart/form-data request, the name "
            + "of the part"),
    @WritesAttribute(attribute = "http.multipart.filename", description = "For a multipart/form-data request, the "
            + "name of the uploaded file of the part. The file is stored temporarily in the directory that the "
            + "java.io.tmpdir system property names"),
    @WritesAttribute(attribute = "http.multipart.fragments.sequence.number", description = "For a "
            + "multipart/form-data request, the index of the part, starting with 1"),
    @WritesAttribute(attribute = "http.multipart.fragments.total.number", description = "For a "
            + "multipart/form-data request, the number of parts")})
public final class QubershipHandleHttpRequest extends AbstractProcessor {

    /**
     * Attribute with the identifier under which the request is registered on the HTTP Context Map.
     */
    static final String HTTP_CONTEXT_ID = "http.context.identifier";

    /**
     * Attribute with the request URI.
     */
    static final String HTTP_REQUEST_URI = "http.request.uri";

    /**
     * Attribute with the hostname of the client.
     */
    static final String HTTP_REMOTE_HOST = "http.remote.host";

    /**
     * Attribute with the IP address or hostname of the server.
     */
    static final String HTTP_LOCAL_NAME = "http.local.name";

    /**
     * Attribute with the listening port of the server.
     */
    static final String HTTP_PORT = "http.server.port";

    private static final String MIME_TYPE_MULTIPART_FORM_DATA = "multipart/form-data";
    private static final long POLL_TIMEOUT_MILLIS = 2;
    private static final Pattern LIST_DELIMITER = Pattern.compile(",");
    private static final Pattern URL_QUERY_PARAM_DELIMITER = Pattern.compile("&");

    /**
     * HTTP Router Service property descriptor.
     */
    public static final PropertyDescriptor HTTP_ROUTER_SERVICE = new PropertyDescriptor.Builder()
            .name("http-router-service")
            .displayName("HTTP Router Service")
            .description("The controller service the processor registers on")
            .required(true)
            .identifiesControllerService(HttpRouterService.class)
            .build();

    /**
     * Request Path property descriptor.
     */
    public static final PropertyDescriptor REQUEST_PATH = new PropertyDescriptor.Builder()
            .name("request-path")
            .displayName("Request Path")
            .description("A regular expression that specifies the HTTP paths this processor handles. It is matched "
                    + "against the decoded path with dot segments resolved, the value written to http.servlet.path, "
                    + "so /public/../admin is matched as /admin. Example: /orders(/.*)?")
            .required(true)
            .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
            .build();

    /**
     * HTTP Context Map property descriptor.
     */
    public static final PropertyDescriptor HTTP_CONTEXT_MAP = new PropertyDescriptor.Builder()
            .name("http-context-map")
            .displayName("HTTP Context Map")
            .description("The HTTP Context Map controller service that caches the request until the flow answers it")
            .required(true)
            .identifiesControllerService(HttpContextMap.class)
            .build();

    /**
     * Container Queue Size property descriptor.
     */
    public static final PropertyDescriptor CONTAINER_QUEUE_SIZE = new PropertyDescriptor.Builder()
            .name("container-queue-size")
            .displayName("Container Queue Size")
            .description("The size of the queue the service fills with the requests routed to this processor")
            .required(true)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .defaultValue("50")
            .build();

    /**
     * Request Queue Timeout property descriptor.
     */
    public static final PropertyDescriptor REQUEST_QUEUE_TIMEOUT = new PropertyDescriptor.Builder()
            .name("request-queue-timeout")
            .displayName("Request Queue Timeout")
            .description("The maximum time a request waits in the queue of this processor. The router service answers "
                    + "a request that waits longer with 503 Service Unavailable. The Request Expiration of the HTTP "
                    + "Context Map starts when the processor takes the request from the queue. The minimum is 1 ms")
            .required(true)
            .addValidator(StandardValidators.createTimePeriodValidator(
                    1, TimeUnit.MILLISECONDS, Long.MAX_VALUE, TimeUnit.NANOSECONDS))
            .defaultValue("30 sec")
            .build();

    /**
     * Allow GET property descriptor.
     */
    public static final PropertyDescriptor ALLOW_GET = allowMethod("GET", true);

    /**
     * Allow POST property descriptor.
     */
    public static final PropertyDescriptor ALLOW_POST = allowMethod("POST", true);

    /**
     * Allow PUT property descriptor.
     */
    public static final PropertyDescriptor ALLOW_PUT = allowMethod("PUT", true);

    /**
     * Allow DELETE property descriptor.
     */
    public static final PropertyDescriptor ALLOW_DELETE = allowMethod("DELETE", true);

    /**
     * Allow HEAD property descriptor.
     */
    public static final PropertyDescriptor ALLOW_HEAD = allowMethod("HEAD", false);

    /**
     * Allow OPTIONS property descriptor.
     */
    public static final PropertyDescriptor ALLOW_OPTIONS = allowMethod("OPTIONS", false);

    /**
     * Additional HTTP Methods property descriptor.
     */
    public static final PropertyDescriptor ADDITIONAL_METHODS = new PropertyDescriptor.Builder()
            .name("additional-http-methods")
            .displayName("Additional HTTP Methods")
            .description("A comma-separated list of non-standard HTTP methods the processor handles, in addition to "
                    + "the methods the Allow properties enable")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Parameters to Attributes property descriptor.
     */
    public static final PropertyDescriptor PARAMETERS_TO_ATTRIBUTES = new PropertyDescriptor.Builder()
            .name("parameters-to-attributes")
            .displayName("Parameters to Attributes")
            .description("A comma-separated list of query parameters or multipart/form-data fields to output as "
                    + "attributes. Fields of an application/x-www-form-urlencoded body are not output, because "
                    + "the body is written to the FlowFile content")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Multipart Request Max Size property descriptor.
     */
    public static final PropertyDescriptor MULTIPART_REQUEST_MAX_SIZE = new PropertyDescriptor.Builder()
            .name("multipart-request-max-size")
            .displayName("Multipart Request Max Size")
            .description("The maximum size of a request. Applies only to requests with Content-Type: "
                    + MIME_TYPE_MULTIPART_FORM_DATA)
            .required(true)
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .defaultValue("1 MB")
            .build();

    /**
     * Multipart Read Buffer Size property descriptor.
     */
    public static final PropertyDescriptor MULTIPART_READ_BUFFER_SIZE = new PropertyDescriptor.Builder()
            .name("multipart-read-buffer-size")
            .displayName("Multipart Read Buffer Size")
            .description("The size at which the content of an incoming file is written to disk. Applies only to "
                    + "requests with Content-Type: " + MIME_TYPE_MULTIPART_FORM_DATA)
            .required(true)
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .defaultValue("512 KB")
            .build();

    /**
     * Relationship for the FlowFiles created from the requests.
     */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("A FlowFile with the request body and the request attributes")
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS = List.of(
            HTTP_ROUTER_SERVICE,
            REQUEST_PATH,
            HTTP_CONTEXT_MAP,
            CONTAINER_QUEUE_SIZE,
            REQUEST_QUEUE_TIMEOUT,
            ALLOW_GET,
            ALLOW_POST,
            ALLOW_PUT,
            ALLOW_DELETE,
            ALLOW_HEAD,
            ALLOW_OPTIONS,
            ADDITIONAL_METHODS,
            PARAMETERS_TO_ATTRIBUTES,
            MULTIPART_REQUEST_MAX_SIZE,
            MULTIPART_READ_BUFFER_SIZE
    );

    private static final Set<Relationship> RELATIONSHIPS = Set.of(REL_SUCCESS);

    private volatile HttpRouterService routerService;
    private volatile HttpContextMap contextMap;
    private volatile Set<String> parametersToAttributes;
    private volatile MultipartConfigElement multipartConfig;

    private static PropertyDescriptor allowMethod(final String method, final boolean allowedByDefault) {
        return new PropertyDescriptor.Builder()
                .name("allow-" + method.toLowerCase(Locale.ROOT))
                .displayName("Allow " + method)
                .description("Whether the processor handles " + method + " requests on its paths")
                .required(true)
                .allowableValues("true", "false")
                .defaultValue(Boolean.toString(allowedByDefault))
                .build();
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    /**
     * Registers the path pattern and the allowed methods of this processor on the router service.
     *
     * <p>The request queue gets the size of Container Queue Size and the queue timeout of Request Queue Timeout.
     *
     * @param context configuration of the processor
     * @throws IllegalStateException if the router service refuses the registration, for example because another
     *                               processor already registered the same path pattern
     */
    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        final HttpRouterService router =
                context.getProperty(HTTP_ROUTER_SERVICE).asControllerService(HttpRouterService.class);
        final HttpContextMap httpContextMap =
                context.getProperty(HTTP_CONTEXT_MAP).asControllerService(HttpContextMap.class);
        final Pattern paths = Pattern.compile(context.getProperty(REQUEST_PATH).getValue());
        final int queueSize = context.getProperty(CONTAINER_QUEUE_SIZE).asInteger();
        final long requestMaxSize = context.getProperty(MULTIPART_REQUEST_MAX_SIZE).asDataSize(DataUnit.B).longValue();
        final int readBufferSize = context.getProperty(MULTIPART_READ_BUFFER_SIZE).asDataSize(DataUnit.B).intValue();

        this.contextMap = httpContextMap;
        this.parametersToAttributes = splitList(context.getProperty(PARAMETERS_TO_ATTRIBUTES).getValue());
        this.multipartConfig = new MultipartConfigElement(System.getProperty("java.io.tmpdir"), requestMaxSize,
                requestMaxSize, readBufferSize);

        final Duration queueTimeout =
                Duration.ofMillis(context.getProperty(REQUEST_QUEUE_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS));
        router.registerProcessor(getIdentifier(), paths, getAllowedMethods(context), queueSize, queueTimeout);
        this.routerService = router;
    }

    /**
     * Unregisters this processor from the router service, which answers the requests still waiting in the queue of
     * the processor with 503.
     *
     * <p>NiFi calls this method after the last onTrigger call has returned, so no onTrigger call polls the queue
     * after the registration is removed.
     */
    @OnStopped
    public void onStopped() {
        final HttpRouterService router = this.routerService;
        if (router != null) {
            router.unregisterProcessor(getIdentifier());
            this.routerService = null;
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final HttpRequestContainer container =
                routerService.pollRequest(getIdentifier(), POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        if (container == null) {
            return;
        }

        final long start = System.nanoTime();
        final String contextIdentifier = UUID.randomUUID().toString();
        try {
            handleContainer(session, container, contextIdentifier, start);
        } catch (final RuntimeException e) {
            if (contextMap.getResponse(contextIdentifier) == null) {
                sendError(SC_INTERNAL_SERVER_ERROR, "Request Processing Failed", container);
            }
            throw e;
        }
    }

    private void handleContainer(final ProcessSession session, final HttpRequestContainer container,
            final String contextIdentifier, final long start) {
        final HttpServletRequest request = container.request();
        final String contentType;
        try {
            contentType = request.getContentType();
        } catch (final IllegalArgumentException e) {
            rejectBadRequest(container, "Unsupported Character Encoding", e);
            return;
        }
        if (contentType != null && contentType.contains(MIME_TYPE_MULTIPART_FORM_DATA)) {
            handleMultipartRequest(session, container, contextIdentifier, start);
        } else {
            handleRequest(session, container, contextIdentifier, start);
        }
    }

    private void handleRequest(final ProcessSession session, final HttpRequestContainer container,
            final String contextIdentifier, final long start) {
        final HttpServletRequest request = container.request();
        FlowFile flowFile = session.create();
        try (OutputStream flowFileOut = session.write(flowFile)) {
            request.getInputStream().transferTo(flowFileOut);
        } catch (final IOException e) {
            session.remove(flowFile);
            handleStreamingError(container, e);
            return;
        }
        final Map<String, String> requestAttributes;
        try {
            requestAttributes = getRequestAttributes(request, contextIdentifier);
        } catch (final IllegalArgumentException e) {
            session.remove(flowFile);
            rejectBadRequest(container, "Malformed Query String", e);
            return;
        }
        flowFile = session.putAllAttributes(flowFile, requestAttributes);
        if (registerRequest(container, contextIdentifier)) {
            forwardFlowFile(session, start, request, flowFile);
        } else {
            session.remove(flowFile);
        }
    }

    private void handleMultipartRequest(final ProcessSession session, final HttpRequestContainer container,
            final String contextIdentifier, final long start) {
        final HttpServletRequest request = container.request();
        request.setAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT, multipartConfig);
        List<Part> parts = null;
        final List<FlowFile> flowFiles = new ArrayList<>();
        try {
            parts = List.copyOf(request.getParts());
            if (parts.isEmpty()) {
                sendError(SC_BAD_REQUEST, "Multipart Request Has No Parts", container);
                return;
            }
            final Map<String, String> requestAttributes = getRequestAttributes(request, contextIdentifier);
            for (int i = 0; i < parts.size(); i++) {
                final Part part = parts.get(i);
                FlowFile flowFile = session.create();
                flowFiles.add(flowFile);
                try (InputStream partIn = part.getInputStream(); OutputStream flowFileOut = session.write(flowFile)) {
                    partIn.transferTo(flowFileOut);
                }
                flowFile = savePartAttributes(session, part, flowFile, i, parts.size());
                flowFile = session.putAllAttributes(flowFile, requestAttributes);
                flowFiles.set(i, flowFile);
            }
        } catch (final IOException | ServletException | IllegalStateException e) {
            session.remove(flowFiles);
            handleStreamingError(container, e);
            return;
        } catch (final IllegalArgumentException e) {
            session.remove(flowFiles);
            rejectBadRequest(container, "Malformed Query String", e);
            return;
        } finally {
            deleteParts(parts);
        }

        if (!registerRequest(container, contextIdentifier)) {
            session.remove(flowFiles);
            return;
        }
        for (final FlowFile flowFile : flowFiles) {
            forwardFlowFile(session, start, request, flowFile);
        }
    }

    private void deleteParts(final List<Part> parts) {
        if (parts == null) {
            return;
        }
        for (final Part part : parts) {
            try {
                part.delete();
            } catch (final Exception e) {
                getLogger().error("Couldn't delete underlying storage for {}", part, e);
            }
        }
    }

    private Set<String> getAllowedMethods(final ProcessContext context) {
        final Set<String> methods = new LinkedHashSet<>();
        addMethodIfAllowed(context, ALLOW_GET, "GET", methods);
        addMethodIfAllowed(context, ALLOW_POST, "POST", methods);
        addMethodIfAllowed(context, ALLOW_PUT, "PUT", methods);
        addMethodIfAllowed(context, ALLOW_DELETE, "DELETE", methods);
        addMethodIfAllowed(context, ALLOW_HEAD, "HEAD", methods);
        addMethodIfAllowed(context, ALLOW_OPTIONS, "OPTIONS", methods);
        for (final String method : splitList(context.getProperty(ADDITIONAL_METHODS).getValue())) {
            methods.add(method.toUpperCase(Locale.ROOT));
        }
        return methods;
    }

    private static void addMethodIfAllowed(final ProcessContext context, final PropertyDescriptor property,
            final String method, final Set<String> methods) {
        if (context.getProperty(property).asBoolean()) {
            methods.add(method);
        }
    }

    private static Set<String> splitList(final String value) {
        if (value == null) {
            return Set.of();
        }
        return Arrays.stream(LIST_DELIMITER.split(value))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private FlowFile savePartAttributes(final ProcessSession session, final Part part, final FlowFile flowFile,
            final int index, final int partCount) {
        final Map<String, String> attributes = new HashMap<>();
        for (final String headerName : part.getHeaderNames()) {
            putAttribute(attributes, "http.headers.multipart." + headerName, part.getHeader(headerName));
        }
        putAttribute(attributes, "http.multipart.size", part.getSize());
        putAttribute(attributes, "http.multipart.content.type", part.getContentType());
        putAttribute(attributes, "http.multipart.name", part.getName());
        putAttribute(attributes, "http.multipart.filename", part.getSubmittedFileName());
        putAttribute(attributes, "http.multipart.fragments.sequence.number", index + 1);
        putAttribute(attributes, "http.multipart.fragments.total.number", partCount);
        return session.putAllAttributes(flowFile, attributes);
    }

    /**
     * Returns the attributes that describe the request itself, the same for every FlowFile created from it.
     *
     * @param request the request to describe
     * @param contextIdentifier identifier under which the request is registered on the HTTP Context Map
     * @return the attributes, keyed by attribute name
     * @throws IllegalArgumentException if the query string contains a malformed percent-encoded sequence, or if
     *                                  Parameters to Attributes is set and Jetty cannot decode a request parameter
     */
    private Map<String, String> getRequestAttributes(final HttpServletRequest request,
            final String contextIdentifier) {
        final Charset charset = getCharset(request);
        final Map<String, String> attributes = new HashMap<>();
        putAttribute(attributes, HTTP_CONTEXT_ID, contextIdentifier);
        putAttribute(attributes, "mime.type", request.getContentType());
        putAttribute(attributes, "http.servlet.path", request.getServletPath());
        putAttribute(attributes, "http.context.path", request.getContextPath());
        putAttribute(attributes, "http.method", request.getMethod());
        putAttribute(attributes, "http.local.addr", request.getLocalAddr());
        putAttribute(attributes, HTTP_LOCAL_NAME, request.getLocalName());
        final String queryString = request.getQueryString();
        if (queryString != null) {
            putAttribute(attributes, "http.query.string", URLDecoder.decode(queryString, charset));
        }
        putAttribute(attributes, HTTP_REMOTE_HOST, request.getRemoteHost());
        putAttribute(attributes, "http.remote.addr", request.getRemoteAddr());
        putAttribute(attributes, "http.remote.user", request.getRemoteUser());
        putAttribute(attributes, "http.protocol", request.getProtocol());
        putAttribute(attributes, HTTP_REQUEST_URI, request.getRequestURI());
        putAttribute(attributes, "http.request.url", request.getRequestURL().toString());
        putAttribute(attributes, "http.auth.type", request.getAuthType());
        final DispatcherType dispatcherType = request.getDispatcherType();
        if (dispatcherType != null) {
            putAttribute(attributes, "http.dispatcher.type", dispatcherType.name());
        }
        putAttribute(attributes, "http.character.encoding", request.getCharacterEncoding());
        putAttribute(attributes, "http.locale", request.getLocale());
        putAttribute(attributes, "http.server.name", request.getServerName());
        putAttribute(attributes, HTTP_PORT, request.getServerPort());

        putParameterAttributes(attributes, request);
        putCookieAttributes(attributes, request);
        if (queryString != null) {
            putQueryParamAttributes(attributes, queryString, charset);
        }

        final Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            final String headerName = headerNames.nextElement();
            putAttribute(attributes, "http.headers." + headerName, request.getHeader(headerName));
        }

        final Principal principal = request.getUserPrincipal();
        if (principal != null) {
            putAttribute(attributes, "http.principal.name", principal.getName());
        }
        return attributes;
    }

    private void putParameterAttributes(final Map<String, String> attributes, final HttpServletRequest request) {
        final Set<String> parameterNames = this.parametersToAttributes;
        if (parameterNames.isEmpty()) {
            return;
        }
        final Enumeration<String> paramEnumeration;
        try {
            paramEnumeration = request.getParameterNames();
        } catch (final IllegalStateException e) {
            // Jetty reports a parameter it cannot decode, such as %C3 that is not complete UTF-8, this way.
            throw new IllegalArgumentException("Cannot decode the request parameters", e);
        }
        while (paramEnumeration.hasMoreElements()) {
            final String paramName = paramEnumeration.nextElement();
            if (parameterNames.contains(paramName)) {
                putAttribute(attributes, "http.param." + paramName, request.getParameter(paramName));
            }
        }
    }

    private static void putCookieAttributes(final Map<String, String> attributes, final HttpServletRequest request) {
        final Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return;
        }
        for (final Cookie cookie : cookies) {
            final String cookiePrefix = "http.cookie." + cookie.getName() + ".";
            putAttribute(attributes, cookiePrefix + "value", cookie.getValue());
            putAttribute(attributes, cookiePrefix + "domain", cookie.getDomain());
            putAttribute(attributes, cookiePrefix + "path", cookie.getPath());
            putAttribute(attributes, cookiePrefix + "max.age", cookie.getMaxAge());
            putAttribute(attributes, cookiePrefix + "secure", cookie.getSecure());
        }
    }

    private static void putQueryParamAttributes(final Map<String, String> attributes, final String queryString,
            final Charset charset) {
        for (final String keyValueString : URL_QUERY_PARAM_DELIMITER.split(queryString)) {
            final int indexOf = keyValueString.indexOf('=');
            if (indexOf < 0) {
                attributes.put("http.query.param." + URLDecoder.decode(keyValueString, charset), "");
            } else {
                final String key = keyValueString.substring(0, indexOf);
                final String value = keyValueString.substring(indexOf + 1);
                attributes.put("http.query.param." + URLDecoder.decode(key, charset),
                        URLDecoder.decode(value, charset));
            }
        }
    }

    private static Charset getCharset(final HttpServletRequest request) {
        final String encoding = request.getCharacterEncoding();
        return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    }

    private boolean registerRequest(final HttpRequestContainer container, final String contextIdentifier) {
        if (contextMap.register(contextIdentifier, container.request(), container.response(), container.context())) {
            return true;
        }
        sendError(SC_SERVICE_UNAVAILABLE, "Request Registration Failed", container);
        return false;
    }

    private void forwardFlowFile(final ProcessSession session, final long start, final HttpServletRequest request,
            final FlowFile flowFile) {
        final long receiveMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        session.getProvenanceReporter().receive(flowFile, request.getRequestURL().toString(),
                "Received from " + request.getRemoteAddr(), receiveMillis);
        session.transfer(flowFile, REL_SUCCESS);
        getLogger().debug("Transferred {} to [{}] Remote Address [{}]", flowFile, REL_SUCCESS,
                request.getRemoteAddr());
    }

    private void handleStreamingError(final HttpRequestContainer container, final Exception e) {
        rejectBadRequest(container, "Stream Processing Failed", e);
    }

    private void rejectBadRequest(final HttpRequestContainer container, final String reason, final Exception e) {
        final HttpServletRequest request = container.request();
        getLogger().error("{}: Method [{}] URI [{}] Address [{}]",
                reason, request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), e);
        sendError(SC_BAD_REQUEST, reason, container);
    }

    private void sendError(final int statusCode, final String message, final HttpRequestContainer container) {
        final HttpServletRequest request = container.request();
        final String method = request.getMethod();
        final String uri = request.getRequestURI();
        final String remoteAddr = request.getRemoteAddr();
        try {
            container.response().sendError(statusCode, message);
            getLogger().warn("Send Error Completed: HTTP {} [{}] Method [{}] URI [{}] Address [{}]",
                    statusCode, message, method, uri, remoteAddr);
        } catch (final Exception e) {
            getLogger().error("Send Error Failed: HTTP {} [{}] Method [{}] URI [{}] Address [{}]",
                    statusCode, message, method, uri, remoteAddr, e);
        }
        try {
            container.context().complete();
        } catch (final RuntimeException e) {
            getLogger().error("Complete Request Failed: Method [{}] URI [{}] Address [{}]",
                    method, uri, remoteAddr, e);
        }
    }

    private static void putAttribute(final Map<String, String> map, final String key, final Object value) {
        if (value != null) {
            map.put(key, value.toString());
        }
    }
}
