package org.qubership.nifi.tools.jsonformat;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;

/**
 * Builds Jackson pretty printers configured to reproduce a detected {@link JsonFormat}.
 */
public final class JsonFormatPrettyPrinters {

    private JsonFormatPrettyPrinters() {
    }

    /**
     * Creates a {@link DefaultPrettyPrinter} that reproduces the given formatting.
     *
     * @param format the detected formatting to reproduce
     * @return a configured pretty printer
     */
    public static DefaultPrettyPrinter from(final JsonFormat format) {
        Separators separators = Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(format.objectColonSpacing())
                .withObjectEntrySpacing(format.objectEntrySpacing())
                .withArrayValueSpacing(format.arrayElementSpacing())
                .withObjectEmptySeparator(format.objectEmptySeparator())
                .withArrayEmptySeparator(format.arrayEmptySeparator());

        return new DefaultPrettyPrinter()
                .withSeparators(separators)
                .withObjectIndenter(indenterFor(format.objectStyle(), format.indent(), format.eol()))
                .withArrayIndenter(indenterFor(format.arrayStyle(), format.indent(), format.eol()));
    }

    private static DefaultPrettyPrinter.Indenter indenterFor(
            final ContainerStyle style,
            final String indent,
            final String eol) {
        return switch (style) {
            case EXPANDED -> new DefaultIndenter(indent, eol);
            case FIXED_SPACE -> new DefaultPrettyPrinter.FixedSpaceIndenter();
            case INLINE -> new DefaultPrettyPrinter.NopIndenter();
        };
    }
}
