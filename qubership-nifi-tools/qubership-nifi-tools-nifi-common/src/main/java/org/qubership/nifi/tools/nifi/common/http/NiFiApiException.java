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

/**
 * Signals a failed NiFi REST API interaction. Carries the request method, a redacted request URI,
 * the HTTP status code (or {@code 0} when the request never produced a response), and a bounded,
 * sanitized excerpt of the response body. Instances never contain authentication headers or secrets.
 *
 * <p>{@link #getMessage()} names the failed request and, when the server sent one, the response
 * excerpt. A build walks thousands of component endpoints, so a reason and a status code on their own
 * would not tell the reader which request failed; every consumer that reports the message therefore
 * reports the request too.</p>
 */
public class NiFiApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Status value used when a request failed before any HTTP response was received. */
    public static final int NO_STATUS = 0;

    private final String method;
    private final String redactedUri;
    private final int statusCode;
    private final String responseExcerpt;

    /**
     * Creates a new exception describing a failed NiFi API interaction.
     *
     * @param requestMethod  the HTTP method of the failed request
     * @param uri            the redacted request URI (must not contain credentials)
     * @param status         the HTTP status code, or {@link #NO_STATUS} when no response arrived
     * @param excerpt        a bounded, sanitized excerpt of the response body (may be empty)
     * @param reason         a human-readable description of the failure, which the message extends
     *                       with the request and the response excerpt
     */
    public NiFiApiException(final String requestMethod, final String uri, final int status,
                            final String excerpt, final String reason) {
        super(describe(requestMethod, uri, excerpt, reason));
        this.method = requestMethod;
        this.redactedUri = uri;
        this.statusCode = status;
        this.responseExcerpt = excerpt;
    }

    /**
     * Creates a new exception describing a request that failed with an underlying cause.
     *
     * @param requestMethod  the HTTP method of the failed request
     * @param uri            the redacted request URI (must not contain credentials)
     * @param status         the HTTP status code, or {@link #NO_STATUS} when no response arrived
     * @param excerpt        a bounded, sanitized excerpt of the response body (may be empty)
     * @param reason         a human-readable description of the failure, which the message extends
     *                       with the request and the response excerpt
     * @param cause          the underlying cause
     */
    public NiFiApiException(final String requestMethod, final String uri, final int status,
                            final String excerpt, final String reason, final Throwable cause) {
        super(describe(requestMethod, uri, excerpt, reason), cause);
        this.method = requestMethod;
        this.redactedUri = uri;
        this.statusCode = status;
        this.responseExcerpt = excerpt;
    }

    /**
     * Builds the exception message: the reason, the failed request, and the response excerpt when the
     * server sent a body. The URI is already redacted to origin and path by
     * {@link NiFiHttpClient#redact(java.net.URI)}, and the excerpt is bounded and collapsed to a
     * single line, so neither can turn the message into a secret leak or a log flood.
     *
     * @param requestMethod the HTTP method of the failed request
     * @param uri           the redacted request URI
     * @param excerpt       the sanitized response excerpt, which may be empty
     * @param reason        the description of the failure
     * @return the composed message
     */
    private static String describe(final String requestMethod, final String uri, final String excerpt,
                                   final String reason) {
        final StringBuilder message = new StringBuilder(reason == null ? "" : reason.strip());
        if (!message.isEmpty() && message.charAt(message.length() - 1) != '.') {
            message.append('.');
        }
        message.append(" Failed request: ").append(requestMethod).append(' ').append(uri).append('.');
        if (excerpt != null && !excerpt.isBlank()) {
            message.append(" Response body: ").append(excerpt);
        }
        return message.toString();
    }

    /**
     * Returns the HTTP method of the failed request.
     *
     * @return the request method
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns the redacted request URI.
     *
     * @return the redacted URI
     */
    public String getRedactedUri() {
        return redactedUri;
    }

    /**
     * Returns the HTTP status code, or {@link #NO_STATUS} when no response was received.
     *
     * @return the status code
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the bounded, sanitized response excerpt.
     *
     * @return the response excerpt
     */
    public String getResponseExcerpt() {
        return responseExcerpt;
    }
}
