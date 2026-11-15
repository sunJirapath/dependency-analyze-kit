package io.github.sunjirapath.dak.core;

import com.fasterxml.jackson.jr.ob.JSON;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** One application context as dumped by the spring-probe, with class locations resolved to artifacts. */
public record ProbeReport(String id, boolean fullAutoConfig, List<String> profiles,
                          List<AutoConfig> autoConfigs, List<Bean> beans, String contextKey, String parentContextKey,
                          String projectDirectory, long capturedAt) {

    public ProbeReport(String id, boolean fullAutoConfig, List<String> profiles,
                       List<AutoConfig> autoConfigs, List<Bean> beans, String contextKey, String parentContextKey) {
        this(id, fullAutoConfig, profiles, autoConfigs, beans, contextKey, parentContextKey, null, 0L);
    }

    /** Compatibility with reports collected before context hierarchy identifiers were recorded. */
    public ProbeReport(String id, boolean fullAutoConfig, List<String> profiles,
                       List<AutoConfig> autoConfigs, List<Bean> beans) {
        this(id, fullAutoConfig, profiles, autoConfigs, beans, null, null);
    }

    /** A condition-report source: an auto-configuration (or other conditional) class. */
    public record AutoConfig(String name, boolean matched, Set<ArtifactId> artifacts, List<String> messages) {}

    /** A bean definition: the artifacts its classes came from and the beans injected into it. */
    public record Bean(String name, String type, Set<ArtifactId> artifacts, String factoryBean, List<String> dependencies) {}

    /**
     * Reads one probe file. {@code locate} maps a class location (jar file or classes directory), as recorded or as
     * keyed by {@link #location}, to its artifact, {@link ArtifactId#PROJECT} for the project's own output, or null when unknown.
     */
    public static ProbeReport read(Path file, Function<String, ArtifactId> locate) throws IOException {
        return read(file, locate, new java.util.ArrayList<>());
    }

    /** Preserve diagnostics across all contexts: another valid context cannot repair missing evidence. */
    public static ProbeReport read(Path file, Function<String, ArtifactId> locate, List<String> problems) throws IOException {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Probe report must be a regular file");
        }
        byte[] data;
        try (var in = Files.newInputStream(file)) {
            data = in.readNBytes(16 * 1024 * 1024 + 1);
        }
        if (data.length > 16 * 1024 * 1024) throw new IOException("Probe report exceeds 16 MiB");
        try {
            JsonFactory factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64).maxStringLength(1024 * 1024).maxNumberLength(20).build()).build();
            try (JsonParser parser = factory.createParser(data)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Expected probe object");
                int depth = 1;
                while (depth > 0) {
                    JsonToken token = parser.nextToken();
                    if (token == null) throw new IOException("Truncated probe object");
                    if (token.isStructStart()) depth++;
                    if (token.isStructEnd()) depth--;
                    if (token == JsonToken.VALUE_STRING) parser.getText();
                }
                if (parser.nextToken() != null) throw new IOException("Trailing probe data");
            }
            return decode(JSON.std.mapFrom(data), locate, problems);
        } catch (RuntimeException | IOException e) {
            // Parser exceptions can contain source excerpts, including credentials.
            throw new IOException("Invalid probe report (JSON, schema or field types)");
        }
    }

    private static ProbeReport decode(Map<String, Object> m, Function<String, ArtifactId> locate,
                                      List<String> problems) throws IOException {
        if (m.containsKey("schemaVersion") && !Integer.valueOf(1).equals(m.get("schemaVersion"))) {
            throw new IOException("Unsupported probe schema");
        }
        Set<String> names = new LinkedHashSet<>();
        boolean complete = m.get("autoConfigs") instanceof List && m.get("beans") instanceof List
                && m.get("profiles") instanceof List && m.get("id") instanceof String id && !id.isBlank()
                && m.get("capturedAt") instanceof Number time && time.longValue() > 0;
        for (Map<String, Object> config : maps(m.get("autoConfigs"))) {
            complete &= config.get("locations") instanceof List && config.get("messages") instanceof List
                    && config.get("matched") instanceof Boolean;
        }
        for (Map<String, Object> bean : maps(m.get("beans"))) {
            if (!(bean.get("name") instanceof String name) || !names.add(name)) {
                throw new IOException("Missing or duplicate bean identity");
            }
            complete &= bean.get("locations") instanceof List && bean.get("dependencies") instanceof List;
        }
        int problemsBeforeLocations = problems.size();
        List<AutoConfig> autoConfigs = maps(m.get("autoConfigs")).stream()
                .map(a -> new AutoConfig(requiredString(a.get("name")), Boolean.TRUE.equals(a.get("matched")),
                        artifacts(a.get("locations"), locate, problems), strings(a.get("messages"))))
                .toList();
        List<Bean> beans = maps(m.get("beans")).stream()
                .map(b -> new Bean((String) b.get("name"), (String) b.get("type"),
                        artifacts(b.get("locations"), locate, problems), (String) b.get("factoryBean"),
                        strings(b.get("dependencies"))))
                .toList();
        complete &= problems.size() == problemsBeforeLocations;
        if (Boolean.TRUE.equals(m.get("fullAutoConfig")) && !complete) {
            problems.add("Incomplete full-context probe report");
        }
        if (m.get("capturedAt") instanceof Number captured && captured.longValue() > System.currentTimeMillis()) {
            problems.add("Future probe capture timestamp");
        }
        return new ProbeReport(
                (String) m.get("id"),
                Boolean.TRUE.equals(m.get("fullAutoConfig")) && complete,
                strings(m.get("profiles")),
                autoConfigs, beans, (String) m.get("contextKey"), (String) m.get("parentContextKey"),
                (String) m.get("projectDirectory"),
                m.get("capturedAt") instanceof Number time ? time.longValue() : 0L);
    }

    /** Missing identity (including older probe versions) cannot establish which module produced evidence. */
    public boolean belongsTo(Path project) {
        if (projectDirectory == null || projectDirectory.isBlank() || capturedAt > System.currentTimeMillis()) return false;
        try {
            Path producer = Path.of(projectDirectory);
            return producer.isAbsolute() && location(producer).equals(location(project));
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    /**
     * The key a class location is looked up by: its real path. {@code getCanonicalPath} resolves links on Windows only
     * from JDK 24, so a test JVM and a build JVM on different JDKs would key a jar under a junction differently.
     */
    public static String location(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static Set<ArtifactId> artifacts(Object locations, Function<String, ArtifactId> locate, List<String> problems) {
        Set<ArtifactId> out = new LinkedHashSet<>();
        for (String s : strings(locations)) {
            ArtifactId id = locate.apply(s);
            if (id == null) id = locate.apply(location(Path.of(s)));
            if (id != null) out.add(id);
            else problems.add("Unmapped probe class location");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        if (o == null) return List.of();
        List<?> values = (List<?>) o;
        values.forEach(ProbeReport::requiredString);
        return (List<String>) values;
    }

    private static String requiredString(Object value) {
        if (!(value instanceof String s)) throw new IllegalArgumentException("Expected string");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }
}
