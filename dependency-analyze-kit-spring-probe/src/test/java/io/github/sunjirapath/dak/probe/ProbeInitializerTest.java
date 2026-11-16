package io.github.sunjirapath.dak.probe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
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
        return capture(dir, includeMessages, context -> {});
    }

    private static String capture(Path dir, String includeMessages, Consumer<GenericApplicationContext> beans) throws Exception {
        String enabled = System.getProperty("dak.enabled");
        String output = System.getProperty("dak.outputDirectory");
        String messages = System.getProperty("dak.includeConditionMessages");
        try {
            System.setProperty("dak.enabled", "true");
            System.setProperty("dak.outputDirectory", dir.toString());
            restore("dak.includeConditionMessages", includeMessages);
            try (GenericApplicationContext context = new GenericApplicationContext()) {
                beans.accept(context);
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

    @Test
    void runnersAndListenersAreMarkedButFrameworkAndLifecycleBeansAreNot(@TempDir Path dir) throws Exception {
        String json = capture(dir, null, context -> {
            context.registerBean("runner", InheritedRunner.class);
            context.registerBean("applicationRunner", StartupRunner.class);
            context.registerBean("listener", StartupListener.class);
            context.registerBean("lifecycle", ConnectionLifecycle.class);
            context.registerBean("frameworkListener", org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
        });
        for (String name : new String[]{"runner", "applicationRunner", "listener"}) {
            assertTrue(beanJson(json, name).contains("\"sideEffect\":true"), json);
        }
        for (String name : new String[]{"lifecycle", "frameworkListener"}) {
            assertFalse(beanJson(json, name).contains("\"sideEffect\""), json);
        }
    }

    private static String beanJson(String json, String name) {
        int start = json.indexOf("\"name\":\"" + name + "\"");
        assertTrue(start >= 0, json);
        return json.substring(start, json.indexOf('}', start));
    }

    interface RunnerContract extends org.springframework.boot.CommandLineRunner {}
    static class RunnerBase implements RunnerContract {
        @Override public void run(String... args) {}
    }
    static class InheritedRunner extends RunnerBase {}
    static class StartupRunner implements org.springframework.boot.ApplicationRunner {
        @Override public void run(org.springframework.boot.ApplicationArguments args) {}
    }
    static class StartupListener implements org.springframework.context.ApplicationListener<org.springframework.context.ApplicationEvent> {
        @Override public void onApplicationEvent(org.springframework.context.ApplicationEvent event) {}
    }
    static class ConnectionLifecycle implements org.springframework.context.SmartLifecycle {
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return false; }
    }
}
