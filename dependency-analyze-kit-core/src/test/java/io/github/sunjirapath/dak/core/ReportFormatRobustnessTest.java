package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.jr.ob.JSON;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportFormatRobustnessTest {
    private static final ArtifactId LIB = new ArtifactId("example", "library");
    private static final String TEXT = "\u0e44\u0e17\u0e22 \ud83d\ude80 \u05e9\u05dc\u05d5\u05dd \u202eRTL\u202c\r\n\t\"\\\b\f\u0000";
    @TempDir Path dir;

    @Test void probeModelRoundTripsWithBomCrLfAndMixedLineEndings() throws Exception {
        String json = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).asString(wireModel());
        ProbeReport expected = expected();
        for (String variant : List.of(json, "\ufeff" + json, json.replace("\n", "\r\n"),
                "\ufeff" + json.replaceFirst("\n", "\r\n"))) {
            List<String> problems = new ArrayList<>();
            ProbeReport actual = ProbeReport.read(Files.writeString(dir.resolve("probe.json"), variant), s -> LIB, problems);
            assertEquals(expected, actual);
            assertTrue(problems.isEmpty());
        }
    }

    @Test void unknownFieldsAndPermutedObjectKeysPreserveDecodedModel() throws Exception {
        var original = wireModel();
        for (int seed = 0; seed < 8; seed++) {
            var keys = new ArrayList<>(original.keySet());
            Collections.shuffle(keys, new Random(seed));
            Map<String, Object> shuffled = new LinkedHashMap<>();
            for (String key : keys) shuffled.put(key, original.get(key));
            Object nested = Map.of("future", List.of(TEXT));
            for (int i = 0; i < 16; i++) nested = Map.of("next", nested);
            shuffled.put("futureExtension", nested);
            Path file = Files.writeString(dir.resolve("future.json"), JSON.std.asString(shuffled));
            assertEquals(expected(), ProbeReport.read(file, s -> LIB), "seed=" + seed);
        }
    }

    @Test void longUnicodeConditionMessageSurvivesParsingAndReportSerialization() throws Exception {
        String message = "\u0e44\u0e17\u0e22\ud83d\ude80".repeat(40_000);
        var model = wireModel();
        model.put("beans", List.of());
        model.put("autoConfigs", List.of(Map.of("name", "example.Config", "matched", false,
                "locations", List.of("/library"), "messages", List.of(message))));
        ProbeReport probe = ProbeReport.read(Files.writeString(dir.resolve("long.json"), JSON.std.asString(model)), s -> LIB);
        assertEquals(List.of(message), probe.autoConfigs().get(0).messages());
        var result = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(LIB, "compile")),
                Map.of(LIB, LIB), Map.of(), List.of(probe));
        assertEquals("example.Config did not match: " + message, result.get(0).summary());
        Map<?, ?> dependency = (Map<?, ?>) ((List<?>) JSON.std.mapFrom(Reports.json(result, List.of(probe), List.of()))
                .get("dependencies")).get(0);
        assertEquals(result.get(0).summary(), dependency.get("summary"));
    }

    @Test void reportJsonRoundTripsAsWireModelAndAtomicRewritePreservesUtf8Bytes() throws Exception {
        var result = new Classifier.Result(LIB, "compile", Verdict.UNKNOWN, TEXT,
                List.of(new Evidence(Evidence.Kind.BEAN, TEXT)));
        String json = Reports.json(List.of(result), List.of(expected()), List.of(TEXT));
        Map<String, Object> parsed = JSON.std.mapFrom(json);
        assertEquals(parsed, JSON.std.mapFrom(JSON.std.asString(parsed)));
        Map<?, ?> dependency = (Map<?, ?>) ((List<?>) parsed.get("dependencies")).get(0);
        assertEquals(TEXT, dependency.get("summary"));
        assertEquals(TEXT, ((Map<?, ?>) ((List<?>) dependency.get("evidence")).get(0)).get("detail"));
        Path file = dir.resolve("reports with spaces").resolve("report.json");
        Reports.write(file, json);
        byte[] first = Files.readAllBytes(file);
        Reports.write(file, json);
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), first);
        assertArrayEquals(first, Files.readAllBytes(file));
        try (var files = Files.list(file.getParent())) { assertEquals(List.of(file), files.toList()); }
    }

    @Test void emptyAndWhitespaceOnlyFilesFailWithThePublicIoDiagnostic() throws Exception {
        for (String text : List.of("", " \t\r\n", "\ufeff")) {
            Path file = Files.writeString(dir.resolve("empty.json"), text);
            IOException error = assertThrows(IOException.class, () -> ProbeReport.read(file, s -> LIB));
            assertEquals("Invalid probe report (JSON, schema or field types)", error.getMessage());
        }
    }

    @Test void fileEncodingIsTestedAtJvmStartupRatherThanChangingACachedProperty() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String encoding : List.of("UTF-8", "windows-1252", "UTF-16LE")) {
            Path output = dir.resolve(encoding);
            Files.createDirectory(output);
            Path log = output.resolve("child.log");
            Process child = new ProcessBuilder(java, "-Dfile.encoding=" + encoding, "-cp", classpath,
                    ReportFormatRobustnessTest.class.getName(), output.toString(), encoding)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child JVM timed out: " + encoding);
                assertEquals(0, child.exitValue(), () -> {
                    try { return Files.readString(log, Charset.forName(encoding)); }
                    catch (IOException e) { throw new AssertionError(e); }
                });
                assertEquals(TEXT, ((Map<?, ?>) ((List<?>) JSON.std.mapFrom(Files.readAllBytes(output.resolve("report.json")))
                        .get("dependencies")).get(0)).get("summary"));
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        assertEquals(Charset.forName(args[1]), Charset.defaultCharset());
        Path output = Path.of(args[0]);
        Path probe = Files.write(output.resolve("probe.json"), JSON.std.asString(wireModel()).getBytes(StandardCharsets.UTF_8));
        assertEquals(expected(), ProbeReport.read(probe, s -> LIB));
        var result = new Classifier.Result(LIB, "compile", Verdict.UNKNOWN, TEXT, List.of());
        String json = Reports.json(List.of(result), List.of(expected()), List.of());
        Reports.write(output.resolve("report.json"), json);
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output.resolve("report.json")));
    }

    private static Map<String, Object> wireModel() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("schemaVersion", 1);
        model.put("id", TEXT);
        model.put("fullAutoConfig", true);
        model.put("capturedAt", 1);
        model.put("contextKey", "child");
        model.put("parentContextKey", "parent");
        model.put("profiles", List.of(TEXT));
        model.put("autoConfigs", List.of(Map.of("name", "example.Config", "matched", false,
                "locations", List.of("/library"), "messages", List.of(TEXT), "future", true)));
        model.put("beans", List.of(Map.of("name", TEXT, "type", "example.Bean", "locations", List.of("/library"),
                "factoryBean", "factory", "dependencies", List.of("other"), "sideEffect", true, "future", TEXT)));
        return model;
    }

    private static ProbeReport expected() {
        return new ProbeReport(TEXT, true, List.of(TEXT),
                List.of(new ProbeReport.AutoConfig("example.Config", false, Set.of(LIB), List.of(TEXT))),
                List.of(new ProbeReport.Bean(TEXT, "example.Bean", Set.of(LIB), "factory", List.of("other"), true)),
                "child", "parent", null, 1);
    }
}
