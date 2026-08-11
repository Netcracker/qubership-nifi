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

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A bounded HTTP response: status code, optional content type, and the response body bytes read
 * within the configured size limit.
 */
public final class NiFiHttpResponse {

    private final int statusCode;
    private final String contentType;
    private final byte[] body;

    /**
     * Creates a new response holder.
     *
     * @param status       the HTTP status code
     * @param mediaType    the response content type, or {@code null} when absent
     * @param bodyBytes    the response body bytes
     */
    public NiFiHttpResponse(final int status, final String mediaType, final byte[] bodyBytes) {
        this.statusCode = status;
        this.contentType = mediaType;
        this.body = bodyBytes.clone();
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the response content type, if the server supplied one.
     *
     * @return the content type
     */
    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    /**
     * Returns a copy of the response body bytes.
     *
     * @return the body bytes
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns the response body decoded as UTF-8 text.
     *
     * @return the body as text
     */
    public String bodyAsText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Reports whether the status code is in the 2xx success range.
     *
     * @return {@code true} when the status is a success
     */
    public boolean isSuccess() {
        final int successLow = 200;
        final int successHigh = 300;
        return statusCode >= successLow && statusCode < successHigh;
    }
}
