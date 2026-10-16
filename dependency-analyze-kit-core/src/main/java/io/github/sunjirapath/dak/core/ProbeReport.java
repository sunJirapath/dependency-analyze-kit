package io.github.sunjirapath.dak.core;

import com.fasterxml.jackson.jr.ob.JSON;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** One application context as dumped by the spring-probe, with class locations resolved to artifacts. */
public record ProbeReport(String id, boolean fullAutoConfig, List<String> profiles,
                          List<AutoConfig> autoConfigs, List<Bean> beans) {

    /** A condition-report source: an auto-configuration (or other conditional) class. */
    public record AutoConfig(String name, boolean matched, Set<ArtifactId> artifacts, List<String> messages) {}

    /** A bean definition: the artifacts its classes came from and the beans injected into it. */
    public record Bean(String name, String type, Set<ArtifactId> artifacts, String factoryBean, List<String> dependencies) {}

    /**
     * Reads one probe file. {@code locate} maps a recorded class location (jar file or classes directory)
     * to its artifact, {@link ArtifactId#PROJECT} for the project's own output, or null when unknown.
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
                        .toList());
    }

    private static Set<ArtifactId> artifacts(Object locations, Function<String, ArtifactId> locate) {
        Set<ArtifactId> out = new LinkedHashSet<>();
        strings(locations).stream().map(locate).filter(Objects::nonNull).forEach(out::add);
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
