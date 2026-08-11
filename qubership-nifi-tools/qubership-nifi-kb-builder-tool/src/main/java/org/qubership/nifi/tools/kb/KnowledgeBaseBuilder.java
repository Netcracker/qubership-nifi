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

package org.qubership.nifi.tools.kb;

import org.qubership.nifi.tools.kb.cli.BuilderConfig;
import org.qubership.nifi.tools.kb.collect.ComponentCollector;
import org.qubership.nifi.tools.kb.collect.UnsupportedTargetException;
import org.qubership.nifi.tools.kb.docs.GuideCollector;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.kb.model.GuideMode;
import org.qubership.nifi.tools.kb.model.GuidesResult;
import org.qubership.nifi.tools.kb.model.KnowledgeBase;
import org.qubership.nifi.tools.kb.model.KnowledgeBaseProvenance;
import org.qubership.nifi.tools.kb.output.KnowledgeBaseValidator;
import org.qubership.nifi.tools.kb.output.KnowledgeBaseWriter;
import org.qubership.nifi.tools.kb.output.OutputReplacer;
import org.qubership.nifi.tools.kb.output.SecretScanner;
import org.qubership.nifi.tools.nifi.common.api.NiFiAboutClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiVersion;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Runs the end-to-end Knowledge Base build: resolve transport and authentication, gate the NiFi
 * version, collect the lossless component catalog and (optionally) the guides, render into a staging
 * directory, validate, scan for secrets, and replace the destination only after success.
 */
public final class KnowledgeBaseBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseBuilder.class);
    private static final NiFiVersion MIN_VERSION = NiFiVersion.of(2, 5, 0);
    private static final NiFiVersion MAX_VERSION = NiFiVersion.of(3, 0, 0);

    private final BuilderVersion builderVersion;
    private final TransportFactory transportFactory;
    private final ComponentCollector componentCollector;
    private final GuideCollector guideCollector;
    private final KnowledgeBaseWriter writer;
    private final KnowledgeBaseValidator validator;
    private final OutputReplacer replacer;

    /**
     * Creates the builder.
     *
     * @param version           the builder identity recorded in the provenance
     * @param transport         the transport factory
     * @param components        the component catalog collector
     * @param guides            the guide collector
     * @param knowledgeBaseWriter the staging writer
     * @param knowledgeBaseValidator the staging validator
     * @param outputReplacer    the output replacer
     */
    public KnowledgeBaseBuilder(final BuilderVersion version,
                                final TransportFactory transport,
                                final ComponentCollector components,
                                final GuideCollector guides,
                                final KnowledgeBaseWriter knowledgeBaseWriter,
                                final KnowledgeBaseValidator knowledgeBaseValidator,
                                final OutputReplacer outputReplacer) {
        this.builderVersion = version;
        this.transportFactory = transport;
        this.componentCollector = components;
        this.guideCollector = guides;
        this.writer = knowledgeBaseWriter;
        this.validator = knowledgeBaseValidator;
        this.replacer = outputReplacer;
    }

    /**
     * Executes the build.
     *
     * @param config the resolved configuration for this run
     */
    public void run(final BuilderConfig config) {
        final KnowledgeBase kb;
        try (NiFiRestClient rest = transportFactory.create(config)) {
            final String nifiVersion = gateVersion(new NiFiAboutClient(rest, config.resolver()));

            final List<ComponentRecord> components =
                    componentCollector.collectAll(new NiFiComponentCatalogClient(rest, config.resolver()));
            final GuideMode guideMode = config.skipGuides() ? GuideMode.SKIP : GuideMode.REQUIRED;
            final GuidesResult guides =
                    guideCollector.collect(rest.httpClient(), config.resolver(), guideMode, nifiVersion);

            kb = assemble(config, nifiVersion, components, guides);
        }
        writeAndReplace(config, kb);
        LOG.info("Knowledge Base written to {}", config.outputDir());
    }

    private String gateVersion(final NiFiAboutClient aboutClient) {
        final String rawVersion = aboutClient.readVersionString();
        final NiFiVersion version = NiFiVersion.parse(rawVersion)
                .orElseThrow(() -> new UnsupportedTargetException(
                        "NiFi version could not be parsed from the about endpoint: '" + rawVersion + "'"));
        if (!version.isWithin(MIN_VERSION, MAX_VERSION)) {
            throw new UnsupportedTargetException("NiFi version " + version.getRaw()
                    + " is outside the supported interval [2.5.0, 3.0.0)");
        }
        LOG.info("Target NiFi version {} is supported", version.getRaw());
        return version.getRaw();
    }

    private KnowledgeBase assemble(final BuilderConfig config, final String nifiVersion,
                                   final List<ComponentRecord> components, final GuidesResult guides) {
        final KnowledgeBaseProvenance provenance = new KnowledgeBaseProvenance(
                builderVersion.name(), builderVersion.version(), Instant.now(), nifiVersion,
                MIN_VERSION.getRaw(), config.resolver().baseUrl());
        return new KnowledgeBase(provenance, components, guides);
    }

    private void writeAndReplace(final BuilderConfig config, final KnowledgeBase kb) {
        final Path staging = replacer.createStaging(config.outputDir());
        try {
            writer.writeTo(staging, kb);
            validator.validate(staging);
            SecretScanner.scan(staging, config.secretsForScan());
            replacer.replace(config.outputDir(), staging);
        } catch (final RuntimeException e) {
            replacer.deleteRecursively(staging);
            throw e;
        }
    }
}
