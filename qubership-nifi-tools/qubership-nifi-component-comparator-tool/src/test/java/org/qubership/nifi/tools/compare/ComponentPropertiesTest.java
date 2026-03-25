package org.qubership.nifi.tools.compare;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComponentPropertiesTest {

    @Test
    void constructorStoresAllFields() {
        ComponentProperties cp = new ComponentProperties("api", "display", "desc");
        assertEquals("api", cp.getApiName());
        assertEquals("display", cp.getDisplayName());
        assertEquals("desc", cp.getDescription());
    }

    @Test
    void constructorAllowsNulls() {
        ComponentProperties cp = new ComponentProperties(null, null, null);
        assertNull(cp.getApiName());
        assertNull(cp.getDisplayName());
        assertNull(cp.getDescription());
    }

    @Test
    void hasEquivalentNameReturnsFalseWhenMappingsNotSet() {
        ComponentProperties cp = new ComponentProperties("a", "Display", "d");
        assertFalse(cp.hasEquivalentName("Display"));
    }

    @Test
    void hasEquivalentNameReturnsFalseForNullInput() {
        ComponentProperties cp = new ComponentProperties("a", "Display", "d");
        cp.setEquivalentNameMappings(Map.of("display", "New Display"));
        assertFalse(cp.hasEquivalentName(null));
    }

    @Test
    void hasEquivalentNameReturnsTrueCaseInsensitiveLookup() {
        ComponentProperties cp = new ComponentProperties("a", "Display", "d");
        cp.setEquivalentNameMappings(Map.of("old name", "New Name"));
        assertTrue(cp.hasEquivalentName("Old Name"));
        assertTrue(cp.hasEquivalentName("old name"));
        assertTrue(cp.hasEquivalentName("OLD NAME"));
    }

    @Test
    void getEquivalentNameReturnsNullWhenMappingsNotSet() {
        ComponentProperties cp = new ComponentProperties("a", "Display", "d");
        assertNull(cp.getEquivalentName("Display"));
    }

    @Test
    void getEquivalentNameReturnsNullForNullInput() {
        ComponentProperties cp = new ComponentProperties("a", "Display", "d");
        cp.setEquivalentNameMappings(Map.of("display", "New Display"));
        assertNull(cp.getEquivalentName(null));
    }

    @Test
    void getEquivalentNameReturnsMappedValue() {
        ComponentProperties cp = new ComponentProperties("a", "Display", "d");
        cp.setEquivalentNameMappings(Map.of("old name", "New Name"));
        assertEquals("New Name", cp.getEquivalentName("Old Name"));
    }

    @Test
    void getEquivalentNameReturnsNullWhenKeyNotFound() {
        ComponentProperties cp = new ComponentProperties("a", "Display", "d");
        cp.setEquivalentNameMappings(Map.of("other", "Value"));
        assertNull(cp.getEquivalentName("missing"));
    }

    @Test
    void compareUniqueReturnsFalseForNull() {
        ComponentProperties cp = new ComponentProperties("a", "d", "desc");
        assertFalse(cp.compareUniqueDisplayName(null));
    }

    @Test
    void compareUniqueMatchesByApiName() {
        ComponentProperties source = new ComponentProperties("same-api", "Display A", "desc1");
        ComponentProperties target = new ComponentProperties("same-api", "Display B", "desc2");
        assertTrue(source.compareUniqueDisplayName(target));
    }

    @Test
    void compareUniqueMatchesByExactDisplayName() {
        ComponentProperties source = new ComponentProperties("api-1", "Same Display", "desc1");
        ComponentProperties target = new ComponentProperties("api-2", "Same Display", "desc2");
        assertTrue(source.compareUniqueDisplayName(target));
    }

    @Test
    void compareUniqueMatchesByCaseInsensitiveDisplayName() {
        ComponentProperties source = new ComponentProperties("api-1", "my property", "desc1");
        ComponentProperties target = new ComponentProperties("api-2", "My Property", "desc2");
        assertTrue(source.compareUniqueDisplayName(target));
    }

    @Test
    void compareUniqueMatchesByDictionaryMapping() {
        ComponentProperties source = new ComponentProperties("api-1", "New Name", "desc1");
        source.setEquivalentNameMappings(Map.of("old name", "New Name"));

        ComponentProperties target = new ComponentProperties("api-2", "Old Name", "desc2");
        assertTrue(source.compareUniqueDisplayName(target));
    }

    @Test
    void compareUniqueMatchesByDictionaryMapping2() {
        ComponentProperties target = new ComponentProperties("api-1", "New Name", "desc1");
        target.setEquivalentNameMappings(Map.of("old name", "New Name"));

        ComponentProperties source = new ComponentProperties("api-2", "Old Name", "desc2");
        assertTrue(target.compareUniqueDisplayName(source));
    }

    @Test
    void compareUniqueReturnsFalseWhenNothingMatches() {
        ComponentProperties source = new ComponentProperties("api-1", "Display A", "desc1");
        ComponentProperties target = new ComponentProperties("api-2", "Display B", "desc2");
        assertFalse(source.compareUniqueDisplayName(target));
    }

    @Test
    void compareUniqueHandlesNullDisplayNames() {
        ComponentProperties source = new ComponentProperties("api-1", null, "desc1");
        ComponentProperties target = new ComponentProperties("api-2", "Display B", "desc2");
        assertFalse(source.compareUniqueDisplayName(target));
    }

    @Test
    void compareUniqueBothNullDisplayNamesMatchesViaNullEquals() {
        ComponentProperties source = new ComponentProperties("api-1", null, "desc1");
        ComponentProperties target = new ComponentProperties("api-2", null, "desc2");
        // Objects.equals(null, null) == true
        assertTrue(source.compareUniqueDisplayName(target));
    }

    @Test
    void compareNonUniqueReturnsFalseForNull() {
        ComponentProperties cp = new ComponentProperties("a", "d", "desc");
        assertFalse(cp.compareNonUniqueDisplayName(null));
    }

    @Test
    void compareNonUniqueMatchesByApiNameIgnoresDescription() {
        ComponentProperties source = new ComponentProperties("same-api", "Display A", "desc1");
        ComponentProperties target = new ComponentProperties("same-api", "Display B", "desc2");
        assertTrue(source.compareNonUniqueDisplayName(target));
    }

    @Test
    void compareNonUniqueMatchesByDisplayNameAndDescription() {
        ComponentProperties source = new ComponentProperties("api-1", "Same Display", "Same Desc");
        ComponentProperties target = new ComponentProperties("api-2", "Same Display", "Same Desc");
        assertTrue(source.compareNonUniqueDisplayName(target));
    }

    @Test
    void compareNonUniqueNoMatchWhenDisplayNameSameButDescriptionDiffers() {
        ComponentProperties source = new ComponentProperties("api-1", "Same Display", "Desc A");
        ComponentProperties target = new ComponentProperties("api-2", "Same Display", "Desc B");
        assertFalse(source.compareNonUniqueDisplayName(target));
    }

    @Test
    void compareNonUniqueMatchesByCaseInsensitiveDisplayNameAndDescription() {
        ComponentProperties source = new ComponentProperties("api-1", "my prop", "Same Desc");
        ComponentProperties target = new ComponentProperties("api-2", "My Prop", "Same Desc");
        assertTrue(source.compareNonUniqueDisplayName(target));
    }

    @Test
    void compareNonUniqueNoMatchCaseInsensitiveDisplayNameButDescriptionDiffers() {
        ComponentProperties source = new ComponentProperties("api-1", "my prop", "Desc A");
        ComponentProperties target = new ComponentProperties("api-2", "My Prop", "Desc B");
        assertFalse(source.compareNonUniqueDisplayName(target));
    }

    @Test
    void compareNonUniqueMatchesByDictionaryAndDescription() {
        ComponentProperties source = new ComponentProperties("api-1", "New Name", "Same Desc");
        source.setEquivalentNameMappings(Map.of("old name", "New Name"));

        ComponentProperties target = new ComponentProperties("api-2", "Old Name", "Same Desc");
        assertTrue(source.compareNonUniqueDisplayName(target));
    }

    @Test
    void compareNonUniqueNoMatchByDictionaryWhenDescriptionDiffers() {
        ComponentProperties source = new ComponentProperties("api-1", "New Name", "Desc A");
        source.setEquivalentNameMappings(Map.of("old name", "New Name"));

        ComponentProperties target = new ComponentProperties("api-2", "Old Name", "Desc B");
        assertFalse(source.compareNonUniqueDisplayName(target));
    }

    @Test
    void compareNonUniqueReturnsFalseWhenNothingMatches() {
        ComponentProperties source = new ComponentProperties("api-1", "Display A", "desc");
        ComponentProperties target = new ComponentProperties("api-2", "Display B", "desc");
        assertFalse(source.compareNonUniqueDisplayName(target));
    }
}
