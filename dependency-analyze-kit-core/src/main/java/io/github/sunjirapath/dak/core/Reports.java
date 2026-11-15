package io.github.sunjirapath.dak.core;

import com.fasterxml.jackson.jr.ob.JSON;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Console table and report.json. */
public final class Reports {

    private Reports() {}

    public static String table(List<Classifier.Result> results) {
        int dep = "DEPENDENCY".length();
        int verdict = "VERDICT".length();
        for (Classifier.Result r : results) {
            dep = Math.max(dep, console(r.dependency().toString()).length());
            verdict = Math.max(verdict, r.verdict().name().length());
        }
        String row = "%-" + dep + "s  %-" + verdict + "s  %s%n";
        StringBuilder sb = new StringBuilder(String.format(row, "DEPENDENCY", "VERDICT", "EVIDENCE"));
        results.forEach(r -> sb.append(String.format(row, console(r.dependency().toString()), r.verdict(), console(r.summary()))));
        return sb.toString();
    }

    private static String console(String value) {
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(c -> {
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029) {
                out.append(String.format("\\u%04x", c));
            } else {
                out.appendCodePoint(c);
            }
        });
        return out.toString().replace("::", "\\u003a\\u003a");
    }

    /** Publish complete reports without following a pre-existing destination symlink. */
    public static void write(Path file, String json) throws IOException {
        file = file.toAbsolutePath().normalize();
        Path parent = file.getParent();
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        for (Path p = parent; p != null; p = p.getParent()) {
            // Trust platform ancestors of the JVM temp root (macOS /var is a symlink).
            if (p.equals(tempRoot)) break;
            if (Files.isSymbolicLink(p)) throw new IOException("Symbolic report directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".dak-", ".tmp");
        try {
            Files.writeString(temporary, json);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Legacy count-only callers cannot establish provenance, so their reports are incomplete. */
    public static String json(List<Classifier.Result> results, int probeReports) throws IOException {
        return json(results, probeReports, List.of(), List.of("Probe provenance not supplied"));
    }

    public static List<String> incompleteReasons(List<Classifier.Result> results, List<ProbeReport> probes,
                                                 List<String> problems) {
        List<String> reasons = new ArrayList<>(problems);
        if (probes.stream().noneMatch(ProbeReport::fullAutoConfig)) {
            reasons.add("Current full-context probe evidence required");
        }
        if (results.stream().anyMatch(r -> r.verdict() == Verdict.UNKNOWN)) {
            reasons.add("Unknown dependency verdicts");
        }
        return reasons.stream().distinct().sorted().toList();
    }

    /** Callers must supply only current, module-qualified probes and every known analysis problem. */
    public static String json(List<Classifier.Result> results, List<ProbeReport> probes, List<String> problems)
            throws IOException {
        return json(results, probes.size(), probes, incompleteReasons(results, probes, problems));
    }

    private static String json(List<Classifier.Result> results, int probeReports, List<ProbeReport> probes,
                               List<String> reasons) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Properties version = new Properties();
        try (InputStream in = Reports.class.getResourceAsStream("dak-version.properties")) {
            if (in == null) throw new IOException("Missing DAK tool version resource");
            version.load(in);
        }
        root.put("schemaVersion", 1);
        root.put("tool", Map.of("name", "dependency-analyze-kit", "version", version.getProperty("version")));
        root.put("analysis", Map.of("status", reasons.isEmpty() ? "COMPLETE" : "INCOMPLETE", "reasons", reasons));
        root.put("probeReports", probeReports);
        root.put("contexts", probes.stream().sorted(Comparator.comparing(
                (ProbeReport p) -> p.contextKey() == null ? "" : p.contextKey()).thenComparing(
                p -> p.id() == null ? "" : p.id()).thenComparingLong(ProbeReport::capturedAt)).map(p -> {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("id", p.id());
            context.put("contextKey", p.contextKey());
            context.put("parentContextKey", p.parentContextKey());
            context.put("capturedAt", p.capturedAt());
            context.put("fullAutoConfig", p.fullAutoConfig());
            context.put("profiles", p.profiles().stream().distinct().sorted().toList());
            return context;
        }).toList());
        root.put("dependencies", results.stream().sorted(Comparator
                .comparing((Classifier.Result r) -> r.dependency().toString()).thenComparing(Classifier.Result::scope)).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dependency", r.dependency().toString());
            m.put("scope", r.scope());
            m.put("verdict", r.verdict().name());
            m.put("summary", r.summary());
            m.put("evidence", r.evidence().stream()
                    .sorted(Comparator.comparing((Evidence e) -> e.kind().name()).thenComparing(Evidence::detail))
                    .map(e -> Map.of("kind", e.kind().name(), "detail", e.detail()))
                    .toList());
            return m;
        }).toList());
        return JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).asString(root);
    }
}
