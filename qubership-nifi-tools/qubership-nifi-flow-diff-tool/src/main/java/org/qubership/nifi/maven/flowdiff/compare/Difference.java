package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;

import java.util.List;

/**
 * One reported difference: an in-place leaf field change, or a whole added or removed component or section. A
 * difference is self-contained for the flat JSON report (canonical {@code path}, {@code pathSegments}, category, raw
 * values, and identity fields) and additionally carries grouping metadata (the containing-group breadcrumb, the
 * component's own path segment, and the field path) so the text and Markdown renderers can present a grouped tree
 * without re-deriving it. Instances are built through {@link Builder}.
 */
public final class Difference {

    private final ChangeCategory category;
    private final String change;
    private final List<String> pathSegments;
    private final String path;
    private final List<GroupRef> breadcrumb;
    private final String shortLabel;
    private final ComponentType componentType;
    private final String identifier;
    private final String name;
    private final String nameBaseline;
    private final String nameTarget;
    private final String fieldPath;
    private final JsonNode baselineValue;
    private final JsonNode targetValue;
    private final boolean otherAttributes;

    private Difference(final Builder builder) {
        this.category = builder.category;
        this.change = builder.change;
        this.pathSegments = List.copyOf(builder.pathSegments);
        this.path = builder.path;
        this.breadcrumb = List.copyOf(builder.breadcrumb);
        this.shortLabel = builder.shortLabel;
        this.componentType = builder.componentType;
        this.identifier = builder.identifier;
        this.name = builder.name;
        this.nameBaseline = builder.nameBaseline;
        this.nameTarget = builder.nameTarget;
        this.fieldPath = builder.fieldPath;
        this.baselineValue = builder.baselineValue;
        this.targetValue = builder.targetValue;
        this.otherAttributes = builder.otherAttributes;
    }

    /**
     * Returns the change category.
     *
     * @return the category
     */
    public ChangeCategory getCategory() {
        return category;
    }

    /**
     * Returns the whole-component change kind, {@code "added"} or {@code "removed"}, or {@code null} for an in-place
     * field change.
     *
     * @return the change kind or {@code null}
     */
    public String getChange() {
        return change;
    }

    /**
     * Returns the logical canonical path as an ordered list of unescaped segments.
     *
     * @return the immutable path segments
     */
    public List<String> getPathSegments() {
        return pathSegments;
    }

    /**
     * Returns the display path, segments joined with {@code /}.
     *
     * @return the display path
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the ancestor process groups of the containing group, root first, used as the text tree breadcrumb.
     *
     * @return the immutable breadcrumb
     */
    public List<GroupRef> getBreadcrumb() {
        return breadcrumb;
    }

    /**
     * Returns the precomputed short display label of the owning component for the text and Markdown renderers (for
     * example {@code LoadStaging}, {@code Route(7c8d9e0f)}, or {@code QueryRecords -> PutDatabaseRecord}), or
     * {@code null} when the change belongs directly to the containing process group.
     *
     * @return the short component label or {@code null}
     */
    public String getShortLabel() {
        return shortLabel;
    }

    /**
     * Returns the owning component type, or {@code null} for a root-owned or section record.
     *
     * @return the component type or {@code null}
     */
    public ComponentType getComponentType() {
        return componentType;
    }

    /**
     * Returns the owning component identifier, or {@code null} for a root-owned or by-name section record.
     *
     * @return the identifier or {@code null}
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Returns the owning component name, or {@code null} for a root-owned or by-name section record.
     *
     * @return the name or {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the baseline name for a rename, or {@code null} when the change is not a {@code name} change.
     *
     * @return the baseline name or {@code null}
     */
    public String getNameBaseline() {
        return nameBaseline;
    }

    /**
     * Returns the target name for a rename, or {@code null} when the change is not a {@code name} change.
     *
     * @return the target name or {@code null}
     */
    public String getNameTarget() {
        return nameTarget;
    }

    /**
     * Returns the field path under the owning component (for example {@code properties/Batch Size}), or {@code null}
     * for a whole added or removed component.
     *
     * @return the field path or {@code null}
     */
    public String getFieldPath() {
        return fieldPath;
    }

