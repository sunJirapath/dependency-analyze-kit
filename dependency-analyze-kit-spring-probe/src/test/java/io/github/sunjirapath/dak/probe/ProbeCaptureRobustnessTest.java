package io.github.sunjirapath.dak.probe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.json.BasicJsonParser;
import org.springframework.context.support.GenericApplicationContext;

@Timeout(60)
class ProbeCaptureRobustnessTest {
    private static final String UNICODE = "\u0e44\u0e17\u0e22 \ud83d\ude80 \u05e9\u05dc\u05d5\u05dd";
    @TempDir Path dir;

    @Test void utf8CapturePreservesUnicodeAndEscapesControlCharacters() throws Exception {
        withOutput(dir.resolve("probe with spaces"), output -> {
            try (var context = new GenericApplicationContext()) {
                context.setId(UNICODE + "\n\r\t\"\\\u0001");
                context.getEnvironment().setActiveProfiles(UNICODE);
                context.registerBean(UNICODE, Object.class);
                new ProbeInitializer().initialize(context);
                context.refresh();
            }
            List<Path> files;
            try (var paths = Files.list(output)) { files = paths.toList(); }
            assertEquals(1, files.size());
            byte[] bytes = Files.readAllBytes(files.get(0));
            String json = new String(bytes, StandardCharsets.UTF_8);
            assertArrayEquals(bytes, json.getBytes(StandardCharsets.UTF_8));
            assertTrue(json.contains("\"id\":\"" + UNICODE + "\\n\\r\\t\\\"\\\\\\u0001\""));
            assertTrue(json.contains("\"name\":\"" + UNICODE + "\""));
            assertTrue(json.contains("\"profiles\":[\"" + UNICODE + "\"]"));
            assertFalse(json.chars().anyMatch(c -> c < 0x20));
        });
    }

    @Test void concurrentContextsPublishCompleteUniqueReportsWithoutTemporaryFiles() throws Exception {
        withOutput(dir.resolve("concurrent"), output -> {
            var executor = Executors.newFixedThreadPool(6);
            try {
                var calls = new ArrayList<Callable<Void>>();
                for (int i = 0; i < 24; i++) {
                    String id = "context-" + i;
                    calls.add(() -> {
                        try (var context = new GenericApplicationContext()) {
                            context.setId(id);
                            context.registerBean("service", Object.class);
                            new ProbeInitializer().initialize(context);
                            context.refresh();
                        }
                        return null;
                    });
                }
                for (var result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) result.get();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
            List<Path> files;
            try (var paths = Files.list(output)) { files = paths.toList(); }
            assertEquals(24, files.size());
            Set<String> ids = new HashSet<>();
            Set<String> keys = new HashSet<>();
            var parser = new BasicJsonParser();
            for (Path file : files) {
                assertTrue(file.getFileName().toString().matches("context-.*\\.json"));
                String json = Files.readString(file);
                Map<String, Object> model = parser.parseMap(json);
                assertTrue(ids.add((String) model.get("id")));
                assertTrue(keys.add((String) model.get("contextKey")));
                assertTrue(json.contains("\"parentContextKey\":null"));
                assertTrue(((Number) model.get("capturedAt")).longValue() > 0);
                List<?> beans = (List<?>) model.get("beans");
                assertEquals(1, beans.size());
                assertEquals("service", ((Map<?, ?>) beans.get(0)).get("name"));
                assertEquals(List.of(), ((Map<?, ?>) beans.get(0)).get("dependencies"));
            }
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < 24; i++) expected.add("context-" + i);
            assertEquals(expected, ids);
        });
    }

    @Test void fiveThousandLazyBeanDefinitionsAreCapturedWithoutInstantiation() throws Exception {
        withOutput(dir.resolve("scale"), output -> {
            try (var context = new GenericApplicationContext()) {
                context.setId("scale");
                for (int i = 0; i < 5_000; i++) {
                    var definition = new RootBeanDefinition(Object.class);
                    definition.setLazyInit(true);
                    context.registerBeanDefinition("bean-" + i, definition);
                }
                new ProbeInitializer().initialize(context);
                context.refresh();
                for (int i = 0; i < 5_000; i++) assertFalse(context.getBeanFactory().containsSingleton("bean-" + i));
            }
            List<Path> files;
            try (var paths = Files.list(output)) { files = paths.toList(); }
            assertEquals(1, files.size());
            Map<String, Object> report = new BasicJsonParser().parseMap(Files.readString(files.get(0)));
            List<?> beans = (List<?>) report.get("beans");
            assertEquals(5_000, beans.size());
            for (int i = 0; i < 5_000; i++) {
                Map<?, ?> bean = (Map<?, ?>) beans.get(i);
                assertEquals("bean-" + i, bean.get("name"));
                assertEquals("java.lang.Object", bean.get("type"));
                assertEquals(List.of(), bean.get("locations"));
                assertEquals(List.of(), bean.get("dependencies"));
            }
        });
    }

    private static void withOutput(Path output, Capture assertion) throws Exception {
        String enabled = System.getProperty("dak.enabled");
        String previous = System.getProperty("dak.outputDirectory");
        try {
            System.setProperty("dak.enabled", "true");
            System.setProperty("dak.outputDirectory", output.toString());
            assertion.run(output);
        } finally {
            restore("dak.enabled", enabled);
            restore("dak.outputDirectory", previous);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    @FunctionalInterface private interface Capture { void run(Path output) throws Exception; }
}
