package io.github.sunjirapath.dak.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProbeFreshnessTest {
    @Test
    void acceptsFreshProbe() {
        assertTrue(ProbeFreshness.isFresh(102, 101, 100));
    }

    @Test
    void rejectsOldFileModificationTime() {
        assertFalse(ProbeFreshness.isFresh(99, 101, 100));
    }

    @Test
    void rejectsOldCaptureTimeEvenWhenFileWasTouched() {
        assertFalse(ProbeFreshness.isFresh(102, 99, 100));
    }

    @Test
    void rejectsBothOldTimes() {
        assertFalse(ProbeFreshness.isFresh(99, 98, 100));
    }

    @Test
    void acceptsTimesEqualToBuildStart() {
        assertTrue(ProbeFreshness.isFresh(100, 100, 100));
    }
}