    /**
     * Returns the raw baseline value, or {@code null} when absent.
     *
     * @return the baseline value node or {@code null}
     */
    public JsonNode getBaselineValue() {
        return baselineValue;
    }

    /**
     * Returns the raw target value, or {@code null} when absent.
     *
     * @return the target value node or {@code null}
     */
    public JsonNode getTargetValue() {
        return targetValue;
    }

    /**
     * Tells whether this difference belongs to a non-{@code flowContents} sibling section, which the human renderers
     * bundle under a single {@code other attributes} group.
     *
     * @return {@code true} for a sibling-section difference
     */
    public boolean isOtherAttributes() {
        return otherAttributes;
    }

    /**
     * Creates a new builder.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for {@link Difference}.
     */
    public static final class Builder {

        private ChangeCategory category = ChangeCategory.SIGNIFICANT;
        private String change;
        private List<String> pathSegments = List.of();
        private String path = "";
        private List<GroupRef> breadcrumb = List.of();
        private String shortLabel;
        private ComponentType componentType;
        private String identifier;
        private String name;
        private String nameBaseline;
        private String nameTarget;
        private String fieldPath;
        private JsonNode baselineValue;
        private JsonNode targetValue;
        private boolean otherAttributes;

        private Builder() {
        }

        /**
         * Sets the category.
         *
         * @param value the category
         * @return this builder
         */
        public Builder category(final ChangeCategory value) {
            this.category = value;
            return this;
        }

        /**
         * Sets the whole-component change kind.
         *
         * @param value {@code "added"} or {@code "removed"}
         * @return this builder
         */
        public Builder change(final String value) {
            this.change = value;
            return this;
        }

        /**
         * Sets the logical path segments.
         *
         * @param value the ordered segments
         * @return this builder
         */
        public Builder pathSegments(final List<String> value) {
            this.pathSegments = value;
            return this;
        }

        /**
         * Sets the display path.
         *
         * @param value the display path
         * @return this builder
         */
        public Builder path(final String value) {
            this.path = value;
            return this;
        }

        /**
         * Sets the containing-group breadcrumb.
         *
         * @param value the ancestor groups, root first
         * @return this builder
         */
        public Builder breadcrumb(final List<GroupRef> value) {
            this.breadcrumb = value;
            return this;
        }

        /**
         * Sets the owning component's precomputed short display label.
         *
         * @param value the short label
         * @return this builder
         */
        public Builder shortLabel(final String value) {
            this.shortLabel = value;
            return this;
        }

        /**
         * Sets the owning component type.
         *
         * @param value the component type
         * @return this builder
         */
        public Builder componentType(final ComponentType value) {
            this.componentType = value;
            return this;
        }

        /**
         * Sets the owning component identifier.
         *
         * @param value the identifier
         * @return this builder
         */
        public Builder identifier(final String value) {
            this.identifier = value;
            return this;
        }

        /**
         * Sets the owning component name.
         *
         * @param value the name
         * @return this builder
         */
        public Builder name(final String value) {
            this.name = value;
            return this;
        }

        /**
         * Sets the baseline and target names for a rename change.
         *
         * @param baseline the baseline name
         * @param target   the target name
         * @return this builder
         */
        public Builder renamed(final String baseline, final String target) {
            this.nameBaseline = baseline;
            this.nameTarget = target;
            return this;
        }

        /**
         * Sets the field path under the owning component.
         *
         * @param value the field path
         * @return this builder
         */
        public Builder fieldPath(final String value) {
            this.fieldPath = value;
            return this;
        }

        /**
         * Sets the baseline and target raw values.
         *
         * @param baseline the baseline value node
         * @param target   the target value node
         * @return this builder
         */
        public Builder values(final JsonNode baseline, final JsonNode target) {
            this.baselineValue = baseline;
            this.targetValue = target;
            return this;
        }

        /**
         * Marks the difference as belonging to a non-{@code flowContents} sibling section.
         *
         * @param value {@code true} for a sibling-section difference
         * @return this builder
         */
        public Builder otherAttributes(final boolean value) {
            this.otherAttributes = value;
            return this;
        }

        /**
         * Builds the difference.
         *
         * @return a new immutable difference
         */
        public Difference build() {
            return new Difference(this);
        }
    }
}
