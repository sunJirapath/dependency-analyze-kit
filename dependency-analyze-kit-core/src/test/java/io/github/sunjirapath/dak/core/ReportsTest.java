package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.jr.ob.JSON;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsTest {
    @Test
    void versionedEnvelopeIncludesAcceptedContextProvenance() throws Exception {
        ProbeReport probe = new ProbeReport("app", true, List.of("prod", "audit", "prod"), List.of(), List.of(),
                "child", "parent", "/private/project", 123L);
        Map<String, Object> report = JSON.std.mapFrom(Reports.json(List.of(), List.of(probe), List.of()));
        assertEquals(1, report.get("schemaVersion"));
        Map<?, ?> tool = (Map<?, ?>) report.get("tool");
        assertEquals("dependency-analyze-kit", tool.get("name"));
        String version = (String) tool.get("version");
        assertFalse(version.isBlank());
        assertFalse(version.contains("${"), "Build must filter the tool version");
        assertEquals("COMPLETE", ((Map<?, ?>) report.get("analysis")).get("status"));
        Map<?, ?> context = (Map<?, ?>) ((List<?>) report.get("contexts")).get(0);
        assertEquals("child", context.get("contextKey"));
        assertEquals("parent", context.get("parentContextKey"));
        assertEquals(123L, ((Number) context.get("capturedAt")).longValue());
        assertEquals(List.of("audit", "prod"), context.get("profiles"));
        assertFalse(Reports.json(List.of(), List.of(probe), List.of()).contains("/private/project"));
    }

    @Test
    void incompleteReasonsCoverMissingSliceUnknownAndPartialAnalysis() throws Exception {
        ProbeReport slice = new ProbeReport("slice", false, List.of(), List.of(), List.of());
        ProbeReport full = new ProbeReport("app", true, List.of(), List.of(), List.of());
        Classifier.Result unknown = new Classifier.Result(new ArtifactId("g", "a"), "compile", Verdict.UNKNOWN,
                "unknown", List.of());
        assertFalse(Reports.incompleteReasons(List.of(), List.of(), List.of()).isEmpty());
        assertFalse(Reports.incompleteReasons(List.of(), List.of(slice), List.of()).isEmpty());
        assertTrue(Reports.incompleteReasons(List.of(unknown), List.of(full), List.of())
                .contains("Unknown dependency verdicts"));
        Map<String, Object> partial = JSON.std.mapFrom(Reports.json(List.of(), List.of(full), List.of("Excluded scope")));
        assertEquals("INCOMPLETE", ((Map<?, ?>) partial.get("analysis")).get("status"));
        assertEquals(List.of("Excluded scope"), ((Map<?, ?>) partial.get("analysis")).get("reasons"));
        Map<String, Object> legacy = JSON.std.mapFrom(Reports.json(List.of(), 1));
        assertEquals("INCOMPLETE", ((Map<?, ?>) legacy.get("analysis")).get("status"));
    }

    @Test
    void dependencyAndEvidenceArraysAreCanonical() throws Exception {
        Evidence a = new Evidence(Evidence.Kind.BYTECODE, "a.A");
        Evidence z = new Evidence(Evidence.Kind.BYTECODE, "z.Z");
        Classifier.Result first = new Classifier.Result(new ArtifactId("a", "a"), "compile", Verdict.USED_BYTECODE,
                "used", List.of(z, a));
        Classifier.Result second = new Classifier.Result(new ArtifactId("z", "z"), "runtime", Verdict.IGNORED,
                "ignored", List.of());
        Classifier.Result sorted = new Classifier.Result(first.dependency(), first.scope(), first.verdict(),
                first.summary(), List.of(a, z));
        assertEquals(Reports.json(List.of(sorted, second), 0), Reports.json(List.of(second, first), 0));
    }
}
