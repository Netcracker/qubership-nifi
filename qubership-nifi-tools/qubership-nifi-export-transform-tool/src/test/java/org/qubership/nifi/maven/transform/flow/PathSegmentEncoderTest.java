package org.qubership.nifi.maven.transform.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathSegmentEncoderTest {

    @Test
    void encodeReplacesEachSpecialCharacterWithItsToken() {
        assertEquals("_bs_", PathSegmentEncoder.encode("\\"));
        assertEquals("_sl_", PathSegmentEncoder.encode("/"));
        assertEquals("_cl_", PathSegmentEncoder.encode(":"));
        assertEquals("_st_", PathSegmentEncoder.encode("*"));
        assertEquals("_qm_", PathSegmentEncoder.encode("?"));
        assertEquals("_qt_", PathSegmentEncoder.encode("\""));
        assertEquals("_lt_", PathSegmentEncoder.encode("<"));
        assertEquals("_gt_", PathSegmentEncoder.encode(">"));
        assertEquals("_vb_", PathSegmentEncoder.encode("|"));
    }

    @Test
    void encodeReplacesEverySpecialCharacterInsideAName() {
        assertEquals("Get value_gt_1", PathSegmentEncoder.encode("Get value>1"));
        assertEquals("a_lt_b_gt_c_sl_d", PathSegmentEncoder.encode("a<b>c/d"));
    }

    @Test
    void encodeReplacesRepeatedSpecialCharacters() {
        assertEquals("_sl__sl__sl_", PathSegmentEncoder.encode("///"));
    }

    @Test
    void encodeLeavesNameWithoutSpecialCharactersUnchanged() {
        assertEquals("Get status 200", PathSegmentEncoder.encode("Get status 200"));
    }

    @Test
    void encodeLeavesUnderscoreUnchanged() {
        assertEquals("Get_value_1", PathSegmentEncoder.encode("Get_value_1"));
    }

    @Test
    void encodeIsIdempotent() {
        String encodedOnce = PathSegmentEncoder.encode("a<b>c/d");
        assertEquals(encodedOnce, PathSegmentEncoder.encode(encodedOnce));
    }

    @Test
    void encodeReturnsEmptyStringForEmptyInput() {
        assertEquals("", PathSegmentEncoder.encode(""));
    }
}
