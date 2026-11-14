package io.github.sunjirapath.dak.probe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

class ProbeInitializerTest {
    @Test
    void conditionMessagesAreSanitizedByDefault(@TempDir Path dir) throws Exception {
        String json = capture(dir, null);
        assertFalse(json.contains("secret-token-123"), json);
        assertTrue(json.contains("SecretCondition: condition message omitted"), json);
        assertTrue(json.contains("\"matched\":false"), json);
    }

    @Test
    void rawConditionMessagesRequireExplicitOptIn(@TempDir Path dir) throws Exception {
        assertTrue(capture(dir, "true").contains("secret-token-123"));
    }

    private static String capture(Path dir, String includeMessages) throws Exception {
        String enabled = System.getProperty("dak.enabled");
        String output = System.getProperty("dak.outputDirectory");
        String messages = System.getProperty("dak.includeConditionMessages");
        try {
            System.setProperty("dak.enabled", "true");
            System.setProperty("dak.outputDirectory", dir.toString());
            restore("dak.includeConditionMessages", includeMessages);
            try (GenericApplicationContext context = new GenericApplicationContext()) {
                ConditionEvaluationReport.get(context.getBeanFactory()).recordConditionEvaluation(
                        "demo.SecretAutoConfiguration", new SecretCondition(),
                        ConditionOutcome.noMatch("credential=secret-token-123"));
                new ProbeInitializer().initialize(context);
                context.refresh();
            }
            try (var files = Files.list(dir)) {
                return Files.readString(files.filter(p -> p.getFileName().toString().startsWith("context-"))
                        .findFirst().orElseThrow());
            }
        } finally {
            restore("dak.enabled", enabled);
            restore("dak.outputDirectory", output);
            restore("dak.includeConditionMessages", messages);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    static class SecretCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return false;
        }
    }
}
