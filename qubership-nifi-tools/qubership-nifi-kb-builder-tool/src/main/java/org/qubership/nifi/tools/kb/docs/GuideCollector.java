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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.qubership.nifi.tools.kb.model.GuideDocument;
import org.qubership.nifi.tools.kb.model.GuideMode;
import org.qubership.nifi.tools.kb.model.GuideType;
import org.qubership.nifi.tools.kb.model.GuidesResult;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpResponse;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects the three guides from the target NiFi instance, converts them to Markdown, and extracts
 * the required Developer's Guide sections. A guide-content sentinel check runs before parsing so a
 * single-page-app shell fails as "not a guide at this path" rather than obscurely during extraction.
 * When guides are skipped, no request is made and no processing runs.
 */
public final class GuideCollector {

    private static final Logger LOG = LoggerFactory.getLogger(GuideCollector.class);
    private static final String ACCEPT_HTML = "text/html";
    private static final String LF = "\n";

    private final HtmlToMarkdown htmlToMarkdown;
    private final DeveloperGuideExtractor developerGuideExtractor;

    /**
     * Creates a new guide collector.
     *
     * @param markdownConverter the HTML to Markdown converter
     * @param guideExtractor    the Developer's Guide section extractor
     */
    public GuideCollector(final HtmlToMarkdown markdownConverter, final DeveloperGuideExtractor guideExtractor) {
        this.htmlToMarkdown = markdownConverter;
        this.developerGuideExtractor = guideExtractor;
    }

    /**
     * Collects the guides for the given mode.
     *
     * @param httpClient  the HTTP client bound to the current run
     * @param resolver    the URI resolver bound to the current run
     * @param mode        the guide mode
     * @param nifiVersion the detected NiFi version, used in the generated provenance header
     * @return the guides result, empty in skip mode
     * @throws GuideException when a guide cannot be fetched, fails the sentinel check, or is missing
     *                        a required section
     */
    public GuidesResult collect(final NiFiHttpClient httpClient, final NiFiUriResolver resolver,
                                final GuideMode mode, final String nifiVersion) {
        if (mode == GuideMode.SKIP) {
            return new GuidesResult(GuideMode.SKIP, List.of());
        }
        final List<GuideDocument> documents = new ArrayList<>();
        for (final GuideType type : GuideType.values()) {
            documents.add(collectOne(httpClient, resolver, type, nifiVersion));
        }
        return new GuidesResult(GuideMode.REQUIRED, documents);
    }

    private GuideDocument collectOne(final NiFiHttpClient httpClient, final NiFiUriResolver resolver,
                                     final GuideType type, final String nifiVersion) {
        final URI uri = resolver.resolve(type.getSourcePath());
        LOG.info("Collecting guide {}", type.getTitle());
        final NiFiHttpResponse response = httpClient.get(uri, ACCEPT_HTML);
        if (!response.isSuccess()) {
            throw new GuideException("Guide " + type.getTitle() + " is not accessible (status "
                    + response.statusCode() + ")");
        }
        final String contentType = response.contentType().orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).contains("html")) {
            throw new GuideException("Guide " + type.getTitle() + " returned non-HTML content type: "
                    + contentType);
        }
        final Document document = Jsoup.parse(response.bodyAsText(), uri.toString());
        assertSentinel(type, document);

        final Element content = selectContent(document);
        final String bodyMarkdown = htmlToMarkdown.convert(content);

        final String sourceUrl = NiFiHttpClient.redact(uri);
        if (type == GuideType.DEVELOPER) {
            final DeveloperGuideExtractor.ExtractResult extracted = developerGuideExtractor.extract(bodyMarkdown);
            return new GuideDocument(type, header(type, nifiVersion) + extracted.markdown(),
                    sourceUrl, contentType, extracted.selectedHeadings());
        }
        return new GuideDocument(type, header(type, nifiVersion) + bodyMarkdown, sourceUrl, contentType, List.of());
    }

    private void assertSentinel(final GuideType type, final Document document) {
        final String text = document.text().toLowerCase(Locale.ROOT);
        if (!text.contains(type.getSentinelKeyword().toLowerCase(Locale.ROOT))) {
            throw new GuideException("Response at " + type.getSourcePath()
                    + " does not look like the " + type.getTitle() + " (missing expected content)");
        }
    }

    private Element selectContent(final Document document) {
        document.select("script, style, nav, header, footer, #toc, .toc, #banner, #footer").remove();
        final Element content = document.selectFirst("#content");
        return content != null ? content : document.body();
    }

    private String header(final GuideType type, final String nifiVersion) {
        return "# " + type.getTitle() + LF + LF
                + "- NiFi version: " + nifiVersion + LF
                + "- Source path: " + type.getSourcePath() + LF
                + "- Converted from the target NiFi instance." + LF + LF;
    }
}
