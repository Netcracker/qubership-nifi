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

package org.qubership.nifi.tools.nifi.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Sends bounded HTTP requests to a single NiFi origin. It applies authentication, enforces that
 * every request and redirect stays on the resolver's origin, bounds response sizes, and retries
 * idempotent GET requests on transient failures with bounded exponential backoff.
 *
 * <p>The wrapped {@link HttpClient} should be created with {@link #newHttpClient(SSLContext, Duration)}
 * (or an equivalent builder that never auto-follows redirects) so that redirect origin enforcement
 * happens here rather than inside the JDK client.</p>
 */
public final class NiFiHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(NiFiHttpClient.class);

    private static final int REDIRECT_LOW = 300;
    private static final int REDIRECT_HIGH = 400;
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);
    private static final int EXCERPT_LIMIT = 512;
    private static final long BACKOFF_MULTIPLIER = 2L;

    private final HttpClient httpClient;
    private final NiFiUriResolver resolver;
    private final NiFiRequestAuthenticator authenticator;
    private final Config config;

    /**
     * Creates a NiFi HTTP client with default transport settings.
     *
     * @param client        the underlying JDK HTTP client (should not auto-follow redirects)
     * @param uriResolver   the resolver defining the permitted origin
     * @param requestAuth   the authenticator applied to every request
     */
    public NiFiHttpClient(final HttpClient client, final NiFiUriResolver uriResolver,
                          final NiFiRequestAuthenticator requestAuth) {
        this(client, uriResolver, requestAuth, Config.defaults());
    }

    /**
     * Creates a NiFi HTTP client with explicit transport settings.
     *
     * @param client        the underlying JDK HTTP client (should not auto-follow redirects)
     * @param uriResolver   the resolver defining the permitted origin
     * @param requestAuth   the authenticator applied to every request
     * @param transport     the transport configuration
     */
    public NiFiHttpClient(final HttpClient client, final NiFiUriResolver uriResolver,
                          final NiFiRequestAuthenticator requestAuth, final Config transport) {
        this.httpClient = client;
        this.resolver = uriResolver;
        this.authenticator = requestAuth;
        this.config = transport;
    }

    /**
     * Builds a JDK HTTP client suitable for use with this wrapper: a finite connect timeout and a
     * redirect policy that never auto-follows, so redirect origin enforcement stays in this class.
     *
     * @param sslContext     the SSL context, or {@code null} to use the JVM default
     * @param connectTimeout the connection timeout
     * @return the configured HTTP client
     */
    public static HttpClient newHttpClient(final SSLContext sslContext, final Duration connectTimeout) {
        final HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        return builder.build();
    }

    /**
     * Sends a GET request, retrying transient failures and following only same-origin redirects.
     *
     * @param uri          the request URI, which must be on the resolver's origin
     * @param acceptHeader the value of the {@code Accept} header
     * @return the bounded response
     */
    public NiFiHttpResponse get(final URI uri, final String acceptHeader) {
        requireSameOrigin(uri);
        return executeWithRetry("GET", uri, acceptHeader);
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
        requireSameOrigin(uri);
        final HttpRequest.Builder builder = baseRequest(uri, acceptHeader)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return executeOnce("POST", uri, builder);
    }

    /**
     * Sends a DELETE request without retrying.
     *
     * @param uri the request URI, which must be on the resolver's origin
     * @return the bounded response
     */
    public NiFiHttpResponse delete(final URI uri) {
        requireSameOrigin(uri);
        final HttpRequest.Builder builder = baseRequest(uri, "application/json").DELETE();
        return executeOnce("DELETE", uri, builder);
    }

    private void requireSameOrigin(final URI uri) {
        if (!resolver.isSameOrigin(uri)) {
            throw new NiFiApiException("GET", redact(uri), NiFiApiException.NO_STATUS, "",
                    "Request URI is not on the permitted NiFi origin " + resolver.origin());
        }
    }

    private HttpRequest.Builder baseRequest(final URI uri, final String acceptHeader) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.requestTimeout())
                .header("Accept", acceptHeader);
        authenticator.apply(builder);
        return builder;
    }

    private NiFiHttpResponse executeWithRetry(final String method, final URI uri, final String acceptHeader) {
        int attempt = 0;
        while (true) {
            try {
                final HttpResponse<byte[]> raw = sendRawFollowingSameOriginRedirects(method, uri, acceptHeader);
                if (RETRYABLE_STATUSES.contains(raw.statusCode()) && attempt < config.maxRetries()) {
                    backoff(attempt, retryAfterMillis(raw));
                    attempt++;
                    continue;
                }
                return toResponse(raw);
            } catch (final IOException e) {
                if (attempt < config.maxRetries()) {
                    LOG.debug("Retrying {} after transport failure (attempt {})", redact(uri), attempt, e);
                    backoff(attempt, -1L);
                    attempt++;
                    continue;
                }
                throw new NiFiApiException(method, redact(uri), NiFiApiException.NO_STATUS, "",
                        "Request failed after retries: " + e.getMessage(), e);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NiFiApiException(method, redact(uri), NiFiApiException.NO_STATUS, "",
                        "Request was interrupted", e);
            }
        }
    }

    private NiFiHttpResponse executeOnce(final String method, final URI uri, final HttpRequest.Builder builder) {
        try {
            final HttpResponse<byte[]> raw = send(builder);
            return toResponse(raw);
        } catch (final IOException e) {
            throw new NiFiApiException(method, redact(uri), NiFiApiException.NO_STATUS, "",
                    "Request failed: " + e.getMessage(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NiFiApiException(method, redact(uri), NiFiApiException.NO_STATUS, "",
                    "Request was interrupted", e);
        }
    }

    private HttpResponse<byte[]> sendRawFollowingSameOriginRedirects(final String method, final URI initialUri,
                                                                     final String acceptHeader)
            throws IOException, InterruptedException {
        URI currentUri = initialUri;
        int redirects = 0;
        while (true) {
            final HttpResponse<byte[]> raw = send(baseRequest(currentUri, acceptHeader).GET());
            final int status = raw.statusCode();
            if (status >= REDIRECT_LOW && status < REDIRECT_HIGH) {
                final Optional<String> location = raw.headers().firstValue("Location");
                if (location.isEmpty()) {
                    throw new NiFiApiException(method, redact(currentUri), status, "",
                            "Redirect response is missing a Location header");
                }
                final URI target = currentUri.resolve(location.get());
                if (!resolver.isSameOrigin(target) || redirects >= config.maxRedirects()) {
                    throw new NiFiApiException(method, redact(currentUri), status, "",
                            "Rejected redirect to " + redact(target));
                }
                redirects++;
                currentUri = target;
                continue;
            }
            return raw;
        }
    }

    private HttpResponse<byte[]> send(final HttpRequest.Builder builder) throws IOException, InterruptedException {
        return httpClient.send(builder.build(),
                responseInfo -> new BoundedBodySubscriber(config.maxBodyBytes()));
    }

    private static NiFiHttpResponse toResponse(final HttpResponse<byte[]> raw) {
        final String contentType = raw.headers().firstValue("Content-Type").orElse(null);
        return new NiFiHttpResponse(raw.statusCode(), contentType, raw.body());
    }

    private static long retryAfterMillis(final HttpResponse<byte[]> raw) {
        final Optional<String> header = raw.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return -1L;
        }
        try {
            final long millisPerSecond = 1000L;
            return Long.parseLong(header.get().trim()) * millisPerSecond;
        } catch (final NumberFormatException e) {
            return -1L;
        }
    }

    private void backoff(final int attempt, final long retryAfterMillis) {
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
        }
    }

    /**
     * Produces a redacted string form of a URI for diagnostics. The URI never carries credentials
     * by construction, but user-info is stripped defensively.
     *
     * @param uri the URI
     * @return a redacted URI string
     */
    public static String redact(final URI uri) {
        if (uri == null) {
            return "<none>";
        }
        if (uri.getUserInfo() == null) {
            return uri.toString();
        }
        return uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + uri.getRawPath();
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
     * A response body subscriber that accumulates bytes up to a maximum and cancels the exchange
     * when the limit is exceeded, so an unexpected large response cannot exhaust memory.
     */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final List<byte[]> chunks = new ArrayList<>();
        private Flow.Subscription subscription;
        private int total;

        BoundedBodySubscriber(final int limit) {
            this.maxBytes = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(final Flow.Subscription sub) {
            this.subscription = sub;
            sub.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(final List<ByteBuffer> item) {
            for (final ByteBuffer buffer : item) {
                final int remaining = buffer.remaining();
                if (total + remaining > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IOException("Response body exceeds the " + maxBytes + " byte limit"));
                    return;
                }
                final byte[] copy = new byte[remaining];
                buffer.get(copy);
                chunks.add(copy);
                total += remaining;
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            final byte[] out = new byte[total];
            int position = 0;
            for (final byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, out, position, chunk.length);
                position += chunk.length;
            }
            result.complete(out);
        }
    }

    /**
     * Transport configuration for {@link NiFiHttpClient}: request timeout, response size bound,
     * retry count, backoff bounds, and the same-origin redirect budget.
     *
     * @param requestTimeout the per-request timeout
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
