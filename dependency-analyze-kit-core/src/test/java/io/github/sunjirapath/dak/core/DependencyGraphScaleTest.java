package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.jr.ob.JSON;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class DependencyGraphScaleTest {
    @TempDir Path dir;

    @Test void tenThousandArtifactsPreserveOwnershipAndExactVerdictCounts() throws Exception {
        int count = 5_000;
        var declared = new ArrayList<Classifier.Declared>();
        Map<ArtifactId, List<ArtifactId>> closures = new LinkedHashMap<>();
        Map<ArtifactId, List<String>> bytecode = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            ArtifactId direct = new ArtifactId("scale", "direct-" + i);
            ArtifactId transitive = new ArtifactId("scale", "transitive-" + i);
            declared.add(new Classifier.Declared(direct, "compile"));
            closures.put(direct, List.of(direct, transitive));
            if (i % 2 == 0) bytecode.put(transitive, List.of("scale.Type" + i));
        }
        var owners = Classifier.owners(closures, Set.of());
        assertEquals(10_000, owners.size());
        for (int i = 0; i < count; i++) assertEquals(declared.get(i).id(), owners.get(new ArtifactId("scale", "transitive-" + i)));
        var full = new ProbeReport("scale", true, List.of(), List.of(), List.of());
        var results = new Classifier(List.of(), List.of()).classify(declared, owners, bytecode, List.of(full));
        assertEquals(count, results.size());
        for (int i = 0; i < count; i++) assertEquals(i % 2 == 0 ? Verdict.USED_BYTECODE : Verdict.UNUSED_CANDIDATE,
                results.get(i).verdict(), "dependency=" + i);
        var parsed = JSON.std.mapFrom(Reports.json(results, List.of(full), List.of()));
        assertEquals(count, ((List<?>) parsed.get("dependencies")).size());
        assertEquals("COMPLETE", ((Map<?, ?>) parsed.get("analysis")).get("status"));
    }

    @Test void tenThousandBeanCycleIsTraversedIterativelyAndSurvivesProbeParsing() throws Exception {
        int count = 10_000;
        ArtifactId lib = new ArtifactId("scale", "chain");
        var beans = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) beans.add(Map.of("name", "bean" + i, "type", "scale.Bean",
                "locations", List.of(i == 0 ? "/project" : "/library"),
                "dependencies", List.of("bean" + ((i + 1) % count))));
        Path file = Files.writeString(dir.resolve("large.json"), JSON.std.asString(Map.of("id", "scale", "capturedAt", 1,
                "fullAutoConfig", true, "profiles", List.of(), "autoConfigs", List.of(), "beans", beans)));
        var probe = ProbeReport.read(file, s -> s.equals("/project") ? ArtifactId.PROJECT : lib);
        assertTrue(probe.fullAutoConfig());
        assertEquals(count, probe.beans().size());
        var result = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(lib, "compile")),
                Map.of(lib, lib), Map.of(), List.of(probe)).get(0);
        assertEquals(Verdict.USED_BEAN, result.verdict());
        assertEquals(count - 1, result.evidence().size());
        assertTrue(result.evidence().stream().allMatch(e -> e.kind() == Evidence.Kind.BEAN
                && e.detail().endsWith("used by project bean 'bean0'")));
    }

    @Test void fiveThousandAncestorContextsResolveWithoutRecursion() {
        int count = 5_000;
        ArtifactId lib = new ArtifactId("scale", "ancestor");
        var probes = new ArrayList<ProbeReport>();
        for (int i = 0; i < count; i++) {
            List<ProbeReport.Bean> beans = i == 0
                    ? List.of(new ProbeReport.Bean("client", "scale.Client", Set.of(lib), null, List.of()))
                    : i == count - 1 ? List.of(new ProbeReport.Bean("app", "scale.App", Set.of(ArtifactId.PROJECT),
                            null, List.of("client"))) : List.of();
            probes.add(new ProbeReport("same-display-id", true, List.of(), List.of(), beans,
                    "context" + i, i == 0 ? null : "context" + (i - 1)));
        }
        var result = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(lib, "compile")),
                Map.of(lib, lib), Map.of(), probes).get(0);
        assertEquals(Verdict.USED_BEAN, result.verdict());
        assertEquals(List.of(new Evidence(Evidence.Kind.BEAN,
                "bean 'client' (scale.Client) used by project bean 'app'")), result.evidence());
    }
}
