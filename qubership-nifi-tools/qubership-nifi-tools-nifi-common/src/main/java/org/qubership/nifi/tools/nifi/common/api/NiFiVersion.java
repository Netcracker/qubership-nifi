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

package org.qubership.nifi.tools.nifi.common.api;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed NiFi version. Holds the numeric {@code major.minor.patch} tuple and the original raw
 * string. Any suffix after the patch number (for example {@code -SNAPSHOT} or a qualifier) is
 * retained in {@link #getRaw()} for provenance but does not affect numeric comparison.
 *
 * <p>Comparison is performed on the numeric tuple only, so {@code 2.10.0} is greater than
 * {@code 2.9.0} even though a lexicographic string comparison would disagree.</p>
 */
public final class NiFiVersion implements Comparable<NiFiVersion> {

    private static final Pattern LEADING_TUPLE =
            Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\.(\\d+)(.*)$");

    private static final int RADIX_BASE = 31;

    private final int major;
    private final int minor;
    private final int patch;
    private final String raw;

    private NiFiVersion(final int majorPart, final int minorPart, final int patchPart, final String rawText) {
        this.major = majorPart;
        this.minor = minorPart;
        this.patch = patchPart;
        this.raw = rawText;
    }

    /**
     * Parses a version string that starts with a numeric {@code major.minor.patch} tuple.
     *
     * @param text the raw version string
     * @return the parsed version, or an empty optional when the leading numeric tuple is missing
     *         or malformed
     */
    public static Optional<NiFiVersion> parse(final String text) {
        if (text == null) {
            return Optional.empty();
        }
        final Matcher matcher = LEADING_TUPLE.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            final int majorPart = Integer.parseInt(matcher.group(1));
            final int minorPart = Integer.parseInt(matcher.group(2));
            final int patchPart = Integer.parseInt(matcher.group(3));
            return Optional.of(new NiFiVersion(majorPart, minorPart, patchPart, text.trim()));
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the major version number.
     *
     * @return the major number
     */
    public int getMajor() {
        return major;
    }

    /**
     * Returns the minor version number.
     *
     * @return the minor number
     */
    public int getMinor() {
        return minor;
    }

    /**
     * Returns the patch version number.
     *
     * @return the patch number
     */
    public int getPatch() {
        return patch;
    }

    /**
     * Returns the original raw version string, including any suffix after the patch number.
     *
     * @return the raw version string
     */
    public String getRaw() {
        return raw;
    }

    /**
     * Reports whether this version is greater than or equal to the given lower bound (inclusive)
     * and strictly less than the given upper bound (exclusive), comparing numeric tuples only.
     *
     * @param lowerInclusive the inclusive lower bound
     * @param upperExclusive the exclusive upper bound
     * @return {@code true} when this version is within the half-open interval
     */
    public boolean isWithin(final NiFiVersion lowerInclusive, final NiFiVersion upperExclusive) {
        return this.compareTo(lowerInclusive) >= 0 && this.compareTo(upperExclusive) < 0;
    }

    /**
     * Creates a version from an explicit numeric tuple, using the canonical dotted string as its raw form.
     *
     * @param majorPart the major number
     * @param minorPart the minor number
     * @param patchPart the patch number
     * @return the version
     */
    public static NiFiVersion of(final int majorPart, final int minorPart, final int patchPart) {
        return new NiFiVersion(majorPart, minorPart, patchPart,
                majorPart + "." + minorPart + "." + patchPart);
    }

    @Override
    public int compareTo(final NiFiVersion other) {
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof NiFiVersion)) {
            return false;
        }
        final NiFiVersion other = (NiFiVersion) obj;
        return major == other.major && minor == other.minor && patch == other.patch;
    }

    @Override
    public int hashCode() {
        return (major * RADIX_BASE + minor) * RADIX_BASE + patch;
    }

    @Override
    public String toString() {
        return raw;
    }
}
