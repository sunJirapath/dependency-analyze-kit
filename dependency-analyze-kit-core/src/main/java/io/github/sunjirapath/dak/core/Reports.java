package io.github.sunjirapath.dak.core;

import com.fasterxml.jackson.jr.ob.JSON;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Console table and report.json. */
public final class Reports {

    private Reports() {}

    public static String table(List<Classifier.Result> results) {
        int dep = "DEPENDENCY".length();
        int verdict = "VERDICT".length();
        for (Classifier.Result r : results) {
            dep = Math.max(dep, r.dependency().toString().length());
            verdict = Math.max(verdict, r.verdict().name().length());
        }
        String row = "%-" + dep + "s  %-" + verdict + "s  %s%n";
        StringBuilder sb = new StringBuilder(String.format(row, "DEPENDENCY", "VERDICT", "EVIDENCE"));
        results.forEach(r -> sb.append(String.format(row, r.dependency(), r.verdict(), r.summary())));
        return sb.toString();
    }

    public static String json(List<Classifier.Result> results, int probeReports) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("probeReports", probeReports);
        root.put("dependencies", results.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dependency", r.dependency().toString());
            m.put("scope", r.scope());
            m.put("verdict", r.verdict().name());
            m.put("summary", r.summary());
            m.put("evidence", r.evidence().stream()
                    .map(e -> Map.of("kind", e.kind().name(), "detail", e.detail()))
                    .toList());
            return m;
        }).toList());
        return JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).asString(root);
    }
}
