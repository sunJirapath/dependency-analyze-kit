package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DynamicRegistrationsTest {
    private static final ArtifactId LIB = new ArtifactId("example", "provider");
    @TempDir Path dir;

    @Test void servicesFileRetains() throws IOException {
        assertEquals(Map.of(LIB, "META-INF/services"), scan(Map.of("META-INF/services/example.Service", "example.Provider")));
    }

    @Test void springFactoriesRetains() throws IOException {
        assertEquals(Map.of(LIB, "spring.factories"), scan(Map.of("META-INF/spring.factories", "example.Listener")));
    }

    @Test void springImportsRetain() throws IOException {
        assertEquals(Map.of(LIB, "spring/*.imports"), scan(Map.of("META-INF/spring/example.Config.imports", "example.Config")));
    }

    @Test void emptyServicesAndOrdinaryResourcesAreIgnored() throws IOException {
        assertEquals(Map.of(), scan(Map.of("META-INF/services/example.Service", "", "example/config.properties", "value")));
    }

    @Test void servicesTakePrecedenceOverSpringMetadata() throws IOException {
        assertEquals(Map.of(LIB, "META-INF/services"), scan(Map.of("META-INF/services/example.Service", "example.Provider",
                "META-INF/spring.factories", "example.Listener", "META-INF/spring/example.Config.imports", "example.Config")));
    }

    @Test void unreadableJarRetains() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.jar"), "not a zip");
        assertEquals(Map.of(LIB, "jar unreadable"), DynamicRegistrations.scan(Map.of(LIB, broken)));
        assertEquals(Map.of(LIB, "jar unreadable"), DynamicRegistrations.scan(Map.of(LIB, dir.resolve("missing.jar"))));
    }

    @Test void unsafeEntriesAndOversizedCompressedMetadataRetain() throws IOException {
        assertEquals(Map.of(LIB, "jar unreadable"), scan(Map.of("../outside", "bad")));
        assertEquals(Map.of(LIB, "jar unreadable"), scan(Map.of("META-INF/services/example.Service", "x".repeat(1024 * 1024 + 1))));
    }

    private Map<ArtifactId, String> scan(Map<String, String> entries) throws IOException {
        Path path = dir.resolve("provider.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return DynamicRegistrations.scan(Map.of(LIB, path));
    }
}
