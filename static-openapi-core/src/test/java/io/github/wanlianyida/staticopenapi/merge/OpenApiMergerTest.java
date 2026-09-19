package io.github.wanlianyida.staticopenapi.merge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenApiMergerTest {

    private final OpenApiMerger merger = new OpenApiMerger();

    @Test
    void summaryPrefersSwagger3() {
        assertEquals("v3", merger.mergeSummary("v3", "v2", "jd", "def"));
    }

    @Test
    void summaryFallsBackToSwagger2ThenJavadocThenDefault() {
        assertEquals("v2", merger.mergeSummary(null, "v2", "jd", "def"));
        assertEquals("jd", merger.mergeSummary("", "  ", "jd", "def"));
        assertEquals("def", merger.mergeSummary(null, null, null, "def"));
        assertEquals("", merger.mergeSummary(null, null, null, null));
    }

    @Test
    void descriptionSamePriorityOrder() {
        assertEquals("v3", merger.mergeDescription("v3", "v2", "jd"));
        assertEquals("v2", merger.mergeDescription("", "v2", "jd"));
        assertEquals("jd", merger.mergeDescription(null, "", " jd "));
    }
}
