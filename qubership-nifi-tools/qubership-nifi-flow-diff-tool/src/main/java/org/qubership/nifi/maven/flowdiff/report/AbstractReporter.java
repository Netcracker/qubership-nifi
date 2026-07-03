package org.qubership.nifi.maven.flowdiff.report;

import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.EndpointChange;
import org.qubership.nifi.maven.flowdiff.compare.ShortLabel;
import org.qubership.nifi.maven.flowdiff.flow.GroupRef;

import java.util.ArrayList;
import java.util.List;

public class AbstractReporter {
    /** Defines, if technical differences are displayed.  */
    protected final boolean showTechnical;
    /** Defines max value that is displayed.  */
    protected final int maxValueLength;

    /**
     * Creates a reporter.
     *
     * @param maxValueLengthValue the value truncation budget; {@code 0} disables truncation
     * @param showTechnicalValue  whether to also list technical changes, marked {@code [tech]}
     */
    public AbstractReporter(final int maxValueLengthValue, final boolean showTechnicalValue) {
        this.maxValueLength = maxValueLengthValue;
        this.showTechnical = showTechnicalValue;
    }

    protected static String groupKey(final List<GroupRef> breadcrumb) {
        List<String> ids = new ArrayList<>();
        //use root as id for root group
        breadcrumb.forEach(group -> ids.add(group.root() ? "root" : group.identifier()));
        return String.join("/", ids);
    }

    protected static String crumbDisplay(final List<GroupRef> breadcrumb) {
        List<String> labels = new ArrayList<>();
        breadcrumb.forEach(group -> labels.add(ShortLabel.group(group)));
        return String.join(" / ", labels);
    }

    /**
     * Check if difference show be displayed.
     * @param difference difference to check
     * @return true, if difference should be displayed.
     */
    protected boolean isListable(final Difference difference) {
        ChangeCategory category = difference.getCategory();
        return category == ChangeCategory.SIGNIFICANT || category == ChangeCategory.ENVIRONMENTAL
                || (showTechnical && category == ChangeCategory.TECHNICAL);
    }

    protected static String componentKey(final Difference difference) {
        return difference.getShortLabel() + "|" + difference.getIdentifier();
    }

    protected static String categoryMarker(final ChangeCategory category) {
        if (category == ChangeCategory.ENVIRONMENTAL) {
            return "[env] ";
        }
        if (category == ChangeCategory.TECHNICAL) {
            return "[tech] ";
        }
        return "";
    }

    protected static void appendMarker(final Difference difference, final StringBuilder sb) {
        String marker = categoryMarker(difference.getCategory());
        if (!marker.isEmpty()) {
            sb.append(marker);
        }
    }

    protected static String endpoint(final EndpointChange.EndpointRef ref) {
        //use type code by default:
        return endpoint(ref, false);
    }

    protected static String endpoint(final EndpointChange.EndpointRef ref, final boolean useTypeName) {
        return "[" + (useTypeName ? ref.typeName() : ref.typeCode()) + "] " + ref.label() + " (" + ref.identifier() + ")";
    }
}
