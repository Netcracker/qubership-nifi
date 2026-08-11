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

package org.qubership.nifi.tools.kb.model;

import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * The canonical identity of a component: its kind, bundle coordinates, and fully qualified type.
 * The fully qualified type alone is not unique because different bundle versions can expose the
 * same class name, so the full tuple is used for correlation, uniqueness checks, directory naming,
 * and index generation.
 */
public final class ComponentIdentity {

    private static final int HASH_PREFIX_LENGTH = 12;
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int BYTE_MASK = 0xFF;
    private static final int NIBBLE_MASK = 0x0F;
    private static final int HIGH_NIBBLE_SHIFT = 4;

    private final NiFiComponentKind kind;
    private final String group;
    private final String artifact;
    private final String version;
    private final String type;

    /**
     * Creates a new component identity.
     *
     * @param componentKind the component kind
     * @param bundleGroup   the bundle group
     * @param bundleArtifact the bundle artifact
     * @param bundleVersion the bundle version
     * @param fullyQualifiedType the fully qualified type
     */
    public ComponentIdentity(final NiFiComponentKind componentKind, final String bundleGroup,
                             final String bundleArtifact, final String bundleVersion,
                             final String fullyQualifiedType) {
        this.kind = componentKind;
        this.group = bundleGroup;
        this.artifact = bundleArtifact;
        this.version = bundleVersion;
        this.type = fullyQualifiedType;
    }

    /**
     * Returns the component kind.
     *
     * @return the kind
     */
    public NiFiComponentKind getKind() {
        return kind;
    }

    /**
     * Returns the bundle group.
     *
     * @return the group
     */
    public String getGroup() {
        return group;
    }

    /**
     * Returns the bundle artifact.
     *
     * @return the artifact
     */
    public String getArtifact() {
        return artifact;
    }

    /**
     * Returns the bundle version.
     *
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the fully qualified type.
     *
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the simple (unqualified) class name of the type.
     *
     * @return the simple name
     */
    public String simpleName() {
        final int lastDot = type.lastIndexOf('.');
        return lastDot >= 0 ? type.substring(lastDot + 1) : type;
    }

    /**
     * Returns the unambiguous canonical string of this identity, with newline-separated fields.
     *
     * @return the canonical string
     */
    public String canonical() {
        return kind.name() + '\n' + group + '\n' + artifact + '\n' + version + '\n' + type;
    }

    /**
     * Returns the first 12 lowercase hexadecimal characters of the SHA-256 digest of the canonical
     * identity.
     *
     * @return the identity hash, always 12 characters
     */
    public String identityHash() {
        final byte[] digest = sha256(canonical().getBytes(StandardCharsets.UTF_8));
        final StringBuilder sb = new StringBuilder(HASH_PREFIX_LENGTH);
        for (int i = 0; i < digest.length && sb.length() < HASH_PREFIX_LENGTH; i++) {
            final int value = digest[i] & BYTE_MASK;
            sb.append(HEX_DIGITS[(value >> HIGH_NIBBLE_SHIFT) & NIBBLE_MASK]);
            sb.append(HEX_DIGITS[value & NIBBLE_MASK]);
        }
        return sb.substring(0, HASH_PREFIX_LENGTH);
    }

    /**
     * Returns the readable, collision-safe directory name for this component: the simple class name
     * followed by a hyphen and the identity hash. Any character outside {@code [A-Za-z0-9._-]} is
     * replaced with an underscore, and a name that sanitizes to nothing becomes {@code component}.
     *
     * @return the directory name
     */
    public String directoryName() {
        return sanitize(simpleName()) + '-' + identityHash();
    }

    private static String sanitize(final String value) {
        final StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            final boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            sb.append(allowed ? c : '_');
        }
        return sb.length() == 0 ? "component" : sb.toString();
    }

    private static byte[] sha256(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ComponentIdentity)) {
            return false;
        }
        final ComponentIdentity other = (ComponentIdentity) obj;
        return kind == other.kind && group.equals(other.group) && artifact.equals(other.artifact)
                && version.equals(other.version) && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, group, artifact, version, type);
    }

    @Override
    public String toString() {
        return kind.name() + ' ' + group + ':' + artifact + ':' + version + ':' + type;
    }
}
