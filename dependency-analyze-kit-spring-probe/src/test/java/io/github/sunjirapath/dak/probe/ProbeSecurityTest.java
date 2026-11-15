package io.github.sunjirapath.dak.probe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.core.env.MapPropertySource;
import org.springframework.context.support.GenericApplicationContext;

class ProbeSecurityTest {
    @TempDir Path dir;

    @Test void secretPropertyAndUrlCredentialLeakAttackIsOmitted() throws Exception {
        // Kills copying actual environment property values into default condition messages.
        Path output = dir.resolve("probe");
        capture(output);
        try (var files = Files.list(output)) {
            String json = Files.readString(files.findFirst().orElseThrow());
            assertFalse(json.contains("planted-secret"));
            assertFalse(json.contains("user:password"));
            assertTrue(json.contains("condition message omitted"));
        }
    }

    @Test void symlinkOutputDirectoryEscapeAttackIsRejected() throws Exception {
        // Kills writing probe files through an attacker-controlled build-directory link.
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Path link = symlink(dir.resolve("linked-target"), outside);
        capture(link);
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }

    @Test void existingOutputFileOverwriteAttackPreservesBytes() throws Exception {
        // Kills truncating a regular file when the configured output directory collides with it.
        Path output = Files.writeString(dir.resolve("existing"), "precious");
        capture(output);
        assertEquals("precious", Files.readString(output));
    }

    @Test void probeFailureLogSecretAndWorkflowInjectionAttackIsRedacted() throws Exception {
        // Kills printing the untrusted context ID and exception path on the probe's failure path.
        Path output = Files.writeString(dir.resolve("secret-path"), "collision");
        var previous = System.err;
        var bytes = new java.io.ByteArrayOutputStream();
        try (var errors = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(errors);
            capture(output, "planted-secret\n::error::forged\u001b[2J");
        } finally {
            System.setErr(previous);
        }
        String message = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(message.contains("probe failed"));
        assertFalse(message.contains("planted-secret"));
        assertFalse(message.contains("secret-path"));
        assertFalse(message.contains("::error::"));
        assertFalse(message.contains("\u001b"));
    }

    @Test void priorJvmFilenameCollisionAttackPreservesExistingReport() throws Exception {
        // Kills predictable pid/counter filenames overwriting reports from a reused pid or classloader.
        Path output = Files.createDirectory(dir.resolve("probe"));
        var field = ProbeInitializer.class.getDeclaredField("COUNTER");
        field.setAccessible(true);
        int next = ((java.util.concurrent.atomic.AtomicInteger) field.get(null)).get() + 1;
        Path existing = Files.writeString(output.resolve("context-" + ProcessHandle.current().pid() + "-" + next + ".json"), "precious");
        capture(output);
        assertEquals("precious", Files.readString(existing));
        try (var files = Files.list(output)) {
            var reports = files.filter(p -> !p.equals(existing)).toList();
            assertEquals(1, reports.size());
            for (Path file : reports) {
                assertTrue(file.getFileName().toString().matches("context-.*\\.json"));
                String json = Files.readString(file);
                assertTrue(json.startsWith("{"));
                assertTrue(json.endsWith("}"));
                assertTrue(json.contains("\"beans\":["));
            }
        }
    }

    private static void capture(Path output) throws Exception {
        capture(output, "security-test");
    }

    private static void capture(Path output, String id) throws Exception {
        String enabled = System.getProperty("dak.enabled");
        String directory = System.getProperty("dak.outputDirectory");
        String messages = System.getProperty("dak.includeConditionMessages");
        try {
            System.setProperty("dak.enabled", "true");
            System.setProperty("dak.outputDirectory", output.toString());
            System.clearProperty("dak.includeConditionMessages");
            try (GenericApplicationContext context = new GenericApplicationContext()) {
                context.setId(id);
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("attack", Map.of(
                        "private.token", "planted-secret", "private.url", "https://user:password@example.invalid")));
                ConditionEvaluationReport.get(context.getBeanFactory()).recordConditionEvaluation("example.Config",
                        (conditionContext, metadata) -> false, ConditionOutcome.noMatch(
                                context.getEnvironment().getProperty("private.token") + " "
                                        + context.getEnvironment().getProperty("private.url")));
                new ProbeInitializer().initialize(context);
                context.refresh();
            }
        } finally {
            restore("dak.enabled", enabled); restore("dak.outputDirectory", directory);
            restore("dak.includeConditionMessages", messages);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    private static Path symlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlinks unavailable");
            throw new AssertionError(e);
        }
    }
}
