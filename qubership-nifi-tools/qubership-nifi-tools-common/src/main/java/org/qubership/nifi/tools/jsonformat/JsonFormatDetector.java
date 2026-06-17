package org.qubership.nifi.tools.jsonformat;

import com.fasterxml.jackson.core.util.Separators;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects the textual formatting of a JSON document by scanning its raw text.
 *
 * <p>The scan is string-aware: it tracks whether it is inside a string literal (honoring escape
 * sequences) so that structural characters such as {@code :}, {@code ,}, <code>{</code> or
 * {@code [} appearing inside string values do not affect detection.</p>
 *
 * <p>For each formatting dimension the most frequent observed value wins ("dominant-wins"). When a
 * dimension never occurs in the input, the corresponding default from {@link JsonFormat#defaults()}
 * is used.</p>
 */
public final class JsonFormatDetector {

    private JsonFormatDetector() {
    }

    /**
     * Detects the formatting of the given JSON text.
     *
     * @param content raw JSON document text
     * @return the detected {@link JsonFormat}
     */
    public static JsonFormat detect(final String content) {
        Tallies tallies = new Tallies();
        Deque<Frame> frames = new ArrayDeque<>();
        int depth = 0;
        boolean inString = false;
        int n = content.length();
        int i = 0;
        while (i < n) {
            char c = content.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '\n':
                    inc(tallies.eol, (i > 0 && content.charAt(i - 1) == '\r') ? "\r\n" : "\n");
                    sampleIndent(content, i + 1, depth, tallies);
                    break;
                case '{':
                case '[':
                    frames.push(new Frame(c == '[', handleContainerStart(content, i, c, tallies)));
                    depth++;
                    break;
                case '}':
                case ']':
                    depth--;
                    if (!frames.isEmpty()) {
                        frames.pop();
                    }
                    break;
                case ':':
                    inc(tallies.colon, colonSpacing(content, i));
                    break;
                case ',':
                    handleComma(content, i, frames.peek(), tallies);
                    break;
                default:
                    break;
            }
            i++;
        }
        return build(tallies, content);
    }

    private static JsonFormat build(final Tallies tallies, final String content) {
        ContainerStyle objectStyle = dominant(tallies.objectStyle, ContainerStyle.EXPANDED);
        ContainerStyle arrayStyle = dominant(tallies.arrayStyle, ContainerStyle.EXPANDED);
        char indentChar = dominant(tallies.indentChar, ' ');
        int indentWidth = dominant(tallies.indentWidth, 2);
        return new JsonFormat(
                String.valueOf(indentChar).repeat(indentWidth),
                dominant(tallies.eol, JsonFormat.DEFAULT_EOL),
                objectStyle,
                arrayStyle,
                dominant(tallies.colon, Separators.Spacing.AFTER),
                objectStyle == ContainerStyle.INLINE
                        ? dominant(tallies.objectComma, Separators.Spacing.NONE) : Separators.Spacing.NONE,
                arrayStyle == ContainerStyle.INLINE
                        ? dominant(tallies.arrayComma, Separators.Spacing.NONE) : Separators.Spacing.NONE,
                dominant(tallies.objectEmpty, ""),
                dominant(tallies.arrayEmpty, ""),
                content.endsWith("\n"));
    }

    private static ContainerStyle handleContainerStart(
            final String content,
            final int index,
            final char open,
            final Tallies tallies) {
        boolean isArray = open == '[';
        char closer = isArray ? ']' : '}';
        int n = content.length();
        int k = index + 1;
        while (k < n && (content.charAt(k) == ' ' || content.charAt(k) == '\t')) {
            k++;
        }
        if (k < n && content.charAt(k) == closer) {
            inc(isArray ? tallies.arrayEmpty : tallies.objectEmpty, (k > index + 1) ? " " : "");
            return ContainerStyle.INLINE;
        }
        ContainerStyle style;
        if (k < n && (content.charAt(k) == '\n' || content.charAt(k) == '\r')) {
            style = ContainerStyle.EXPANDED;
        } else if (k > index + 1) {
            style = ContainerStyle.FIXED_SPACE;
        } else {
            style = ContainerStyle.INLINE;
        }
        inc(isArray ? tallies.arrayStyle : tallies.objectStyle, style);
        return style;
    }

    private static void handleComma(
            final String content,
            final int index,
            final Frame frame,
            final Tallies tallies) {
        if (frame == null || frame.style() != ContainerStyle.INLINE) {
            return;
        }
        int n = content.length();
        int q = index + 1;
        int after = 0;
        while (q < n && (content.charAt(q) == ' ' || content.charAt(q) == '\t')) {
            after++;
            q++;
        }
        Separators.Spacing spacing = after > 0 ? Separators.Spacing.AFTER : Separators.Spacing.NONE;
        inc(frame.isArray() ? tallies.arrayComma : tallies.objectComma, spacing);
    }

    private static Separators.Spacing colonSpacing(final String content, final int index) {
        int before = 0;
        int p = index - 1;
        while (p >= 0 && (content.charAt(p) == ' ' || content.charAt(p) == '\t')) {
            before++;
            p--;
        }
        int after = 0;
        int q = index + 1;
        int n = content.length();
        while (q < n && (content.charAt(q) == ' ' || content.charAt(q) == '\t')) {
            after++;
            q++;
        }
        if (before > 0 && after > 0) {
            return Separators.Spacing.BOTH;
        }
        if (before > 0) {
            return Separators.Spacing.BEFORE;
        }
        if (after > 0) {
            return Separators.Spacing.AFTER;
        }
        return Separators.Spacing.NONE;
    }

    private static void sampleIndent(
            final String content,
            final int lineStart,
            final int depth,
            final Tallies tallies) {
        int n = content.length();
        int j = lineStart;
        int length = 0;
        char first = ' ';
        while (j < n && (content.charAt(j) == ' ' || content.charAt(j) == '\t')) {
            if (length == 0) {
                first = content.charAt(j);
            }
            length++;
            j++;
        }
        if (j >= n) {
            return;
        }
        char nonWhitespace = content.charAt(j);
        if (nonWhitespace == '\n' || nonWhitespace == '\r') {
            return;
        }
        if (nonWhitespace == '}' || nonWhitespace == ']') {
            return;
        }
        if (depth >= 1 && length > 0 && length % depth == 0) {
            inc(tallies.indentWidth, length / depth);
            inc(tallies.indentChar, first);
        }
    }

    private static <T> void inc(final Map<T, Integer> counts, final T key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static <T> T dominant(final Map<T, Integer> counts, final T fallback) {
        T best = fallback;
        int bestCount = 0;
        for (Map.Entry<T, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * Mutable frequency counters collected during a single detection pass.
     */
    private static final class Tallies {
        private final Map<String, Integer> eol = new HashMap<>();
        private final Map<Integer, Integer> indentWidth = new HashMap<>();
        private final Map<Character, Integer> indentChar = new HashMap<>();
        private final Map<ContainerStyle, Integer> objectStyle = new HashMap<>();
        private final Map<ContainerStyle, Integer> arrayStyle = new HashMap<>();
        private final Map<Separators.Spacing, Integer> colon = new HashMap<>();
        private final Map<Separators.Spacing, Integer> objectComma = new HashMap<>();
        private final Map<Separators.Spacing, Integer> arrayComma = new HashMap<>();
        private final Map<String, Integer> objectEmpty = new HashMap<>();
        private final Map<String, Integer> arrayEmpty = new HashMap<>();
    }

    /**
     * A container currently open during the scan.
     *
     * @param isArray whether the container is an array (otherwise an object)
     * @param style the detected layout style of the container
     */
    private record Frame(boolean isArray, ContainerStyle style) {
    }
}
