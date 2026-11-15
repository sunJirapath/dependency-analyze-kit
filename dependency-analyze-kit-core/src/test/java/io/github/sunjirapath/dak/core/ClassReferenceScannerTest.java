package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sunjirapath.dak.core.fixtures.AnnotationConsumer;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassReferenceScannerTest {

    @Test
    void findsTypesStoredOnlyInAnnotationMetadata(@TempDir Path dir) throws IOException {
        // Only the consumer is scanned; Tag.class is in the external JUnit jar, never in this directory.
        try (var in = AnnotationConsumer.class.getResourceAsStream("AnnotationConsumer.class")) {
            Files.copy(in, dir.resolve("AnnotationConsumer.class"));
        }
        Set<String> references = ClassReferenceScanner.scan(Set.of(dir));
        assertTrue(references.contains("org.junit.jupiter.api.Tag"), references::toString);
    }

    @Test
    void invalidMagicFailsClosed(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("Broken.class"), new byte[]{0, 0, 0, 0});
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(Set.of(dir)));
    }

    @Test
    void truncatedConstantPoolsFailClosed(@TempDir Path dir) throws IOException {
        for (int tag : new int[]{1, 3, 5, 7, 15}) {
            pool(dir, tag);
            assertThrows(IOException.class, () -> ClassReferenceScanner.scan(Set.of(dir)), "tag=" + tag);
        }
    }

    @Test
    void unknownConstantPoolTagFailsClosed(@TempDir Path dir) throws IOException {
        pool(dir, 99);
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(Set.of(dir)));
    }

    @Test
    void invalidClassIndexFailsWithIOException(@TempDir Path dir) throws IOException {
        Path file = pool(dir, 7);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file,
                java.nio.file.StandardOpenOption.APPEND))) {
            out.writeShort(99);
        }
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(Set.of(dir)));
    }

    @Test
    void unicodeAnnotationDescriptorsAreScanned(@TempDir Path dir) throws IOException {
        Path file = pool(dir, 1);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file,
                java.nio.file.StandardOpenOption.APPEND))) {
            out.writeUTF("Lexample/\u6ce8\u91c8;");
        }
        assertTrue(ClassReferenceScanner.scan(Set.of(dir)).contains("example.\u6ce8\u91c8"));
    }

    @Test
    void missingAndNullRootsAreIgnored(@TempDir Path dir) throws IOException {
        assertEquals(Set.of(), ClassReferenceScanner.scan(java.util.Arrays.asList(null, dir.resolve("missing"))));
    }

    @Test
    void versionedOnlyClassesBelongToMultiReleaseJar(@TempDir Path dir) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path file = dir.resolve("multi-release.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/versions/9/example/Versioned.class"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("META-INF/versions/999/example/Future.class"));
            out.closeEntry();
        }
        ArtifactId owner = new ArtifactId("example", "multi-release");
        assertEquals(Map.of(owner, Set.of("example.Versioned")), ClassReferenceScanner.owners(
                Set.of("example.Versioned", "example.Future"), Map.of(owner, file)));
    }

    @Test
    void versionDirectoriesWithoutMultiReleaseManifestAreNotOwners(@TempDir Path dir) throws IOException {
        Path file = jar(dir.resolve("ordinary.jar"), "META-INF/versions/9/example/Versioned.class");
        assertEquals(Map.of(), ClassReferenceScanner.owners(Set.of("example.Versioned"),
                Map.of(new ArtifactId("example", "ordinary"), file)));
    }

    private static Path pool(Path dir, int tag) throws IOException {
        Path file = dir.resolve("Pool.class");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(61);
            out.writeShort(2);
            out.writeByte(tag);
        }
        return file;
    }

    @Test
    void shadedCopyIsNotCreditedOverTheRealOwner(@TempDir Path dir) throws java.io.IOException {
        String nullMarked = "org.jspecify.annotations.NullMarked";
        ArtifactId fat = new ArtifactId("com.example", "agent");
        ArtifactId owner = new ArtifactId("org.jspecify", "jspecify");
        Map<ArtifactId, Path> jars = new LinkedHashMap<>();
        jars.put(fat, jar(dir.resolve("agent.jar"), "org/jspecify/annotations/NullMarked.class",
                "com/example/Agent.class"));
        jars.put(owner, jar(dir.resolve("jspecify.jar"), "org/jspecify/annotations/NullMarked.class"));

        Map<ArtifactId, Set<String>> owners = ClassReferenceScanner.owners(
                Set.of(nullMarked, "com.example.Agent"), jars);

        assertEquals(Map.of(owner, Set.of(nullMarked), fat, Set.of("com.example.Agent")), owners);
    }

    private static Path jar(Path file, String... entries) throws java.io.IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.closeEntry();
            }
        }
        return file;
    }
}
