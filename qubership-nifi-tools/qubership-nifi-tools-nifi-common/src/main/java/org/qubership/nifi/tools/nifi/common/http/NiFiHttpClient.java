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

package org.qubership.nifi.tools.nifi.common.http;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.qubership.nifi.tools.nifi.common.auth.NiFiRequestAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Sends bounded HTTP requests to a single NiFi origin. It applies authentication, enforces that
 * every request and redirect stays on the resolver's origin, bounds response sizes, and retries
 * idempotent GET requests on transient failures with bounded exponential backoff. A TLS failure or
 * a response that breaches the size bound is not transient and fails on the first attempt.
 *
 * <p>The wrapped {@link CloseableHttpClient} should be created with
 * {@link #newHttpClient(SSLContext, Duration)} (or an equivalent builder that never auto-follows
 * redirects) so that redirect origin enforcement happens here rather than inside the transport.</p>
 *
 * <p>Redirects are followed for GET only. Re-issuing a POST or a DELETE against a redirect target
 * would either repeat a side effect or quietly downgrade the verb, so {@link #post} and
 * {@link #delete} hand a 3xx response back to the caller unchanged.</p>
 *
 * <p>This client takes ownership of the wrapped client: {@link #close()} shuts down the underlying
 * connection pool, so each instance must be closed when it is no longer needed.</p>
 */
public final class NiFiHttpClient implements Closeable {

    /** JSON media type used by NiFi REST endpoints. */
    public static final String APPLICATION_JSON = "application/json";

    private static final Logger LOG = LoggerFactory.getLogger(NiFiHttpClient.class);

    private static final int REDIRECT_LOW = 300;
    private static final int REDIRECT_HIGH = 400;
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);
    private static final int EXCERPT_LIMIT = 512;
    private static final long BACKOFF_MULTIPLIER = 2L;
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private final CloseableHttpClient httpClient;
    private final NiFiUriResolver resolver;
    private final NiFiRequestAuthenticator authenticator;
    private final Config config;

    /**
     * Creates a NiFi HTTP client with default transport settings.
     *
     * @param client        the underlying HTTP client (should not auto-follow redirects)
     * @param uriResolver   the resolver defining the permitted origin
     * @param requestAuth   the authenticator applied to every request
     */
    public NiFiHttpClient(final CloseableHttpClient client, final NiFiUriResolver uriResolver,
                          final NiFiRequestAuthenticator requestAuth) {
        this(client, uriResolver, requestAuth, Config.defaults());
    }

    /**
     * Creates a NiFi HTTP client with explicit transport settings.
     *
     * @param client        the underlying HTTP client (should not auto-follow redirects)
     * @param uriResolver   the resolver defining the permitted origin
     * @param requestAuth   the authenticator applied to every request
     * @param transport     the transport configuration
     */
    public NiFiHttpClient(final CloseableHttpClient client, final NiFiUriResolver uriResolver,
                          final NiFiRequestAuthenticator requestAuth, final Config transport) {
        this.httpClient = client;
        this.resolver = uriResolver;
        this.authenticator = requestAuth;
        this.config = transport;
    }

    /**
     * Builds an HTTP client suitable for use with this wrapper: a finite connect timeout, and no
     * automatic redirect, retry, cookie, or content-encoding handling.
     *
     * <p>Redirect and retry handling stay in this class. Cookie handling is off so that the only
     * {@code Cookie} header sent is the one an authenticator sets, and content encoding is declined
     * so that the response size bound applies to the bytes on the wire rather than to a decompressed
     * body.</p>
     *
     * @param sslContext     the SSL context, or {@code null} to use the JVM default trust material
     * @param connectTimeout the connection timeout
     * @return the configured HTTP client, which the caller passes to this class to own
     */
    public static CloseableHttpClient newHttpClient(final SSLContext sslContext, final Duration connectTimeout) {
        final Timeout timeout = Timeout.of(connectTimeout);
        final PoolingHttpClientConnectionManagerBuilder connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(timeout)
                                .build());
        if (sslContext != null) {
            connectionManager.setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext));
        }
        return HttpClientBuilder.create()
                .setConnectionManager(connectionManager.build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout)
                        .setRedirectsEnabled(false)
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .build();
    }

    /**
     * Sends a GET request, retrying transient failures and following only same-origin redirects.
     *
     * @param uri          the request URI, which must be on the resolver's origin
     * @param acceptHeader the value of the {@code Accept} header
     * @return the bounded response
     */
    public NiFiHttpResponse get(final URI uri, final String acceptHeader) {
        requireSameOrigin(Method.GET, uri);
        return executeWithRetry(uri, acceptHeader);
    }

    /**
     * Sends a POST request without retrying. POST is not treated as idempotent.
     *
     * @param uri          the request URI, which must be on the resolver's origin
     * @param body         the request body
     * @param contentType  the value of the {@code Content-Type} header
     * @param acceptHeader the value of the {@code Accept} header
     * @return the bounded response
     */
    public NiFiHttpResponse post(final URI uri, final String body, final String contentType,
                                 final String acceptHeader) {
        requireSameOrigin(Method.POST, uri);
        final HttpPost request = new HttpPost(uri);
        request.setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8),
                ContentType.parse(contentType)));
        return executeOnce(Method.POST, uri, prepare(request, acceptHeader)).response();
    }

    /**
     * Sends a DELETE request without retrying.
     *
     * @param uri the request URI, which must be on the resolver's origin
     * @return the bounded response
     */
    public NiFiHttpResponse delete(final URI uri) {
        requireSameOrigin(Method.DELETE, uri);
        return executeOnce(Method.DELETE, uri, prepare(new HttpDelete(uri), APPLICATION_JSON)).response();
    }

    /**
     * Closes the underlying HTTP client and its connection pool.
     */
    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
    }

    private void requireSameOrigin(final Method method, final URI uri) {
        if (!resolver.isSameOrigin(uri)) {
            throw new NiFiApiException(method.name(), redact(uri), NiFiApiException.NO_STATUS, "",
                    "Request URI is not on the permitted NiFi origin " + resolver.origin());
        }
    }

    private HttpUriRequestBase prepare(final HttpUriRequestBase request, final String acceptHeader) {
        final Timeout timeout = Timeout.of(config.requestTimeout());
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .setRedirectsEnabled(false)
                .build());
        request.setHeader(HttpHeaders.ACCEPT, acceptHeader);
        authenticator.apply(request);
        return request;
    }

    /**
     * Sends a GET request, retrying transient failures. Only GET takes this path: of the three verbs
     * this client offers it is the one that is idempotent, so replaying it cannot repeat a side effect.
     *
     * @param uri          the request URI
     * @param acceptHeader the value of the {@code Accept} header
     * @return the bounded response
     */
    private NiFiHttpResponse executeWithRetry(final URI uri, final String acceptHeader) {
        int attempt = 0;
        while (true) {
            try {
                final Exchange exchange = sendGetFollowingSameOriginRedirects(uri, acceptHeader);
                if (RETRYABLE_STATUSES.contains(exchange.response().statusCode()) && attempt < config.maxRetries()) {
                    backoff(Method.GET, uri, attempt, retryAfterMillis(exchange));
                    attempt++;
                    continue;
                }
                return exchange.response();
            } catch (final IOException e) {
                if (isTransient(e) && attempt < config.maxRetries()) {
                    LOG.debug("Retrying {} after transport failure (attempt {})", redact(uri), attempt, e);
                    backoff(Method.GET, uri, attempt, -1L);
                    attempt++;
                    continue;
                }
                throw new NiFiApiException(Method.GET.name(), redact(uri), NiFiApiException.NO_STATUS, "",
                        failureMessage(e, attempt > 0), e);
            }
        }
    }

    /**
     * Reports whether a transport failure could plausibly succeed on a later attempt. A TLS failure
     * means the peer is not trusted and an oversized body means the response is too large for this
     * client; repeating either only delays the same outcome.
     *
     * @param failure the transport failure
     * @return {@code true} when the request is worth retrying
     */
    private static boolean isTransient(final IOException failure) {
        return !(failure instanceof SSLException) && !(failure instanceof ResponseTooLargeException);
    }

    /**
     * Describes a transport failure. The prefix reports whether the request was actually retried, not
     * whether it was retryable: with retries configured off, a transient failure fails on its first
     * attempt and reporting it as retry-exhausted would send the reader looking for retries that
     * never happened.
     *
     * @param failure the transport failure
     * @param retried whether at least one retry was attempted before this failure
     * @return the message describing the failure
     */
    private static String failureMessage(final IOException failure, final boolean retried) {
        final String prefix = retried ? "Request failed after retries: " : "Request failed: ";
        return prefix + failure.getMessage();
    }

    private Exchange executeOnce(final Method method, final URI uri, final HttpUriRequestBase request) {
        try {
            return httpClient.execute(request, new BoundedResponseHandler(config.maxBodyBytes()));
        } catch (final IOException e) {
            throw new NiFiApiException(method.name(), redact(uri), NiFiApiException.NO_STATUS, "",
                    "Request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Issues a GET and follows any same-origin redirect it answers with. Redirect following is GET-only
     * by design: this client sends POST and DELETE through {@link #executeOnce}, which hands a 3xx back
     * to the caller verbatim rather than re-issuing the request against the redirect target.
     *
     * @param initialUri   the first URI to request
     * @param acceptHeader the value of the {@code Accept} header
     * @return the exchange that ended the redirect chain
     * @throws IOException on a transport failure
     */
    private Exchange sendGetFollowingSameOriginRedirects(final URI initialUri,
                                                         final String acceptHeader) throws IOException {
        URI currentUri = initialUri;
        int redirects = 0;
        while (true) {
            final Exchange exchange = httpClient.execute(prepare(new HttpGet(currentUri), acceptHeader),
                    new BoundedResponseHandler(config.maxBodyBytes()));
            final int status = exchange.response().statusCode();
            if (status >= REDIRECT_LOW && status < REDIRECT_HIGH) {
                if (exchange.location() == null) {
                    throw new NiFiApiException(Method.GET.name(), redact(currentUri), status, "",
                            "Redirect response is missing a Location header");
                }
                final URI target = currentUri.resolve(exchange.location());
                if (!resolver.isSameOrigin(target) || redirects >= config.maxRedirects()) {
                    throw new NiFiApiException(Method.GET.name(), redact(currentUri), status, "",
                            "Rejected redirect to " + redact(target));
                }
                redirects++;
                currentUri = target;
                continue;
            }
            return exchange;
        }
    }

    private static long retryAfterMillis(final Exchange exchange) {
        if (exchange.retryAfter() == null) {
            return -1L;
        }
        try {
            final long millisPerSecond = 1000L;
            return Long.parseLong(exchange.retryAfter().trim()) * millisPerSecond;
        } catch (final NumberFormatException e) {
            return -1L;
        }
    }

    private void backoff(final Method method, final URI uri, final int attempt, final long retryAfterMillis) {
        long delay = config.baseBackoff().toMillis();
        for (int i = 0; i < attempt; i++) {
            delay *= BACKOFF_MULTIPLIER;
        }
        delay = Math.min(delay, config.maxBackoff().toMillis());
        if (retryAfterMillis > 0) {
            delay = Math.min(retryAfterMillis, config.maxBackoff().toMillis());
        }
        try {
            Thread.sleep(delay);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NiFiApiException(method.name(), redact(uri), NiFiApiException.NO_STATUS, "",
                    "Request was interrupted", e);
        }
    }

    /**
     * Produces a redacted string form of a URI for diagnostics: origin and path only. A NiFi API URI
     * carries everything a reader needs in its path segments, while user-info, a query, and a
     * fragment can all carry a credential, so all three are dropped. The result is therefore safe to
     * put in a message, a log line, or a generated file.
     *
     * @param uri the URI
     * @return a redacted URI string
     */
    public static String redact(final URI uri) {
        if (uri == null) {
            return "<none>";
        }
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (uri.getScheme() == null || uri.getHost() == null) {
            // A relative or opaque URI has no origin to report; its path is all that is safe to keep.
            return path.isEmpty() ? "<unknown>" : path;
        }
        return uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path;
    }

    /**
     * Returns a bounded, single-line excerpt of a response body for diagnostics.
     *
     * @param body the response body
     * @return a bounded excerpt
     */
    public static String excerpt(final String body) {
        if (body == null) {
            return "";
        }
        final String collapsed = body.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= EXCERPT_LIMIT) {
            return collapsed;
        }
        return collapsed.substring(0, EXCERPT_LIMIT) + "...";
    }

    /**
     * The outcome of a single HTTP exchange: the bounded response, plus the two header values the
     * retry and redirect loops need after the response has been consumed and closed.
     *
     * @param response   the bounded response
     * @param location   the {@code Location} header value, or {@code null} when absent
     * @param retryAfter the {@code Retry-After} header value, or {@code null} when absent
     */
    private record Exchange(NiFiHttpResponse response, String location, String retryAfter) {
    }

    /**
     * Signals that a response body exceeded the configured size bound. It is distinct from a
     * transport failure so that the retry loop can leave it alone and the caller sees the real
     * reason rather than a retry-exhausted message.
     */
    private static final class ResponseTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        ResponseTooLargeException(final String message) {
            super(message);
        }
    }

    /**
     * A response handler that reads at most a maximum number of body bytes and fails the exchange
     * when the limit is exceeded, so an unexpected large response cannot exhaust memory.
     */
    private static final class BoundedResponseHandler implements HttpClientResponseHandler<Exchange> {

        private final int maxBytes;

        BoundedResponseHandler(final int limit) {
            this.maxBytes = limit;
        }

        @Override
        public Exchange handleResponse(final ClassicHttpResponse response) throws IOException {
            final HttpEntity entity = response.getEntity();
            byte[] body = new byte[0];
            if (entity != null) {
                try (InputStream content = entity.getContent()) {
                    body = content.readNBytes(maxBytes + 1);
                }
                if (body.length > maxBytes) {
                    throw new ResponseTooLargeException("Response body exceeds the " + maxBytes + " byte limit");
                }
            }
            return new Exchange(
                    new NiFiHttpResponse(response.getCode(), headerValue(response, HttpHeaders.CONTENT_TYPE), body),
                    headerValue(response, HttpHeaders.LOCATION),
                    headerValue(response, RETRY_AFTER_HEADER));
        }

        private static String headerValue(final ClassicHttpResponse response, final String name) {
            final Header header = response.getFirstHeader(name);
            return header == null ? null : header.getValue();
        }
    }

    /**
     * Transport configuration for {@link NiFiHttpClient}: request timeout, response size bound,
     * retry count, backoff bounds, and the same-origin redirect budget.
     *
     * @param requestTimeout the per-request timeout, applied per socket read
     * @param maxBodyBytes   the maximum response body size in bytes
     * @param maxRetries     the maximum number of retries for idempotent GET requests
     * @param baseBackoff    the base backoff delay
     * @param maxBackoff     the maximum backoff delay
     * @param maxRedirects   the maximum number of same-origin redirects to follow
     */
    public record Config(Duration requestTimeout, int maxBodyBytes, int maxRetries,
                         Duration baseBackoff, Duration maxBackoff, int maxRedirects) {

        private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
        private static final int DEFAULT_MAX_BODY_BYTES = 32 * 1024 * 1024;
        private static final int DEFAULT_MAX_RETRIES = 3;
        private static final int DEFAULT_BASE_BACKOFF_MILLIS = 500;
        private static final int DEFAULT_MAX_BACKOFF_SECONDS = 8;
        private static final int DEFAULT_MAX_REDIRECTS = 3;

        /**
         * Returns the default transport configuration.
         *
         * @return the default configuration
         */
        public static Config defaults() {
            return new Config(Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS),
                    DEFAULT_MAX_BODY_BYTES, DEFAULT_MAX_RETRIES,
                    Duration.ofMillis(DEFAULT_BASE_BACKOFF_MILLIS),
                    Duration.ofSeconds(DEFAULT_MAX_BACKOFF_SECONDS), DEFAULT_MAX_REDIRECTS);
        }
    }
}
