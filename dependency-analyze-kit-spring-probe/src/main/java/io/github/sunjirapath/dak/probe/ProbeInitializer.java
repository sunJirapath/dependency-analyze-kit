package io.github.sunjirapath.dak.probe;

import java.io.File;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.ClassUtils;
import org.springframework.util.MultiValueMap;

/**
 * Dumps the condition evaluation report and the bean graph of every refreshed context to
 * {@code ${dak.outputDirectory:target/dependency-analyze-kit}/context-<pid>-<n>.json}.
 * Does nothing unless {@code -Ddak.enabled=true}. Never fails the user's tests.
 */
public class ProbeInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final Map<ApplicationContext, String> CONTEXT_KEYS = new WeakHashMap<>();

    private static synchronized String contextKey(ApplicationContext context) {
        return context == null ? null : CONTEXT_KEYS.computeIfAbsent(context, c -> UUID.randomUUID().toString());
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!Boolean.getBoolean("dak.enabled")) {
            return;
        }
        context.addApplicationListener((ApplicationListener<ApplicationEvent>) event -> {
            // same identity check as ConditionEvaluationReportLoggingListener: ignore parent/child refreshes
            if (event instanceof ContextRefreshedEvent refreshed && refreshed.getApplicationContext() == context) {
                try {
                    dump(context);
                } catch (Exception | LinkageError e) {
                    System.err.println("[dependency-analyze-kit] probe failed for context " + context.getId() + ": " + e);
                }
            }
        });
    }

    private static void dump(ConfigurableApplicationContext context) throws Exception {
        ConfigurableListableBeanFactory bf = context.getBeanFactory();
        ClassLoader cl = context.getClassLoader() != null ? context.getClassLoader() : ClassUtils.getDefaultClassLoader();
        ConditionEvaluationReport report = ConditionEvaluationReport.find(bf);
        Json json = new Json();
        json.open('{').field("id").str(context.getId())
                .field("contextKey").str(contextKey(context))
                .field("parentContextKey").str(contextKey(context.getParent()))
                .field("fullAutoConfig").raw(String.valueOf(report != null && isFull(report, cl)))
                .field("profiles").strs(Arrays.asList(context.getEnvironment().getActiveProfiles()))
                .field("autoConfigs").open('[');
        if (report != null) {
            for (Map.Entry<String, ConditionEvaluationReport.ConditionAndOutcomes> e
                    : report.getConditionAndOutcomesBySource().entrySet()) {
                List<String> messages = new ArrayList<>();
                e.getValue().forEach(o -> {
                    if (!o.getOutcome().isMatch()) {
                        messages.add(ClassUtils.getShortName(o.getCondition().getClass()) + ": " + o.getOutcome().getMessage());
                    }
                });
                autoConfig(json, e.getKey(), e.getValue().isFullMatch(), messages, cl);
            }
            for (String name : report.getUnconditionalClasses()) {
                if (!report.getConditionAndOutcomesBySource().containsKey(name)) {
                    autoConfig(json, name, true, List.of(), cl);
                }
            }
            for (String excluded : report.getExclusions()) {
                autoConfig(json, excluded, false, List.of("excluded"), cl);
            }
        }
        json.close(']').field("beans").open('[');
        for (String name : bf.getBeanDefinitionNames()) {
            Set<String> classes = new LinkedHashSet<>();
            String type = null;
            try {
                Class<?> t = bf.getType(name, false);
                if (t != null && Proxy.isProxyClass(t)) {
                    // a JDK proxy class has no jar; the interfaces it implements do
                    Arrays.stream(t.getInterfaces()).map(Class::getName).forEach(classes::add);
                    type = classes.isEmpty() ? t.getName() : classes.iterator().next();
                } else if (t != null) {
                    type = ClassUtils.getUserClass(t).getName();
                    classes.add(type);
                }
            } catch (Exception | LinkageError ignored) {
                // ponytail: a type we cannot resolve contributes no evidence
            }
            BeanDefinition bd = bf.getMergedBeanDefinition(name);
            if (bd.getBeanClassName() != null) {
                classes.add(bd.getBeanClassName());
            }
            json.open('{').field("name").str(name).field("type").str(type)
                    .field("locations").strs(locations(classes, cl))
                    .field("factoryBean").str(bd.getFactoryBeanName())
                    .field("dependencies").strs(Arrays.asList(bf.getDependenciesForBean(name)))
                    .close('}');
        }
        json.close(']').close('}');

        Path dir = Path.of(System.getProperty("dak.outputDirectory", "target/dependency-analyze-kit"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("context-" + ProcessHandle.current().pid() + "-"
                + COUNTER.incrementAndGet() + ".json"), json.toString());
    }

    /** Full auto-configuration (not a test slice): every registered auto-configuration was considered. */
    private static boolean isFull(ConditionEvaluationReport report, ClassLoader cl) {
        Set<String> considered = new HashSet<>(report.getConditionAndOutcomesBySource().keySet());
        considered.addAll(report.getUnconditionalClasses());
        considered.addAll(report.getExclusions());
        return considered.containsAll(ImportCandidates.load(AutoConfiguration.class, cl).getCandidates());
    }

    /**
     * An auto-configuration is attributed to its own jar and to the jars of its {@code @ConditionalOnClass} classes.
     * Boot 3 keeps every built-in auto-configuration in the shared spring-boot-autoconfigure jar, so the
     * classes it waits for are what tie it to a starter.
     */
    private static void autoConfig(Json json, String source, boolean matched, List<String> messages, ClassLoader cl) {
        String className = source.contains("#") ? source.substring(0, source.indexOf('#')) : source;
        Set<String> classes = new LinkedHashSet<>(List.of(className));
        try {
            MultiValueMap<String, Object> attrs = new SimpleMetadataReaderFactory(cl).getMetadataReader(className)
                    .getAnnotationMetadata().getAllAnnotationAttributes(ConditionalOnClass.class.getName(), true);
            if (attrs != null) {
                attrs.forEach((key, values) -> values.forEach(v -> classes.addAll(Arrays.asList((String[]) v))));
            }
        } catch (Exception ignored) {
            // ponytail: unreadable class keeps its own location only
        }
        json.open('{').field("name").str(source).field("matched").raw(String.valueOf(matched))
                .field("locations").strs(locations(classes, cl)).field("messages").strs(messages).close('}');
    }

    /** Jar file or classes directory each class was loaded from; JDK and unknown classes are skipped. */
    private static List<String> locations(Set<String> classes, ClassLoader cl) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : classes) {
            String resource = c.replace('.', '/') + ".class";
            URL url = cl.getResource(resource);
            try {
                if (url == null) {
                    continue;
                }
                String s = url.toString();
                if (s.startsWith("jar:file:") && s.contains("!/")) {
                    out.add(new File(URI.create(s.substring(4, s.indexOf("!/")))).getCanonicalPath());
                } else if (s.startsWith("file:")) {
                    String file = new File(url.toURI()).getCanonicalPath();
                    out.add(file.substring(0, file.length() - resource.length() - 1));
                }
                // ponytail: jar:nested: (fat jar) and jrt: are skipped; the probe runs on the test classpath
            } catch (Exception ignored) {
                // unparseable location contributes nothing
            }
        }
        return new ArrayList<>(out);
    }

    /** Minimal JSON writer: keeps the probe free of dependencies on the user's test classpath. */
    private static final class Json {
        private final StringBuilder sb = new StringBuilder();
        private boolean first = true;

        Json open(char c) {
            comma();
            sb.append(c);
            first = true;
            return this;
        }

        Json close(char c) {
            sb.append(c);
            first = false;
            return this;
        }

        Json field(String name) {
            comma();
            quote(name);
            sb.append(':');
            first = true; // the value follows without a comma
            return this;
        }

        Json str(String s) {
            comma();
            if (s == null) {
                sb.append("null");
            } else {
                quote(s);
            }
            first = false;
            return this;
        }

        Json raw(String s) {
            comma();
            sb.append(s);
            first = false;
            return this;
        }

        Json strs(List<String> values) {
            open('[');
            values.forEach(this::str);
            return close(']');
        }

        private void comma() {
            if (!first) {
                sb.append(',');
            }
            first = false;
        }

        private void quote(String s) {
            sb.append('"');
            for (char ch : s.toCharArray()) {
                switch (ch) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (ch < 0x20) {
                            sb.append(String.format("\\u%04x", (int) ch));
                        } else {
                            sb.append(ch);
                        }
                    }
                }
            }
            sb.append('"');
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
