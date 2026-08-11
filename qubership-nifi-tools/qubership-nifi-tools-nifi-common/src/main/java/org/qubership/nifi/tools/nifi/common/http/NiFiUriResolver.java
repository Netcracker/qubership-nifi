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

import org.apache.hc.core5.net.URIBuilder;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Normalizes a NiFi deployment or UI URL and safely resolves API and documentation paths against
 * the normalized deployment base.
 *
 * <p>Normalization strips a trailing {@code /nifi} or {@code /nifi-api} segment so that both the
 * deployment base and the browser UI URL resolve to the same base. It rejects URLs that carry user
 * information, a query, or a fragment, and (when required) URLs that are not HTTPS. Every resolved
 * request URL retains the normalized base's scheme, host, port, and path prefix.</p>
 *
 * <p>Bundle coordinates and fully qualified type names are appended as individual path segments and
 * percent-encoded, so a separator or a space inside a coordinate cannot escape its segment.</p>
 */
public final class NiFiUriResolver {

    private static final String NIFI_API_SUFFIX = "/nifi-api";
    private static final String NIFI_SUFFIX = "/nifi";
    private static final String SEGMENT = "/";

    private final String scheme;
    private final String host;
    private final int port;
    private final String baseUrl;

    private NiFiUriResolver(final String uriScheme, final String uriHost, final int uriPort,
                            final String base) {
        this.scheme = uriScheme;
        this.host = uriHost;
        this.port = uriPort;
        this.baseUrl = base;
    }

    /**
     * Normalizes the given NiFi URL, requiring HTTPS.
     *
     * @param nifiUrl the deployment or UI URL
     * @return a resolver bound to the normalized deployment base
     * @throws IllegalArgumentException when the URL is malformed, not HTTPS, or carries user
     *                                  information, a query, or a fragment
     */
    public static NiFiUriResolver fromBaseUrl(final String nifiUrl) {
        return fromBaseUrl(nifiUrl, true);
    }

    /**
     * Normalizes the given NiFi URL.
     *
     * @param nifiUrl      the deployment or UI URL
     * @param requireHttps whether to reject non-HTTPS schemes
     * @return a resolver bound to the normalized deployment base
     * @throws IllegalArgumentException when the URL is malformed or violates the structural rules
     */
    public static NiFiUriResolver fromBaseUrl(final String nifiUrl, final boolean requireHttps) {
        if (nifiUrl == null || nifiUrl.isBlank()) {
            throw new IllegalArgumentException("NiFi URL must not be blank");
        }
        final URI uri;
        try {
            uri = new URI(nifiUrl.trim());
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("NiFi URL is not a valid URI: " + e.getReason(), e);
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new IllegalArgumentException("NiFi URL must be absolute with a scheme");
        }
        final String uriScheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (requireHttps && !"https".equals(uriScheme)) {
            throw new IllegalArgumentException("NiFi URL must use HTTPS");
        }
        if (!"https".equals(uriScheme) && !"http".equals(uriScheme)) {
            throw new IllegalArgumentException("NiFi URL scheme must be http or https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("NiFi URL must not contain user information");
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("NiFi URL must not contain a query");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("NiFi URL must not contain a fragment");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("NiFi URL must contain a host");
        }

        final String normalizedPrefix = stripNifiSuffix(trimTrailingSlash(uri.getPath()));
        final StringBuilder base = new StringBuilder();
        base.append(uriScheme).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            base.append(':').append(uri.getPort());
        }
        base.append(normalizedPrefix);
        return new NiFiUriResolver(uriScheme, uri.getHost(), uri.getPort(), base.toString());
    }

    private static String trimTrailingSlash(final String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String result = path;
        while (result.length() > 1 && result.endsWith(SEGMENT)) {
            result = result.substring(0, result.length() - 1);
        }
        return SEGMENT.equals(result) ? "" : result;
    }

    private static String stripNifiSuffix(final String path) {
        if (path.endsWith(NIFI_API_SUFFIX)) {
            return path.substring(0, path.length() - NIFI_API_SUFFIX.length());
        }
        if (path.endsWith(NIFI_SUFFIX)) {
            return path.substring(0, path.length() - NIFI_SUFFIX.length());
        }
        return path;
    }

    /**
     * Returns the normalized deployment base URL, without a trailing slash.
     *
     * @return the normalized base URL
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Returns the origin (scheme, host, and port) of the normalized base URL.
     *
     * @return the origin string
     */
    public String origin() {
        final StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        return sb.toString();
    }

    /**
     * Resolves an absolute-path API or documentation resource (for example {@code /nifi-api/flow/about})
     * against the normalized deployment base.
     *
     * @param absolutePath the resource path, which must start with {@code /}
     * @return the resolved absolute URI
     */
    public URI resolve(final String absolutePath) {
        if (absolutePath == null || !absolutePath.startsWith(SEGMENT)) {
            throw new IllegalArgumentException("Resource path must start with '/'");
        }
        return URI.create(baseUrl + absolutePath);
    }

    /**
     * Resolves a component definition URI, encoding each coordinate and the type as a separate
     * RFC 3986 path segment.
     *
     * @param kind     the component kind whose definition prefix is used
     * @param group    the bundle group
     * @param artifact the bundle artifact
     * @param version  the bundle version
     * @param type     the fully qualified type
     * @return the resolved absolute URI
     */
    public URI resolveDefinition(final NiFiComponentKind kind, final String group, final String artifact,
                                 final String version, final String type) {
        return resolveCoordinatePath(kind.getDefinitionPathPrefix(), group, artifact, version, type);
    }

    /**
     * Resolves a component additional-details URI, encoding each coordinate and the type as a
     * separate RFC 3986 path segment.
     *
     * @param kind     the component kind whose additional-details prefix is used
     * @param group    the bundle group
     * @param artifact the bundle artifact
     * @param version  the bundle version
     * @param type     the fully qualified type
     * @return the resolved absolute URI
     */
    public URI resolveAdditionalDetails(final NiFiComponentKind kind, final String group, final String artifact,
                                        final String version, final String type) {
        return resolveCoordinatePath(kind.getAdditionalDetailsPathPrefix(), group, artifact, version, type);
    }

    private URI resolveCoordinatePath(final String prefix, final String group, final String artifact,
                                      final String version, final String type) {
        try {
            return new URIBuilder(resolve(prefix))
                    .appendPathSegments(group, artifact, version, type)
                    .build();
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Component URI could not be built: " + e.getReason(), e);
        }
    }

    /**
     * Reports whether the given URI shares this resolver's origin (scheme, host, and port).
     *
     * @param uri the URI to test
     * @return {@code true} when the URI has the same origin
     */
    public boolean isSameOrigin(final URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        final boolean sameScheme = scheme.equalsIgnoreCase(uri.getScheme());
        final boolean sameHost = host.equalsIgnoreCase(uri.getHost());
        final boolean samePort = port == uri.getPort();
        return sameScheme && sameHost && samePort;
    }

}
