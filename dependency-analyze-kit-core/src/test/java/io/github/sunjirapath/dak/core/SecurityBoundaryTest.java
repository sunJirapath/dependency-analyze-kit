package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class SecurityBoundaryTest {
    @TempDir Path dir;
    private static final ArtifactId LIB = new ArtifactId("example", "library");

    private ProbeReport read(String text) throws IOException {
        return ProbeReport.read(Files.writeString(dir.resolve("probe.json"), text), location -> LIB);
    }

    @Test void missingEvidenceAttackCannotClaimFullContext() throws Exception {
        // Kills trusting fullAutoConfig when the bean/condition arrays were omitted.
        String complete = "{\"id\":\"full\",\"capturedAt\":1,\"fullAutoConfig\":true,\"profiles\":[],\"autoConfigs\":[],\"beans\":[]}";
        for (String field : List.of("profiles", "autoConfigs", "beans")) {
            ProbeReport probe = read(complete.replace(",\"" + field + "\":[]", ""));
            assertFalse(probe.fullAutoConfig());
            assertEquals(Verdict.UNKNOWN, classify(probe, List.of()).verdict());
        }
    }

    @Test void missingBeanEdgesAttackCannotClaimFullContext() throws Exception {
        // Kills claiming completeness after dropping an individual bean's locations or injection edges.
        for (String bean : List.of("{\"name\":\"app\",\"dependencies\":[]}", "{\"name\":\"app\",\"locations\":[]}")) {
            ProbeReport probe = read("{\"id\":\"full\",\"capturedAt\":1,\"fullAutoConfig\":true,\"profiles\":[],\"autoConfigs\":[],\"beans\":[" + bean + "]}");
            assertFalse(probe.fullAutoConfig());
            assertEquals(Verdict.UNKNOWN, classify(probe, List.of()).verdict());
        }
    }

    @Test void missingProvenanceFieldsAttackCannotClaimFullContext() throws Exception {
        // Kills treating an identity-less/undated cached object as complete runtime evidence.
        for (String header : List.of("\"id\":\"full\",", "\"capturedAt\":1,")) {
            ProbeReport probe = read("{" + header + "\"fullAutoConfig\":true,\"profiles\":[],\"autoConfigs\":[],\"beans\":[]}");
            assertFalse(probe.fullAutoConfig());
        }
    }

    @Test void trailingJsonObjectAttackIsRejected() {
        // Kills accepting the first object and silently ignoring a second report payload.
        assertThrows(IOException.class, () -> read("{} {}"));
    }

    @Test void duplicateJsonKeyAttackIsRejected() {
        // Kills last-key-wins parsing that lets a duplicate field replace evidence.
        assertThrows(IOException.class, () -> read("{\"beans\":[],\"beans\":[]}"));
    }

    @Test void unsupportedSchemaAttackIsRejected() {
        // Kills accepting a foreign schema as current evidence.
        assertThrows(IOException.class, () -> read("{\"schemaVersion\":999}"));
    }

    @Test void duplicateBeanIdentityAttackIsRejected() {
        // Kills bean-map overwrite which can erase project dependency edges.
        assertThrows(IOException.class, () -> read("{\"beans\":[{\"name\":\"app\"},{\"name\":\"app\"}]}"));
    }

    @Test void malformedAndWrongTypeJsonAttacksFailClearly() {
        // Kills unchecked casts and null auto-configuration identities.
        for (String text : List.of("{", "{\"profiles\":[4]}", "{\"autoConfigs\":[{}]}", "{\"beans\":42}")) {
            assertThrows(IOException.class, () -> read(text), text);
        }
    }

    @Test void deepJsonAttackIsRejected() {
        // Kills removal of the explicit pre-materialization nesting limit.
        assertThrows(IOException.class, () -> read("{\"extra\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"));
    }

    @Test void oversizedJsonAttackIsRejected() {
        // Kills unbounded readString before parsing (valid, otherwise harmless JSON).
        String value = "\"" + "x".repeat(900000) + "\",";
        assertThrows(IOException.class, () -> read("{\"extra\":[" + value.repeat(19) + "\"end\"]}"));
    }

    @Test void parserErrorSecretLeakAttackIsRedacted() {
        // Kills propagating Jackson source excerpts in exception causes/messages.
        IOException error = assertThrows(IOException.class, () -> read("{\"password\":plantedSecretToken123}"));
        assertFalse(error.toString().contains("plantedSecretToken123"));
        assertNull(error.getCause());
    }

    @Test void futureTimestampAndForeignProjectAttacksAreRejected() {
        // Kills accepting future cache evidence and accepting another module's report.
        ProbeReport future = new ProbeReport("id", true, List.of(), List.of(), List.of(), "key", null,
                dir.toAbsolutePath().toString(), Long.MAX_VALUE);
        assertFalse(future.belongsTo(dir));
        ProbeReport foreign = new ProbeReport("id", true, List.of(), List.of(), List.of(), "key", null,
                dir.resolve("other").toAbsolutePath().toString(), 1);
        assertFalse(foreign.belongsTo(dir));
    }

    @Test void incompleteScanAttackNeverProducesUnused() {
        // Kills the classifier guard shared by both plugins after a partial scan/invalid probe.
        assertEquals(Verdict.UNKNOWN, classify(full(), List.of("Annotation reference scan failed")).verdict());
    }

    @Test void incompleteAndFutureProbeCannotHideAlongsideFullContextAttack() throws Exception {
        // Kills losing invalid-context diagnostics just because another full context is available.
        for (String json : List.of("{\"fullAutoConfig\":true}", "{\"capturedAt\":9223372036854775807}")) {
            List<String> problems = new java.util.ArrayList<>();
            ProbeReport bad = ProbeReport.read(Files.writeString(dir.resolve("bad.json"), json), location -> LIB, problems);
            var result = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(LIB, "compile")),
                    Map.of(LIB, LIB), Map.of(), List.of(full(), bad), problems).get(0);
            assertEquals(Verdict.UNKNOWN, result.verdict());
        }
    }

    @Test void truncatedAndAbsurdPoolClassAttacksFailClosed() throws Exception {
        // Kills treating EOF in a large constant pool as zero references.
        Path file = dir.resolve("Bomb.class");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0xCAFEBABE); out.writeShort(0); out.writeShort(61); out.writeShort(65535);
        }
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(List.of(dir)));
    }

    @Test void futureClassVersionAttackFailsClosed() throws Exception {
        // Kills interpreting an unknown class format as an empty reference set.
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("Future.class")))) {
            out.writeInt(0xCAFEBABE); out.writeShort(0); out.writeShort(65535); out.writeShort(1);
        }
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(List.of(dir)));
    }

    @Test void oversizedClassAttackFailsClosed() throws Exception {
        // Kills the byte limit even when the constant pool is syntactically empty.
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("Huge.class")))) {
            out.writeInt(0xCAFEBABE); out.writeShort(0); out.writeShort(61); out.writeShort(1);
            out.write(new byte[16 * 1024 * 1024]);
        }
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(List.of(dir)));
    }

    @Test void classDirectoryAttackFailsClearly() throws Exception {
        // Kills silently dropping a malformed class-shaped build output.
        Files.createDirectory(dir.resolve("Fake.class"));
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(List.of(dir)));
    }

    @Test void nonZipJarAttackFailsClosed() throws Exception {
        // Kills catching a corrupt jar and returning zero owners.
        Path file = Files.writeString(dir.resolve("fake.jar"), "not a zip");
        assertThrows(IOException.class, () -> ClassReferenceScanner.owners(Set.of("example.A"), Map.of(LIB, file)));
    }

    @Test void zipSlipEntryAttackIsRejectedWithoutExtraction() throws Exception {
        // Kills the unsafe jar-name guard; no archive entry is ever extracted.
        Path jar = jar("../outside.class", new byte[0]);
        assertThrows(IOException.class, () -> ClassReferenceScanner.owners(Set.of("example.A"), Map.of(LIB, jar)));
        assertFalse(Files.exists(dir.resolve("outside.class")));
    }

    @Test void compressedManifestBombAttackIsRejected() throws Exception {
        // Kills the manifest expansion bound before JarFile's multi-release lookup.
        String manifest = "Manifest-Version: 1.0\r\nX-Data: x\r\n" + (" " + "x".repeat(70) + "\r\n").repeat(16000) + "\r\n";
        Path jar = jar("META-INF/MANIFEST.MF", manifest.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> ClassReferenceScanner.owners(Set.of("example.A"), Map.of(LIB, jar)));
    }

    @Test void hugeZipEntryCountAttackIsRejected() throws Exception {
        // Kills the entry-count bound even when no reference lookup or extraction is needed.
        Path file = dir.resolve("many.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i <= 100_000; i++) {
                out.putNextEntry(new ZipEntry("entry-" + i)); out.closeEntry();
            }
        }
        assertThrows(IOException.class, () -> ClassReferenceScanner.owners(Set.of(), Map.of(LIB, file)));
    }

    @Test void descriptorRegexBacktrackingAttackIsBounded() throws Exception {
        // Kills repeated suffix matching of a long invalid descriptor in a constant-pool UTF8 entry.
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("Regex.class")))) {
            out.writeInt(0xCAFEBABE); out.writeShort(0); out.writeShort(61); out.writeShort(2);
            out.writeByte(1); out.writeUTF("L".repeat(60000) + "!;");
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(ClassReferenceScanner.scan(List.of(dir)).isEmpty()));
    }

    @Test void duplicateZipEntryAttackIsRejected() throws Exception {
        // Kills duplicate central-directory identity acceptance (ZipOutputStream forbids constructing it directly).
        Path file = dir.resolve("duplicate.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (String name : List.of("a/A.class", "a/B.class")) {
                out.putNextEntry(new ZipEntry(name)); out.closeEntry();
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        byte[] needle = "a/B.class".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            if (java.util.Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) bytes[i + 2] = 'A';
        }
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> ClassReferenceScanner.owners(Set.of("a.A"), Map.of(LIB, file)));
    }

    @Test void symlinkScanEscapeAttackIsRejected() throws Exception {
        // Kills following a class-file symlink out of the selected root; requires symlink-capable test host.
        Path outside = Files.write(dir.resolve("outside.bin"), new byte[0]);
        Path root = Files.createDirectory(dir.resolve("classes"));
        symlink(root.resolve("Leak.class"), outside);
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(List.of(root)));
    }

    @Test void consoleWorkflowAndAnsiInjectionAttackIsEscaped() {
        // Kills raw newline/ESC interpolation in table rows consumed by build loggers.
        Classifier.Result result = new Classifier.Result(new ArtifactId("g\n::error::forged", "a\u001b[2J"),
                "compile", Verdict.UNKNOWN, "summary\r\n::warning::forged\u2028next", List.of());
        String table = Reports.table(List.of(result));
        assertEquals(2, table.lines().count());
        assertFalse(table.contains("\u001b"));
        assertFalse(table.contains("\n::error::"));
        assertFalse(table.contains("\u2028"));
    }

    @Test void jsonOutputInjectionAttackRemainsData() throws Exception {
        // Kills hand-interpolating quotes/control characters into report JSON.
        String hostile = "\"}\n::error::x\u001b<script>|graph TD";
        Classifier.Result result = new Classifier.Result(LIB, "compile", Verdict.UNKNOWN, hostile, List.of());
        Map<?, ?> parsed = com.fasterxml.jackson.jr.ob.JSON.std.mapFrom(Reports.json(List.of(result), 0));
        assertEquals(hostile, ((Map<?, ?>) ((List<?>) parsed.get("dependencies")).get(0)).get("summary"));
    }

    @Test void workflowCommandPrefixInjectionAttackIsEscaped() {
        // Kills escaping only newlines while allowing an artifact to begin a workflow command directly.
        var result = new Classifier.Result(new ArtifactId("::error", ":forged"), "compile", Verdict.UNKNOWN,
                "untrusted metadata", List.of());
        assertFalse(Reports.table(List.of(result)).lines().anyMatch(line -> line.startsWith("::error::")));
    }

    @Test void regexQuoteInjectionAttackIsLiteral() {
        // Kills injecting regex operators by closing the old \\Q quote with \\E.
        ArtifactId hostile = new ArtifactId("g", "\\E(a+)+$");
        assertTrue(hostile.matches("g:\\E(a+)+$"));
        assertFalse(new ArtifactId("g", "aaaa").matches("g:\\E(a+)+$"));
    }

    @Test void invalidPatternDiagnosticInjectionAttackIsRedacted() {
        // Kills echoing a rejected pattern as additional CI/log lines, bypassing table escaping.
        String attack = "bad\n::error::forged";
        var error = assertThrows(IllegalArgumentException.class, () -> new Classifier(List.of(attack), List.of()));
        assertFalse(error.getMessage().contains(attack));
        assertFalse(error.getMessage().contains("\n"));
    }

    @Test void wildcardBacktrackingExhaustionAttackIsBounded() {
        // Kills exponential .* regex backtracking on a failing wildcard suffix.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertFalse(new ArtifactId("g", "a".repeat(100)).matches("g:" + "*a".repeat(30) + "b")));
    }

    @Test void symlinkReportOverwriteAttackDoesNotTouchTarget() throws Exception {
        // Kills direct writeString following a pre-existing report symlink.
        Path target = Files.writeString(dir.resolve("precious.txt"), "keep");
        Path output = dir.resolve("report.json");
        symlink(output, target);
        Reports.write(output, "{}");
        assertEquals("keep", Files.readString(target));
        assertEquals("{}", Files.readString(output));
    }

    @Test void symlinkReportDirectoryEscapeAttackIsRejected() throws Exception {
        // Kills following a parent symlink into an unrelated directory.
        Path target = Files.createDirectory(dir.resolve("outside"));
        Path link = symlink(dir.resolve("link"), target);
        assertThrows(IOException.class, () -> Reports.write(link.resolve("report.json"), "{}"));
        assertFalse(Files.exists(target.resolve("report.json")));
    }

    @Test void nullConstantPoolClassIndexAttackFailsClosed() throws Exception {
        // Kills accepting a CONSTANT_Class which points at the unusable zero slot.
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("Null.class")))) {
            out.writeInt(0xCAFEBABE); out.writeShort(0); out.writeShort(61); out.writeShort(2);
            out.writeByte(7); out.writeShort(0);
        }
        assertThrows(IOException.class, () -> ClassReferenceScanner.scan(List.of(dir)));
    }

    private Path symlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlinks unavailable");
            throw new AssertionError(e);
        }
    }

    private Path jar(String entry, byte[] data) throws IOException {
        Path file = dir.resolve("attack.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(entry)); out.write(data); out.closeEntry();
        }
        return file;
    }

    private static ProbeReport full() {
        return new ProbeReport("app", true, List.of(), List.of(), List.of());
    }

    private static Classifier.Result classify(ProbeReport probe, List<String> problems) {
        return new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(LIB, "compile")),
                Map.of(LIB, LIB), Map.of(), List.of(probe), problems).get(0);
    }
}
