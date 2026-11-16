package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.jr.ob.JSON;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassPathPlatformTest {
    private static final ArtifactId LIB = new ArtifactId("example", "library");
    @TempDir Path dir;

    @Test void jarAndClassDirectoryWorkWithSpacesUnicodeAndPathsOverTwoHundredCharacters() throws Exception {
        Path nested = dir.resolve("space \u0e44\u0e17\u0e22");
        while (nested.toAbsolutePath().toString().length() < 210) nested = nested.resolve("long directory segment");
        Files.createDirectories(nested);
        Path jar = jar(nested.resolve("library with spaces.jar"), "example/Api.class");
        Path classes = Files.createDirectory(nested.resolve("project classes"));
        try (var in = ClassPathPlatformTest.class.getResourceAsStream("ClassPathPlatformTest.class")) {
            assertNotNull(in);
            Files.copy(in, classes.resolve("Consumer.class"));
        }
        var references = ClassReferenceScanner.scan(List.of(classes));
        assertTrue(references.contains("io.github.sunjirapath.dak.core.ClassReferenceScanner"));
        assertEquals(Map.of(LIB, Set.of("example.Api")), ClassReferenceScanner.owners(Set.of("example.Api"), Map.of(LIB, jar)));
        assertEquals(Map.of(), DynamicRegistrations.scan(Map.of(LIB, jar)));
        Path probe = Files.writeString(nested.resolve("context.json"), JSON.std.asString(Map.of(
                "beans", List.of(Map.of("name", "client", "locations", List.of(jar.toString()), "dependencies", List.of())))));
        assertEquals(Set.of(LIB), ProbeReport.read(probe, Map.of(ProbeReport.location(jar), LIB)::get).beans().get(0).artifacts());
    }

    @Test void existingAndMissingLocationsNormalizeRelativeAbsoluteAndDotDotForms() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("module with spaces"));
        Files.createDirectory(folder.resolve("child"));
        Path jar = jar(folder.resolve("library.jar"), "example/Api.class");
        Path working = Path.of("").toAbsolutePath();
        List<Path> forms = new ArrayList<>(List.of(jar.toAbsolutePath(),
                folder.resolve("child/../library.jar"), folder.resolve("./library.jar")));
        if (working.getRoot().equals(jar.toAbsolutePath().getRoot())) forms.add(working.relativize(jar.toAbsolutePath()));
        if (java.io.File.separatorChar == '\\') {
            forms.add(Path.of(jar.toString().replace('\\', '/')));
            forms.add(Path.of(jar.toString().toUpperCase(java.util.Locale.ROOT)));
        }
        String canonical = ProbeReport.location(jar);
        for (Path form : forms) {
            assertEquals(canonical, ProbeReport.location(form), form.toString());
            assertEquals(Map.of(LIB, Set.of("example.Api")), ClassReferenceScanner.owners(Set.of("example.Api"), Map.of(LIB, form)));
        }
        Path absent = folder.resolve("absent.jar");
        assertEquals(absent.toAbsolutePath().normalize().toString(), ProbeReport.location(folder.resolve("child/../absent.jar")));
        Path relativeMissing = Path.of("target", "..", "target", "round-four-absent.jar");
        assertEquals(relativeMissing.toAbsolutePath().normalize().toString(), ProbeReport.location(relativeMissing));
        var report = new ProbeReport("app", true, List.of(), List.of(), List.of(), "key", null,
                folder.resolve("child/..").toString(), 1);
        assertTrue(report.belongsTo(folder));
    }

    @Test void classNamesAndArtifactCoordinatesRemainCaseSensitiveInsideAnArchive() throws Exception {
        Path jar = jar(dir.resolve("case.jar"), "example/Api.class", "example/api.class");
        ArtifactId upper = new ArtifactId("example", "Library");
        Map<ArtifactId, Path> jars = new LinkedHashMap<>();
        jars.put(upper, jar);
        jars.put(LIB, jar);
        assertEquals(Map.of(upper, Set.of("example.Api", "example.api")),
                ClassReferenceScanner.owners(Set.of("example.Api", "example.api", "example.API"), jars));
        // Equal package scores intentionally use classpath order, not a sorted artifact order.
        Map<ArtifactId, Path> reversed = new LinkedHashMap<>();
        reversed.put(LIB, jar);
        reversed.put(upper, jar);
        assertEquals(Map.of(LIB, Set.of("example.Api", "example.api")),
                ClassReferenceScanner.owners(Set.of("example.Api", "example.api"), reversed));
        assertNotEquals(upper, LIB);
        assertFalse(upper.matches("example:library"));
    }

    @Test void everyTruncatedArchiveRetainsUnknownVerdictThroughDynamicRegistrationEvidence() throws Exception {
        Path source = jar(dir.resolve("complete.jar"), "example/Api.class");
        byte[] valid = Files.readAllBytes(source);
        for (int length : new int[]{0, 4, valid.length / 2, valid.length - 1}) {
            Path broken = Files.write(dir.resolve("truncated-" + length + ".jar"), Arrays.copyOf(valid, length));
            IOException error = assertThrows(IOException.class, () ->
                    ClassReferenceScanner.owners(Set.of("example.Api"), Map.of(LIB, broken)));
            assertNotNull(error.getMessage());
            var registrations = DynamicRegistrations.scan(Map.of(LIB, broken));
            assertEquals(Map.of(LIB, "jar unreadable"), registrations);
            var results = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(LIB, "compile")),
                    Map.of(LIB, LIB), Map.of(), List.of(new ProbeReport("full", true, List.of(), List.of(), List.of())),
                    List.of(), registrations);
            assertEquals(Verdict.UNKNOWN, results.get(0).verdict(), "length=" + length);
            assertTrue(results.get(0).summary().contains("jar unreadable"));
        }
    }

    @Test void corruptCompressedServiceEntryRetainsUnknownEvenWithAReadableCentralDirectory() throws Exception {
        Path file = jar(dir.resolve("corrupt-entry.jar"), "META-INF/services/example.Service");
        byte[] archive = Files.readAllBytes(file);
        int nameLength = Byte.toUnsignedInt(archive[26]) | Byte.toUnsignedInt(archive[27]) << 8;
        int extraLength = Byte.toUnsignedInt(archive[28]) | Byte.toUnsignedInt(archive[29]) << 8;
        // DEFLATE block type 3 is reserved; the ZIP directory and entry identity remain intact.
        archive[30 + nameLength + extraLength] = 7;
        Files.write(file, archive);
        try (var jar = new java.util.jar.JarFile(file.toFile())) {
            assertNotNull(jar.getJarEntry("META-INF/services/example.Service"));
            assertThrows(IOException.class, () -> {
                try (var input = jar.getInputStream(jar.getJarEntry("META-INF/services/example.Service"))) { input.read(); }
            });
        }
        var registrations = DynamicRegistrations.scan(Map.of(LIB, file));
        assertEquals(Map.of(LIB, "jar unreadable"), registrations);
        var result = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(LIB, "compile")),
                Map.of(LIB, LIB), Map.of(), List.of(new ProbeReport("full", true, List.of(), List.of(), List.of())),
                List.of(), registrations).get(0);
        assertEquals(Verdict.UNKNOWN, result.verdict());
    }

    private static Path jar(Path file, String... entries) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(new byte[]{1, 2, 3});
                out.closeEntry();
            }
        }
        return file;
    }
}
