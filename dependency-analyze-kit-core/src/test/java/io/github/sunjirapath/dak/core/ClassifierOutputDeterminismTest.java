package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClassifierOutputDeterminismTest {
    private static final ArtifactId LIB = new ArtifactId("example", "IMAGE-client");
    private static final ArtifactId A = new ArtifactId("example", "a-transitive");
    private static final ArtifactId Z = new ArtifactId("example", "z-transitive");
    private static final List<Classifier.Declared> DECLARED = List.of(new Classifier.Declared(LIB, "compile"));
    private static final Map<ArtifactId, ArtifactId> OWNERS = Map.of(LIB, LIB, A, LIB, Z, LIB);
    private final Classifier classifier = new Classifier(List.of(), List.of("example:IMAGE-client"));

    // FINDING: Bytecode summary selects insertion-order evidence, changing report bytes when the artifact map is permuted.
    @Test void transitiveBytecodeMapPermutationPreservesCanonicalReport() throws Exception {
        Map<ArtifactId, List<String>> forward = new LinkedHashMap<>();
        forward.put(A, List.of("example.A"));
        forward.put(Z, List.of("example.Z"));
        Map<ArtifactId, List<String>> reverse = new LinkedHashMap<>();
        reverse.put(Z, forward.get(Z));
        reverse.put(A, forward.get(A));
        var first = classifier.classify(DECLARED, OWNERS, forward, List.of(full(List.of())));
        var second = classifier.classify(DECLARED, OWNERS, reverse, List.of(full(List.of())));
        assertEquals(Verdict.USED_BYTECODE, first.get(0).verdict());
        assertEquals(Set.copyOf(first.get(0).evidence()), Set.copyOf(second.get(0).evidence()));
        assertArrayEquals(bytes(first), bytes(second));
    }

    // FINDING: Unmatched auto-configuration summary depends on probe order despite identical evidence.
    @Test void unmatchedProbePermutationPreservesCanonicalReport() throws Exception {
        ProbeReport a = full(List.of(new ProbeReport.AutoConfig("example.AConfig", false, Set.of(LIB), List.of("absent"))));
        ProbeReport z = new ProbeReport("other", true, List.of(),
                List.of(new ProbeReport.AutoConfig("example.ZConfig", false, Set.of(LIB), List.of("off"))),
                List.of(), "other", null, null, 1);
        var first = classifier.classify(DECLARED, OWNERS, Map.of(), List.of(a, z));
        var second = classifier.classify(DECLARED, OWNERS, Map.of(), List.of(z, a));
        assertEquals(Verdict.UNUSED_CANDIDATE, first.get(0).verdict());
        assertEquals(Reports.json(first, List.of(a, z), List.of()), Reports.json(second, List.of(z, a), List.of()));
    }

    // FINDING: Side-effect auto-configuration summary selects the first matched configuration rather than a canonical one.
    @Test void matchedAutoConfigPermutationPreservesCanonicalReport() throws Exception {
        var a = new ProbeReport.AutoConfig("example.AConfig", true, Set.of(LIB), List.of());
        var z = new ProbeReport.AutoConfig("example.ZConfig", true, Set.of(LIB), List.of());
        var first = classifier.classify(DECLARED, OWNERS, Map.of(), List.of(full(List.of(a, z))));
        var second = classifier.classify(DECLARED, OWNERS, Map.of(), List.of(full(List.of(z, a))));
        assertEquals(Verdict.USED_AUTOCONFIG, first.get(0).verdict());
        assertArrayEquals(bytes(first), bytes(second));
    }

    @Test void closureAndDeclaredPermutationsPreserveOwnersAndReport() throws Exception {
        ArtifactId runtime = new ArtifactId("example", "runtime");
        ArtifactId test = new ArtifactId("example", "tests");
        List<ArtifactId> ids = List.of(LIB, runtime, test);
        Map<ArtifactId, ArtifactId> expected = Map.of(LIB, LIB, runtime, runtime, test, test, A, LIB, Z, runtime);
        String baseline = null;
        for (int seed = 0; seed < 12; seed++) {
            var shuffled = new ArrayList<>(ids);
            Collections.shuffle(shuffled, new Random(seed));
            Map<ArtifactId, List<ArtifactId>> closures = new LinkedHashMap<>();
            List<Classifier.Declared> declared = new ArrayList<>();
            for (ArtifactId id : shuffled) {
                var closure = new ArrayList<>(id.equals(LIB) ? List.of(id, A)
                        : id.equals(runtime) ? List.of(id, A, Z) : List.of(id, A, Z));
                Collections.shuffle(closure, new Random(seed + 100));
                closures.put(id, closure);
                declared.add(new Classifier.Declared(id, id.equals(test) ? "test" : id.equals(runtime) ? "runtime" : "compile"));
            }
            var owners = Classifier.owners(closures, Set.of(test), Set.of(runtime));
            assertEquals(expected, owners);
            var results = classifier.classify(declared, owners, Map.of(A, List.of("example.A")), List.of(full(List.of())));
            String json = Reports.json(results, List.of(full(List.of())), List.of());
            if (baseline == null) baseline = json;
            assertEquals(baseline, json, "seed=" + seed);
        }
    }

    @Test void sharedClassifierAndInputsAreRepeatableAcrossSixThreads() throws Exception {
        Map<ArtifactId, List<String>> bytecode = Map.of(A, List.of("example.Z", "example.A"));
        List<ProbeReport> probes = List.of(full(List.of()));
        var before = classifier.classify(DECLARED, OWNERS, bytecode, probes);
        byte[] expected = bytes(before);
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<byte[]>> calls = new ArrayList<>();
            for (int i = 0; i < 48; i++) calls.add(() -> bytes(classifier.classify(DECLARED, OWNERS, bytecode, probes)));
            for (var future : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) assertArrayEquals(expected, future.get());
            assertEquals(before, classifier.classify(DECLARED, OWNERS, bytecode, probes));
            assertEquals(List.of("example.Z", "example.A"), bytecode.get(A));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void localeAndTimezoneDoNotChangeReportOrTable() throws Exception {
        Locale previous = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        Classifier ordinary = new Classifier(List.of(), List.of());
        List<ProbeReport> probes = List.of(full(List.of(
                new ProbeReport.AutoConfig("example.ImageConfig", true, Set.of(LIB), List.of()),
                new ProbeReport.AutoConfig("example.OtherConfig", true, Set.of(LIB), List.of()))));
        var initial = ordinary.classify(DECLARED, OWNERS, Map.of(), probes);
        byte[] json = bytes(initial);
        String table = Reports.table(initial);
        try {
            for (String language : List.of("tr-TR", "th-TH", "en-US")) {
                Locale.setDefault(Locale.forLanguageTag(language));
                for (String id : List.of("Asia/Bangkok", "America/Los_Angeles", "UTC")) {
                    TimeZone.setDefault(TimeZone.getTimeZone(id));
                    var result = ordinary.classify(DECLARED, OWNERS, Map.of(), probes);
                    assertArrayEquals(json, bytes(result), language + "/" + id);
                    assertEquals(table, Reports.table(result));
                }
            }
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
            TimeZone.setDefault(zone);
        }
    }

    private static ProbeReport full(List<ProbeReport.AutoConfig> configs) {
        return new ProbeReport("app", true, List.of("prod", "audit"), configs, List.of(), "app", null, null, 1);
    }

    private static byte[] bytes(List<Classifier.Result> results) throws Exception {
        return Reports.json(results, List.of(full(List.of())), List.of()).getBytes(StandardCharsets.UTF_8);
    }
}
