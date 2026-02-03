package org.qubership.nifi;

import org.apache.nifi.components.AllowableValue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CustomComponentEntity {

    private static final String URL_REGEX =
            "\\b(?:https?://|www\\d{0,3}[.]|[a-zA-Z0-9.-]+[.][a-zA-Z]{2,4}/)(?:[^\\s()<>]+|\\(([^\\s()<>]+|(\\([^\\s()"
                    + "<>]+\\)))*\\))+(?:\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’])";

    private static final Pattern URL_PATTERN = Pattern.compile(URL_REGEX);

    private String displayName;
    private String apiName;
    private String defaultValue;
    private String description;
    private String componentDescription;
    private final List<AllowableValue> allowableValues;

    public CustomComponentEntity(
            final String displayName,
            final String apiName,
            final String defaultName,
            final String description,
            List<AllowableValue> allowableValues,
            final String componentDescription) {
        this.displayName = displayName;
        this.apiName = apiName;
        this.defaultValue = defaultName;
        this.description = description;
        this.allowableValues = allowableValues;
        this.componentDescription = componentDescription;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getApiName() {
        return apiName;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public String getDefaultValueAsString() {
        if (defaultValue == null || defaultValue.isEmpty()) {
            return "";
        }
        return escapeUrls(defaultValue);
    }

    public String getDescription() {
        return description;
    }

    public String getDescriptioneAsString() {
        if (description == null || description.isEmpty()) {
            return "";
        }
        return escapeUrls(description);
    }

    public String getComponentDescription() {
        return componentDescription;
    }

    public List<AllowableValue> getAllowableValues() {
        return allowableValues;
    }

    public String getAllowableValuesAsString() {
        if (allowableValues == null || allowableValues.isEmpty()) {
            return "";
        }
        return allowableValues.stream()
                .map(AllowableValue::getDisplayName)
                .collect(Collectors.joining(", "));
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public void setDefaultValue(String defaultName) {
        this.defaultValue = defaultName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setComponentDescription(String componentDescription) {
        this.componentDescription = componentDescription;
    }

    public static String escapeUrls(String value) {
        Matcher matcher = URL_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();

        boolean foundUrl = false;
        while (matcher.find()) {
            foundUrl = true;
            matcher.appendReplacement(sb, "`" + matcher.group() + "`");
        }
        matcher.appendTail(sb);

        return foundUrl ? sb.toString() : value;
    }
}
