package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BeanEvidenceDeterminismTest {
    private static final ArtifactId LIB = new ArtifactId("example", "client");
    private static final ArtifactId MODULE = new ArtifactId("example", "module");
    private static final ArtifactId FACTORY = new ArtifactId("example", "factory");
    private static final List<Classifier.Declared> DECLARED = List.of(
            new Classifier.Declared(LIB, "compile"), new Classifier.Declared(MODULE, "compile"),
            new Classifier.Declared(FACTORY, "compile"));
    private static final Map<ArtifactId, ArtifactId> OWNERS = Map.of(LIB, LIB, MODULE, MODULE, FACTORY, FACTORY);
    private final Classifier classifier = new Classifier(List.of(), List.of(), Set.of(MODULE));

    @Test
    void evidenceUsesSmallestReachableRootAcrossProbeAndBeanOrders() {
        var parent = context("parent", null, List.of(
                bean("client", LIB, null, "tail"), bean("tail", LIB, null, "client"),
                bean("unreachable", LIB, null)));
        var first = context("child-a", "parent", List.of(
                bean("aRoot", ArtifactId.PROJECT, null, "bridge"),
                bean("bridge", LIB, null, "client"),
                bean("factory", FACTORY, "aRoot"),
                bean("product", FACTORY, "factory", "client")));
        var second = context("child-z", "parent", List.of(
                bean("zRoot", ArtifactId.PROJECT, null, "client"),
                bean("moduleService", MODULE, null, "client")));
        List<ProbeReport> probes = List.of(parent, first, second);
        Map<ArtifactId, Set<Evidence>> expected = evidence(probes);

        for (int mask = 0; mask < 8; mask++) {
            List<ProbeReport> ordered = new ArrayList<>();
            for (int i = 0; i < probes.size(); i++) {
                ProbeReport probe = probes.get(i);
                List<ProbeReport.Bean> beans = new ArrayList<>(probe.beans());
                if ((mask & (1 << i)) != 0) Collections.reverse(beans);
                ordered.add(context(probe.contextKey(), probe.parentContextKey(), beans));
            }
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    if (a == b) continue;
                    int c = 3 - a - b;
                    assertEquals(expected, evidence(List.of(ordered.get(a), ordered.get(b), ordered.get(c))),
                            "probe order " + a + b + c + ", bean reversal mask " + mask);
                }
            }
        }

        assertEquals(3, expected.get(LIB).size());
        assertTrue(expected.get(LIB).stream().allMatch(e -> e.kind() == Evidence.Kind.BEAN
                && e.detail().endsWith("used by project bean 'aRoot'")));
        assertFalse(expected.get(LIB).stream().anyMatch(e -> e.detail().contains("unreachable")));
        assertEquals(Set.of(new Evidence(Evidence.Kind.BEAN,
                "bean 'moduleService' (example.moduleService) of a project module")), expected.get(MODULE));
        assertTrue(expected.get(FACTORY).isEmpty(), "factory products remain roots, not usage of their own jar");
    }

    @Test
    void generatedBeanEvidenceIgnoresSuffixAssignmentsAcrossSnapshots() {
        Map<ArtifactId, Set<Evidence>> expected = Map.of(
                LIB, Set.of(new Evidence(Evidence.Kind.BEAN,
                        "bean 'x.named-queries' (example.NamedQueries) used by project bean 'aRoot#0'")),
                MODULE, Set.of(), FACTORY, Set.of());
        for (int swap = 0; swap < 2; swap++) {
            String firstName = "x.named-queries#" + (swap == 0 ? 1 : 2);
            String secondName = "x.named-queries#" + (swap == 0 ? 2 : 1);
            List<ProbeReport> probes = List.of(
                    context("first", null, List.of(
                            bean("zRoot#0", ArtifactId.PROJECT, null, firstName),
                            new ProbeReport.Bean(firstName, "example.NamedQueries", Set.of(LIB), null, List.of()))),
                    context("second", null, List.of(
                            bean("aRoot#0", ArtifactId.PROJECT, null, secondName),
                            new ProbeReport.Bean(secondName, "example.NamedQueries", Set.of(LIB), null, List.of()))));
            for (int mask = 0; mask < 4; mask++) {
                List<ProbeReport> ordered = new ArrayList<>();
                for (int i = 0; i < probes.size(); i++) {
                    ProbeReport probe = probes.get(i);
                    List<ProbeReport.Bean> beans = new ArrayList<>(probe.beans());
                    if ((mask & (1 << i)) != 0) Collections.reverse(beans);
                    ordered.add(context(probe.contextKey(), probe.parentContextKey(), beans));
                }
                assertEquals(expected, evidence(ordered), "suffix swap " + swap + ", reversal mask " + mask);
                Collections.reverse(ordered);
                assertEquals(expected, evidence(ordered), "reversed probes, suffix swap " + swap + ", mask " + mask);
            }
        }
    }

    private Map<ArtifactId, Set<Evidence>> evidence(List<ProbeReport> probes) {
        Map<ArtifactId, Set<Evidence>> result = new java.util.HashMap<>();
        for (Classifier.Result dependency : classifier.classify(DECLARED, OWNERS, Map.of(), probes)) {
            Set<Evidence> unique = Set.copyOf(dependency.evidence());
            assertEquals(unique.size(), dependency.evidence().size(), "duplicate evidence for " + dependency.dependency());
            result.put(dependency.dependency(), unique);
        }
        return result;
    }

    private static ProbeReport context(String key, String parent, List<ProbeReport.Bean> beans) {
        return new ProbeReport("application", true, List.of(), List.of(), beans, key, parent);
    }

    private static ProbeReport.Bean bean(String name, ArtifactId artifact, String factory, String... dependencies) {
        return new ProbeReport.Bean(name, "example." + name, Set.of(artifact), factory, List.of(dependencies));
    }
}
