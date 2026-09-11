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
import org.qubership.nifi.tools.kb.cli.AuthMode;
import org.qubership.nifi.tools.kb.cli.ConfigurationException;
import org.qubership.nifi.tools.kb.docs.ComponentDocumentationCollector;
import org.qubership.nifi.tools.nifi.common.api.NiFiTemporaryComponentSession;
import org.qubership.nifi.tools.nifi.common.api.NiFi1xComponentMetadataProvider;
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
import org.qubership.nifi.tools.nifi.common.api.NiFiCleanupException;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiVersion;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runs the end-to-end Knowledge Base build: resolve transport and authentication, gate the NiFi
 * version, collect the lossless component catalog and (optionally) the guides, render into a staging
 * directory, validate, scan for secrets, and replace the destination only after success.
 */
public final class KnowledgeBaseBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseBuilder.class);
    private static final NiFiVersion LEGACY_MIN_VERSION = NiFiVersion.of(1, 26, 0);
    private static final NiFiVersion LEGACY_MAX_VERSION = NiFiVersion.of(2, 0, 0);
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
     * @param version                the builder identity recorded in the provenance
     * @param transport              the transport factory
     * @param components             the component catalog collector
     * @param guides                 the guide collector
     * @param knowledgeBaseWriter    the staging writer
     * @param knowledgeBaseValidator the staging validator
     * @param outputReplacer         the output replacer
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
     * Executes the build. The destination is replaced only once the staged output has been written,
     * validated, and scanned for secrets, so a failed run leaves any previous Knowledge Base intact
     * and removes its own staging directory.
     *
     * <p>Every failure is unchecked and propagates to the caller, which is expected to turn it into
     * an exit code through
     * {@link org.qubership.nifi.tools.kb.cli.FailureClassifier FailureClassifier}.
     *
     * @param config the resolved configuration for this run
     * @throws UnsupportedTargetException for unsupported NiFi versions
     * @throws NiFiCleanupException when temporary components on a NiFi 1.x target cannot be removed;
     *         a collection failure that preceded it is kept as a suppressed exception
     */
    public void run(final BuilderConfig config) {
        final KnowledgeBase kb;
        try (NiFiRestClient rest = transportFactory.create(config)) {
            final NiFiVersion version = gateVersion(new NiFiAboutClient(rest, config.resolver()));
            final String nifiVersion = version.getRaw();

            boolean legacy = version.isNiFi1x();
            validateBackend(config, legacy);
            LOG.info("Preflight: reading component catalog and required documentation");
            var catalog = new NiFiComponentCatalogClient(rest, config.resolver());
            final GuideMode guideMode = config.skipGuides() ? GuideMode.SKIP : GuideMode.REQUIRED;
            final List<ComponentRecord> components;
            final GuidesResult guides;
            if (legacy) {
                var entries = componentCollector.preflight(catalog);
                var documentation = new ComponentDocumentationCollector(rest.httpClient(), config.resolver());
                if (!entries.isEmpty()) {
                    documentation.collect(entries.getFirst().reference());
                }
                guides = guideCollector.collect(rest.httpClient(), config.resolver(), guideMode, nifiVersion);
                LOG.info("Collecting NiFi 1.x metadata through temporary components");
                try (var session = new NiFiTemporaryComponentSession(rest, config.resolver(), null)) {
                    components = componentCollector.collectAll(entries,
                            new NiFi1xComponentMetadataProvider(session), documentation);
                } catch (final RuntimeException failure) {
                    throw preferCleanupFailure(failure);
                }
            } else {
                components = componentCollector.collectAll(catalog);
                guides = guideCollector.collect(rest.httpClient(), config.resolver(), guideMode, nifiVersion);
            }

            kb = assemble(config, version, components, guides);
        }
        writeAndReplace(config, kb);
        LOG.info("Knowledge Base written to {}", config.outputDir());
    }

    /**
     * Returns the failure a NiFi 1.x collection reports. A cleanup failure may leave owned resources on
     * the target, so it takes precedence over the collection failure it may arrive behind, as a cause or
     * a suppressed exception.
     *
     * @param failure the failure the temporary session block raised
     * @return {@code failure} when it is a {@link NiFiCleanupException} or carries none; otherwise a new
     *         {@link NiFiCleanupException} that names the cleanup failure and the collection failure,
     *         has the cleanup failure as its cause, and has {@code failure} as a suppressed exception
     */
    private static RuntimeException preferCleanupFailure(final RuntimeException failure) {
        if (failure instanceof NiFiCleanupException) {
            return failure;
        }
        final NiFiCleanupException cleanup = findCleanupFailure(failure);
        if (cleanup == null) {
            return failure;
        }
        LOG.error("Temporary component cleanup failed after collection failed: {}", cleanup.getMessage());
        final NiFiCleanupException reported = new NiFiCleanupException(cleanup.getMessage()
                + "; collection had already failed: "
                + Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getName()), cleanup);
        reported.addSuppressed(failure);
        return reported;
    }

    private static NiFiCleanupException findCleanupFailure(final Throwable failure) {
        final Deque<Throwable> pending = new ArrayDeque<>(List.of(failure));
        final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            final Throwable current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof NiFiCleanupException cleanup) {
                return cleanup;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            pending.addAll(Arrays.asList(current.getSuppressed()));
        }
        return null;
    }

    private void validateBackend(final BuilderConfig config, final boolean legacy) {
        if (!legacy) {
            if (config.allowTemporaryComponents()) {
                LOG.info("Ignoring --allow-temporary-components on NiFi 2.x; collection is GET-only");
            }
            return;
        }
        if (!config.allowTemporaryComponents()) {
            throw new ConfigurationException("NiFi 1.x requires --allow-temporary-components "
                    + "on a disposable instance");
        }
        if (config.authMode() == AuthMode.COOKIE) {
            throw new ConfigurationException("NiFi 1.x temporary components do not support cookie authentication; "
                    + "use certificate or token authentication");
        }
    }

    private NiFiVersion gateVersion(final NiFiAboutClient aboutClient) {
        final String rawVersion = aboutClient.readVersionString();
        final NiFiVersion version = NiFiVersion.parse(rawVersion)
                .orElseThrow(() -> new UnsupportedTargetException(
                        "NiFi version could not be parsed from the about endpoint: '" + rawVersion + "'"));
        if (!version.isWithin(MIN_VERSION, MAX_VERSION)
                && !version.isWithin(LEGACY_MIN_VERSION, LEGACY_MAX_VERSION)) {
            throw new UnsupportedTargetException("NiFi version " + version.getRaw()
                    + " is outside the supported intervals [1.26.0, 2.0.0) and [2.5.0, 3.0.0)."
                    + " The NiFi 2.x definition/documentation contract starts at 2.5.0");
        }
        LOG.info("Target NiFi version {} is supported", version.getRaw());
        return version;
    }

    private KnowledgeBase assemble(final BuilderConfig config, final NiFiVersion nifiVersion,
                                   final List<ComponentRecord> components, final GuidesResult guides) {
        final KnowledgeBaseProvenance provenance = new KnowledgeBaseProvenance(
                builderVersion.name(), builderVersion.version(), Instant.now(), nifiVersion.getRaw(),
                (nifiVersion.isNiFi1x() ? LEGACY_MIN_VERSION : MIN_VERSION).getRaw(), config.resolver().baseUrl());
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
