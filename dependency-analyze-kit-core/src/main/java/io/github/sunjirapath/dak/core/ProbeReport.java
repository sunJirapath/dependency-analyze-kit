package io.github.sunjirapath.dak.core;

import com.fasterxml.jackson.jr.ob.JSON;
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
                          List<AutoConfig> autoConfigs, List<Bean> beans, String contextKey, String parentContextKey) {

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
        Map<String, Object> m = JSON.std.mapFrom(Files.readString(file));
        return new ProbeReport(
                (String) m.get("id"),
                Boolean.TRUE.equals(m.get("fullAutoConfig")),
                strings(m.get("profiles")),
                maps(m.get("autoConfigs")).stream()
                        .map(a -> new AutoConfig((String) a.get("name"), Boolean.TRUE.equals(a.get("matched")),
                                artifacts(a.get("locations"), locate), strings(a.get("messages"))))
                        .toList(),
                maps(m.get("beans")).stream()
                        .map(b -> new Bean((String) b.get("name"), (String) b.get("type"),
                                artifacts(b.get("locations"), locate), (String) b.get("factoryBean"),
                                strings(b.get("dependencies"))))
                        .toList(), (String) m.get("contextKey"), (String) m.get("parentContextKey"));
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

    private static Set<ArtifactId> artifacts(Object locations, Function<String, ArtifactId> locate) {
        Set<ArtifactId> out = new LinkedHashSet<>();
        for (String s : strings(locations)) {
            ArtifactId id = locate.apply(s);
            if (id == null) id = locate.apply(location(Path.of(s)));
            if (id != null) out.add(id);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        return o == null ? List.of() : (List<String>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }
}
