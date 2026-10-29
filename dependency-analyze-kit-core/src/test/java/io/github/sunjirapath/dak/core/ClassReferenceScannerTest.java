package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@ClassReferenceScannerTest.Marker
class ClassReferenceScannerTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {}

    @Test
    void findsTypesStoredOnlyInAnnotationMetadata() throws URISyntaxException, java.io.IOException {
        Path classes = Path.of(ClassReferenceScannerTest.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        Set<String> references = ClassReferenceScanner.scan(Set.of(classes));
        String annotationType = "io.github.sunjirapath.dak.core.ClassReferenceScannerTest";
        assertTrue(references.contains(annotationType + (char) 36 + "Marker"), references::toString);
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
