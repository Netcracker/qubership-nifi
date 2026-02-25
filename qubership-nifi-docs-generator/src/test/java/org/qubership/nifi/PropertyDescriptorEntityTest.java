package org.qubership.nifi;

import org.apache.nifi.components.AllowableValue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests for {@link PropertyDescriptorEntity}. */
class PropertyDescriptorEntityTest {

    private PropertyDescriptorEntity entity(String defaultValue, String description) {
        return new PropertyDescriptorEntity("Display", "api-name", defaultValue, description, null, null);
    }

    // --- getDefaultValueAsString tests ---

    /** Verifies null default value returns empty string. */
    @Test
    void testGetDefaultValueAsStringWithNullReturnsEmpty() {
        assertEquals("", entity(null, null).getDefaultValueAsString());
    }

    /** Verifies empty default value returns empty string. */
    @Test
    void testGetDefaultValueAsStringWithEmptyReturnsEmpty() {
        assertEquals("", entity("", null).getDefaultValueAsString());
    }

    /** Verifies non-empty default value is returned as-is. */
    @Test
    void testGetDefaultValueAsStringWithNormalValueReturnsValue() {
        assertEquals("myDefault", entity("myDefault", null).getDefaultValueAsString());
    }

    /** Verifies URL in default value is wrapped in backticks. */
    @Test
    void testGetDefaultValueAsStringWithUrlEscapesWithBackticks() {
        String result = entity("http://example.com", null).getDefaultValueAsString();
        assertEquals("`http://example.com`", result);
    }

    // --- getDescriptionAsString tests ---

    /** Verifies null description returns empty string. */
    @Test
    void testGetDescriptionAsStringWithNullReturnsEmpty() {
        assertEquals("", entity(null, null).getDescriptionAsString());
    }

    /** Verifies plain text description is returned as-is. */
    @Test
    void testGetDescriptionAsStringWithNormalTextReturnsText() {
        assertEquals("Some description", entity(null, "Some description").getDescriptionAsString());
    }

    /** Verifies URL in description is wrapped in backticks. */
    @Test
    void testGetDescriptionAsStringWithHttpUrlEscapesWithBackticks() {
        String result = entity(null, "See https://docs.example.com for details").getDescriptionAsString();
        assertEquals("See `https://docs.example.com` for details", result);
    }

    // --- getAllowableValuesAsString tests ---

    /** Verifies null allowable values list returns empty string. */
    @Test
    void testGetAllowableValuesAsStringWithNullListReturnsEmpty() {
        PropertyDescriptorEntity e = new PropertyDescriptorEntity("D", "a", null, null, null, null);
        assertEquals("", e.getAllowableValuesAsString());
    }

    /** Verifies empty allowable values list returns empty string. */
    @Test
    void testGetAllowableValuesAsStringWithEmptyListReturnsEmpty() {
        PropertyDescriptorEntity e = new PropertyDescriptorEntity(
                "D", "a", null, null, Collections.emptyList(), null);
        assertEquals("", e.getAllowableValuesAsString());
    }

    /** Verifies multiple allowable values are joined with comma and space. */
    @Test
    void testGetAllowableValuesAsStringWithMultipleValuesReturnsCommaSeparated() {
        AllowableValue v1 = new AllowableValue("val1", "Value One", "desc1");
        AllowableValue v2 = new AllowableValue("val2", "Value Two", "desc2");
        PropertyDescriptorEntity e = new PropertyDescriptorEntity(
                "D", "a", null, null, Arrays.asList(v1, v2), null);
        assertEquals("Value One, Value Two", e.getAllowableValuesAsString());
    }

    // --- escapeHttpLinks tests ---

    /** Verifies null input returns null. */
    @Test
    void testEscapeHttpLinksWithNullReturnsNull() {
        PropertyDescriptorEntity e = entity(null, null);
        assertNull(e.escapeHttpLinks(null));
    }

    /** Verifies text with no URL is returned unchanged. */
    @Test
    void testEscapeHttpLinksWithNoUrlReturnsOriginal() {
        PropertyDescriptorEntity e = entity(null, null);
        assertEquals("no url here", e.escapeHttpLinks("no url here"));
    }

    /** Verifies HTTP URL is wrapped in backticks. */
    @Test
    void testEscapeHttpLinksWithHttpUrlWrapsInBackticks() {
        PropertyDescriptorEntity e = entity(null, null);
        assertEquals("`http://example.com`", e.escapeHttpLinks("http://example.com"));
    }

    /** Verifies HTTPS URL is wrapped in backticks. */
    @Test
    void testEscapeHttpLinksWithHttpsUrlWrapsInBackticks() {
        PropertyDescriptorEntity e = entity(null, null);
        assertEquals("`https://secure.example.com`", e.escapeHttpLinks("https://secure.example.com"));
    }

    /** Verifies all URLs in input text are wrapped in backticks. */
    @Test
    void testEscapeHttpLinksWithMultipleUrlsEscapesAll() {
        PropertyDescriptorEntity e = entity(null, null);
        String input = "See http://a.com and https://b.com";
        String result = e.escapeHttpLinks(input);
        assertEquals("See `http://a.com` and `https://b.com`", result);
    }
}
