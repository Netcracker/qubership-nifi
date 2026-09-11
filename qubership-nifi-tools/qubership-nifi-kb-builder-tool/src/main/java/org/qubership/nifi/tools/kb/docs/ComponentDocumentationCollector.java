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

package org.qubership.nifi.tools.kb.docs;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.qubership.nifi.tools.kb.collect.CollectionException;
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentReference;
import org.qubership.nifi.tools.nifi.common.http.NiFiApiException;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpResponse;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads required NiFi 1.x component pages without crawling their linked assets. */
public final class ComponentDocumentationCollector {
    private static final int NOT_FOUND = 404;
    private final NiFiHttpClient http;
    private final NiFiUriResolver resolver;
    private final HtmlToMarkdown converter = new HtmlToMarkdown();
    private final Map<NiFiComponentReference, Page> cache = new HashMap<>();

    /**
     * Binds component documentation collection to the target transport and deployment.
     *
     * @param client the authenticated client
     * @param uriResolver the deployment URI resolver
     */
    public ComponentDocumentationCollector(final NiFiHttpClient client, final NiFiUriResolver uriResolver) {
        http = client;
        resolver = uriResolver;
    }

    /**
     * Collects static metadata for the requested component.
     *
     * @param reference the exact component and bundle identity
     * @return the collected metadata
     */
    public Page collect(final NiFiComponentReference reference) {
        return cache.computeIfAbsent(reference, this::read);
    }

    private Page read(final NiFiComponentReference reference) {
        URI uri = resolver.resolveCoordinatePath("/nifi-docs/components", reference.group(), reference.artifact(),
                reference.version(), reference.type(), "index.html");
        URI subtree = uri.resolve(".");
        NiFiHttpResponse response = http.get(uri, "text/html", target -> within(subtree, target));
        requireSuccess(uri, response);
        Document document = parse(uri, response);
        Element heading = document.selectFirst("h1");
        String simpleName = reference.type().substring(reference.type().lastIndexOf('.') + 1);
        boolean properties = document.select("h2,h3").stream()
                .anyMatch(element -> element.text().trim().equals("Properties:"));
        if (heading == null || !heading.text().equals(simpleName) || !properties) {
            throw invalid(uri, "missing component identity or Properties heading");
        }
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        table(document, metadata, "reads-attributes", "readsAttributes", List.of("name", "description"));
        table(document, metadata, "writes-attributes", "writesAttributes", List.of("name", "description"));
        table(document, metadata, "dynamic-properties", "dynamicProperties", List.of("name", "value", "description"));
        table(document, metadata, "stateful", "stateManagement", List.of("scope", "description"));
        table(document, metadata, "system-resource-considerations", "systemResourceConsiderations",
                List.of("resource", "description"));
        AdditionalDocumentationState state = AdditionalDocumentationState.notAdvertised();
        String additional = null;
        String additionalPath = null;
        Element link = document.select("a[href]").stream()
                .filter(element -> element.text().toLowerCase(Locale.ROOT).startsWith("additional details"))
                .findFirst().orElse(null);
        if (link != null) {
            URI target;
            try {
                target = uri.resolve(link.attr("href")).normalize();
            } catch (IllegalArgumentException failure) {
                throw invalid(uri, "invalid additional documentation link");
            }
            if (!within(subtree, target)) {
                throw invalid(uri, "additional documentation link leaves the component subtree");
            }
            additionalPath = target.getRawPath();
            NiFiHttpResponse details = http.get(target, "text/html", redirect -> within(subtree, redirect));
            state = AdditionalDocumentationState.advertisedUnavailable();
            if (details.statusCode() != NOT_FOUND) {
                requireSuccess(target, details);
                if (!details.bodyAsText().isBlank()) {
                    Document detailDocument = parse(target, details);
                    additional = converter.convert(detailDocument.body());
                    if (additional.isBlank()) {
                        throw invalid(target, "empty parsed additional documentation");
                    }
                    state = AdditionalDocumentationState.advertisedAvailable();
                }
            }
        }
        String markdown = converter.convert(document.body());
        return new Page(uri.getRawPath(), markdown, metadata, state, additional, additionalPath);
    }

    private boolean within(final URI subtree, final URI target) {
        return resolver.isSameOrigin(target) && target.getUserInfo() == null && target.getRawQuery() == null
                && target.getRawFragment() == null && target.normalize().getRawPath().startsWith(subtree.getRawPath())
                && !target.getPath().contains("\\")
                && Arrays.stream(target.getPath().split("/"))
                        .noneMatch(segment -> segment.equals("..") || segment.equals("."));
    }

    private static void requireSuccess(final URI uri, final NiFiHttpResponse response) {
        if (!response.isSuccess()) {
            throw new NiFiApiException("GET", NiFiHttpClient.redact(uri), response.statusCode(), "",
                    "Component documentation request failed at " + uri.getRawPath()
                            + "; check proxy routing for /nifi-docs/components/ (status "
                                    + response.statusCode() + ")");
        }
    }

    private static Document parse(final URI uri, final NiFiHttpResponse response) {
        String html = response.bodyAsText();
        if (!response.contentType().orElse("").toLowerCase(Locale.ROOT).contains("html")
                || !html.toLowerCase(Locale.ROOT).contains("<html")) {
            throw invalid(uri, "expected an HTML document");
        }
        Document document = Jsoup.parse(html, uri.toString());
        document.select("script,style,nav,header,footer").remove();
        // A component page may carry an interactive form; only a credential field or a login target
        // marks the sign-in page a proxy serves in its place.
        if (document.body().text().isBlank() || document.selectFirst(
                "input[type=password], form[action*=login], form[action*=access]") != null) {
            throw invalid(uri, "empty content or authentication page");
        }
        return document;
    }

    private static CollectionException invalid(final URI uri, final String reason) {
        return new CollectionException("Invalid component documentation at " + uri.getRawPath() + ": " + reason
                + "; check proxy routing for /nifi-docs/components/");
    }

    private static void table(final Document document, final ObjectNode result, final String id,
                              final String field, final List<String> columns) {
        Element table = document.getElementById(id);
        if (table == null) {
            return;
        }
        var rows = result.putArray(field);
        for (Element row : table.select("tr")) {
            var cells = row.select("td");
            if (cells.isEmpty()) {
                continue;
            }
            if (cells.size() != columns.size()) {
                throw new CollectionException("Malformed component documentation table " + id);
            }
            ObjectNode target = rows.addObject();
            for (int i = 0; i < columns.size(); i++) {
                target.put(columns.get(i), cells.get(i).text());
            }
        }
    }

    public record Page(String sourcePath, String markdown, ObjectNode metadata,
                       AdditionalDocumentationState additionalState, String additionalMarkdown,
                       String additionalSourcePath) { }
}
