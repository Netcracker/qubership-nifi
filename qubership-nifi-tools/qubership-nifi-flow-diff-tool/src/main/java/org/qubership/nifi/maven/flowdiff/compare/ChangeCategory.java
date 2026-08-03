package org.qubership.nifi.maven.flowdiff.compare;

/**
 * The category every comparable leaf difference falls into. {@code IGNORED} differences are dropped before reporting;
 * {@code TECHNICAL} differences are counted but not listed and are the only ones the revert mode restores;
 * {@code ENVIRONMENTAL} and {@code SIGNIFICANT} differences are reported and never reverted.
 */
public enum ChangeCategory {

    /** Not tracked: {@code propertyDescriptors} and {@code snapshotMetadata}. */
    IGNORED("ignored"),
    /** A NiFi-generated identifier change with no functional meaning; the only reverted category. */
    TECHNICAL("technical"),
    /** Export metadata or runtime packaging, such as bundle versions; reported but never reverted. */
    ENVIRONMENTAL("environmental"),
    /** A real flow-content change; the catch-all category. */
    SIGNIFICANT("significant");

    private final String label;

    ChangeCategory(final String labelValue) {
        this.label = labelValue;
    }

    /**
     * Returns the lower-case label used in reports and JSON.
     *
     * @return the category label
     */
    public String getLabel() {
        return label;
    }
}
