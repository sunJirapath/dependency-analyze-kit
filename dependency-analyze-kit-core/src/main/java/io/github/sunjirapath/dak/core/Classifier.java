package io.github.sunjirapath.dak.core;

import io.github.sunjirapath.dak.core.Evidence.Kind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Joins bytecode references and probe reports into one verdict per declared dependency.
 *
 * <p>Evidence found on any artifact is credited to that artifact's <em>owner</em>: the declared dependency itself,
 * or the single declared dependency whose transitive closure contains it. Artifacts reachable from more than one
 * declared dependency have no owner and count for nobody (the starter rule), unless one of them is itself brought in
 * whole by all the others.
 */
public final class Classifier {

    public record Declared(ArtifactId id, String scope) {}

    public record Result(ArtifactId dependency, String scope, Verdict verdict, String summary, List<Evidence> evidence) {}

    /** Usage these dependencies have is invisible to bytecode and beans: SPI, logging, build-time tools. */
    static final Map<String, String> BUILT_IN_IGNORES = new LinkedHashMap<>();

    /** Starters whose matched auto-configuration is itself the usage (endpoints, filters, view resolvers). */
    static final List<String> BUILT_IN_SIDE_EFFECT = List.of(
            "org.springframework.boot:spring-boot-starter-actuator",
            "org.springframework.boot:spring-boot-starter-security",
            "org.springframework.boot:spring-boot-starter-oauth2-resource-server",
            "org.springframework.boot:spring-boot-starter-thymeleaf",
            "org.springframework.boot:spring-boot-starter-freemarker",
            "org.springframework.boot:spring-boot-starter-mustache",
            "org.springframework.boot:spring-boot-starter-groovy-templates",
            "org.springframework.boot:spring-boot-starter-web-services",
            // annotation-driven: work through post-processors and advisors, which nothing injects
            "org.springframework.boot:spring-boot-starter-cache",
            "org.springframework.boot:spring-boot-starter-validation",
            "org.springframework.boot:spring-boot-starter-aop",
            "org.springframework.boot:spring-boot-starter-aspectj",
            "org.aspectj:aspectjweaver",
            "io.github.resilience4j:resilience4j-spring-boot*",
            // the framework calls the project's functions, so no edge points from project beans to it
            "org.springframework.cloud:spring-cloud-starter-stream-*",
            "org.springframework.cloud:spring-cloud-stream-binder-*",
            // providers that an auto-configuration picks up by class presence
            "io.micrometer:micrometer-registry-*",
            "com.github.ben-manes.caffeine:caffeine",
            "io.github.openfeign:feign-okhttp",
            "io.github.openfeign:feign-hc5");

    /** artifactId words that say nothing about which auto-configuration belongs to a dependency. */
    private static final Set<String> GENERIC_WORDS = Set.of("spring", "boot", "starter", "data", "autoconfigure");

    static {
        String jdbc = "JDBC driver, loaded through ServiceLoader";
        for (String p : List.of("com.h2database:h2", "org.hsqldb:hsqldb", "org.apache.derby:derby*",
                "org.postgresql:postgresql", "com.mysql:mysql-connector-j", "mysql:mysql-connector-java",
                "org.mariadb.jdbc:mariadb-java-client", "com.oracle.database.jdbc:ojdbc*",
                "com.microsoft.sqlserver:mssql-jdbc")) {
            BUILT_IN_IGNORES.put(p, jdbc);
        }
        String logging = "logging backend or bridge, bound at runtime";
        for (String p : List.of("ch.qos.logback:*", "org.apache.logging.log4j:log4j-slf4j2-impl",
                "org.slf4j:jul-to-slf4j", "org.slf4j:jcl-over-slf4j", "org.slf4j:log4j-over-slf4j",
                "org.springframework.boot:spring-boot-starter-logging",
                "org.springframework.boot:spring-boot-starter-log4j2")) {
            BUILT_IN_IGNORES.put(p, logging);
        }
        BUILT_IN_IGNORES.put("org.ehcache:ehcache", "JCache provider, loaded through ServiceLoader");
        BUILT_IN_IGNORES.put("org.apache.commons:commons-pool2", "connection pool, switched on by class presence");
        BUILT_IN_IGNORES.put("io.github.openfeign:feign-micrometer",
                "Feign capability, applied in per-client contexts the probe does not see");
        BUILT_IN_IGNORES.put("org.springframework.boot:spring-boot-configuration-processor", "annotation processor");
        BUILT_IN_IGNORES.put("org.projectlombok:lombok", "annotation processor");
        BUILT_IN_IGNORES.put("org.mapstruct:mapstruct-processor", "annotation processor");
        BUILT_IN_IGNORES.put("org.projectlombok:lombok-mapstruct-binding", "annotation processor");
        BUILT_IN_IGNORES.put("org.springframework.boot:spring-boot-devtools", "development-time tooling");
        BUILT_IN_IGNORES.put("org.springframework.boot:spring-boot-docker-compose", "development-time tooling");
        BUILT_IN_IGNORES.put("io.github.sunjirapath:dependency-analyze-kit-spring-probe",
                "DAK's own probe, declared on the test classpath to produce evidence");
    }

    private final List<String> ignored;
    private final List<String> sideEffect;

    /** Patterns are {@code groupId:artifactId} with {@code *} wildcards; they extend the built-in lists. */
    public Classifier(Collection<String> ignored, Collection<String> sideEffect) {
        ignored.forEach(Classifier::requireGroupArtifact);
        sideEffect.forEach(Classifier::requireGroupArtifact);
        this.ignored = List.copyOf(ignored);
        this.sideEffect = new ArrayList<>(BUILT_IN_SIDE_EFFECT);
        this.sideEffect.addAll(sideEffect);
    }

    /** A bare artifact name never matches {@link ArtifactId#matches}; fail loudly instead of ignoring nothing. */
    private static void requireGroupArtifact(String pattern) {
        String trimmed = pattern.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon != trimmed.lastIndexOf(':') || colon == trimmed.length() - 1) {
            throw new IllegalArgumentException(
                    "'" + pattern + "' is not a groupId:artifactId pattern; bare artifact names never match");
        }
    }

    /**
     * Maps every artifact to its owning declared dependency.
     *
     * <p>A test-scope dependency cannot replace a main-scope one, so its claim on an artifact only counts when no
     * main-scope dependency claims it too. Otherwise {@code spring-boot-starter-test}, which pulls in
     * {@code spring-boot-starter}, would make every Boot jar shared.
     *
     * <p>When one claimant's whole closure is inside every other claimant's (a starter that a declared library
     * also pulls in), that claimant owns the artifact: it is how the project gets it directly.
     *
     * @param closures   each declared dependency to its transitive closure
     * @param testScoped the declared dependencies with test scope
     */
    public static Map<ArtifactId, ArtifactId> owners(Map<ArtifactId, ? extends Collection<ArtifactId>> closures,
                                                     Set<ArtifactId> testScoped) {
        Map<ArtifactId, Set<ArtifactId>> claimedBy = new HashMap<>();
        closures.forEach((d, c) -> c.forEach(a -> claimedBy.computeIfAbsent(a, k -> new HashSet<>()).add(d)));
        Map<ArtifactId, ArtifactId> owner = new HashMap<>();
        claimedBy.forEach((a, ds) -> {
            Set<ArtifactId> main = new HashSet<>(ds);
            main.removeAll(testScoped);
            Set<ArtifactId> claims = main.isEmpty() ? ds : main;
            // a claimant that every other claimant brings in whole is the direct route to the artifact
            List<ArtifactId> direct = claims.stream().filter(d -> claims.stream()
                    .allMatch(o -> o.equals(d) || closures.get(o).containsAll(closures.get(d)))).toList();
            if (direct.size() == 1) {
                owner.put(a, direct.get(0));
            }
        });
        closures.keySet().forEach(d -> owner.put(d, d)); // a declared dependency always owns its own jar
        return owner;
    }

    /**
     * @param declared dependencies to report on
     * @param owner    artifact to owning declared dependency, see {@link #owners}
     * @param bytecode artifacts referenced by project classes, with the referenced class names
     * @param probes   probe reports; empty means no runtime evidence, so nothing can be UNUSED
     */
    public List<Result> classify(List<Declared> declared, Map<ArtifactId, ArtifactId> owner,
                                 Map<ArtifactId, ? extends Collection<String>> bytecode, List<ProbeReport> probes) {
        Map<ArtifactId, Set<Evidence>> found = new HashMap<>();
        bytecode.forEach((a, classes) -> credit(found, owner, Set.of(a), new Evidence(Kind.BYTECODE,
                a + ": " + classes.stream().sorted().findFirst().orElse("?")
                        + (classes.size() > 1 ? " (+" + (classes.size() - 1) + " more)" : ""))));
        for (ProbeReport probe : probes) {
            for (ProbeReport.AutoConfig ac : probe.autoConfigs()) {
                if (ac.name().contains("#")) {
                    continue; // ponytail: @Bean-method outcomes are noise next to their class's outcome
                }
                credit(found, owner, ac.artifacts(), ac.matched()
                        ? new Evidence(Kind.AUTOCONFIG_MATCHED, ac.name() + " matched")
                        : new Evidence(Kind.AUTOCONFIG_UNMATCHED,
                                ac.name() + " did not match: " + String.join("; ", ac.messages())));
            }
        }
        beanEvidence(probes, found, owner);

        boolean full = probes.stream().anyMatch(ProbeReport::fullAutoConfig);
        List<Result> results = new ArrayList<>();
        for (Declared d : declared) {
            List<Evidence> ev = new ArrayList<>(found.getOrDefault(d.id(), Set.of()));
            Evidence bytecodeEv = first(ev, Kind.BYTECODE);
            Evidence beanEv = first(ev, Kind.BEAN);
            Evidence matched = first(ev, Kind.AUTOCONFIG_MATCHED);
            Evidence unmatched = first(ev, Kind.AUTOCONFIG_UNMATCHED);
            Verdict verdict;
            String summary;
            if (bytecodeEv != null) {
                verdict = Verdict.USED_BYTECODE;
                summary = bytecodeEv.detail();
            } else if (beanEv != null) {
                verdict = Verdict.USED_BEAN;
                summary = beanEv.detail();
            } else if (matched != null && sideEffect.stream().anyMatch(d.id()::matches)) {
                verdict = Verdict.USED_AUTOCONFIG;
                summary = matched.detail();
            } else if (probes.isEmpty()) {
                verdict = Verdict.UNKNOWN;
                summary = "no probe report; run the tests with -Ddak.enabled=true and the spring-probe on the test classpath";
            } else if (!full) {
                verdict = Verdict.UNKNOWN;
                summary = "only slice-test contexts observed; add a @SpringBootTest";
            } else {
                verdict = Verdict.UNUSED_CANDIDATE;
                summary = matched != null ? matchedButUnused(ev, d.id())
                        : unmatched != null ? unmatched.detail()
                        : "no bytecode reference, bean or auto-configuration";
            }
            if (verdict == Verdict.UNUSED_CANDIDATE || verdict == Verdict.UNKNOWN) {
                String reason = ignoreReason(d.id());
                if (reason != null) {
                    verdict = Verdict.IGNORED;
                    summary = reason;
                    ev.add(0, new Evidence(Kind.IGNORED, reason));
                }
            }
            results.add(new Result(d.id(), d.scope(), verdict, summary, ev));
        }
        return results;
    }

    /** Credits beans reachable from a project bean through injection / depends-on edges. */
    private static void beanEvidence(List<ProbeReport> probes, Map<ArtifactId, Set<Evidence>> found,
                                     Map<ArtifactId, ArtifactId> owner) {
        Map<String, List<Integer>> contextsByKey = new HashMap<>();
        Map<BeanKey, ProbeReport.Bean> beans = new LinkedHashMap<>();
        for (int i = 0; i < probes.size(); i++) {
            ProbeReport probe = probes.get(i);
            if (probe.contextKey() != null) {
                contextsByKey.computeIfAbsent(probe.contextKey(), k -> new ArrayList<>()).add(i);
            }
            int snapshot = i;
            probe.beans().forEach(b -> beans.put(new BeanKey(snapshot, b.name()), b));
        }
        Set<BeanKey> roots = new LinkedHashSet<>();
        Map<BeanKey, List<BeanKey>> products = new HashMap<>();
        beans.forEach((key, bean) -> {
            if (bean.artifacts().contains(ArtifactId.PROJECT)) {
                roots.add(key);
            }
            if (bean.factoryBean() != null) {
                for (BeanKey factory : resolveBeans(key.context(), bean.factoryBean(), probes, contextsByKey, beans)) {
                    products.computeIfAbsent(factory, k -> new ArrayList<>()).add(key);
                }
            }
        });
        // Expand all factory levels before traversal, regardless of bean definition order.
        Deque<BeanKey> factories = new ArrayDeque<>(roots);
        while (!factories.isEmpty()) {
            for (BeanKey product : products.getOrDefault(factories.poll(), List.of())) {
                if (roots.add(product)) {
                    factories.add(product);
                }
            }
        }

        Map<BeanKey, BeanKey> reachedFrom = new LinkedHashMap<>();
        Deque<BeanKey> queue = new ArrayDeque<>();
        roots.forEach(r -> { reachedFrom.put(r, r); queue.add(r); });
        while (!queue.isEmpty()) {
            BeanKey key = queue.poll();
            ProbeReport.Bean bean = beans.get(key);
            for (String dep : bean.dependencies()) {
                for (BeanKey dependency : resolveBeans(key.context(), dep, probes, contextsByKey, beans)) {
                    if (reachedFrom.putIfAbsent(dependency, reachedFrom.get(key)) == null) {
                        queue.add(dependency);
                    }
                }
            }
        }
        reachedFrom.forEach((key, root) -> {
            ProbeReport.Bean bean = beans.get(key);
            if (!roots.contains(key)) {
                credit(found, owner, bean.artifacts(), new Evidence(Kind.BEAN,
                        "bean '" + key.name() + "' (" + bean.type() + ") used by project bean '" + root.name() + "'"));
            }
        });
    }

    private record BeanKey(int context, String name) {}

    /** Spring resolves a local bean before looking in its ancestors; unrelated contexts are never searched. */
    private static Set<BeanKey> resolveBeans(int context, String name, List<ProbeReport> probes,
                                             Map<String, List<Integer>> contextsByKey,
                                             Map<BeanKey, ProbeReport.Bean> beans) {
        Set<BeanKey> resolved = new LinkedHashSet<>();
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(context);
        while (!queue.isEmpty()) {
            int snapshot = queue.poll();
            if (!visited.add(snapshot)) {
                continue;
            }
            BeanKey key = new BeanKey(snapshot, name);
            if (beans.containsKey(key)) {
                resolved.add(key);
            } else {
                queue.addAll(contextsByKey.getOrDefault(probes.get(snapshot).parentContextKey(), List.of()));
            }
        }
        return resolved;
    }

    private static void credit(Map<ArtifactId, Set<Evidence>> found, Map<ArtifactId, ArtifactId> owner,
                               Set<ArtifactId> artifacts, Evidence evidence) {
        artifacts.stream().map(owner::get).filter(Objects::nonNull).distinct()
                .forEach(o -> found.computeIfAbsent(o, k -> new LinkedHashSet<>()).add(evidence));
    }

    /**
     * "RedisAutoConfiguration matched, but ..." — names the auto-configurations that look like they belong to the
     * dependency first (simple name contains a word of its artifactId), so the line isn't an arbitrary pick.
     */
    private static String matchedButUnused(List<Evidence> ev, ArtifactId id) {
        List<String> words = Arrays.stream(id.artifactId().toLowerCase(Locale.ROOT).split("-"))
                .filter(w -> w.length() > 2 && !GENERIC_WORDS.contains(w)).toList();
        List<String> names = ev.stream().filter(e -> e.kind() == Kind.AUTOCONFIG_MATCHED)
                .map(e -> e.detail().substring(e.detail().lastIndexOf('.', e.detail().indexOf(' ')) + 1,
                        e.detail().length() - " matched".length()))
                .map(n -> n.contains("$") ? n.substring(0, n.indexOf('$')) : n) // nested -> top-level class
                .distinct()
                .sorted(Comparator.comparing((String n) -> words.stream()
                                .noneMatch(n.toLowerCase(Locale.ROOT)::contains))
                        .thenComparingInt(String::length)
                        .thenComparing(n -> n))
                .toList();
        String shown = String.join(", ", names.subList(0, Math.min(2, names.size())))
                + (names.size() > 2 ? ", ..." : "");
        return names.size() == 1 ? shown + " matched, but no project class or bean uses it"
                : names.size() + " auto-configurations matched (" + shown + "), but no project class or bean uses them";
    }

    private static Evidence first(List<Evidence> ev, Kind kind) {
        return ev.stream().filter(e -> e.kind() == kind).findFirst().orElse(null);
    }

    private String ignoreReason(ArtifactId id) {
        for (String p : ignored) {
            if (id.matches(p)) {
                return "ignored by configuration (" + p + ")";
            }
        }
        for (Map.Entry<String, String> e : BUILT_IN_IGNORES.entrySet()) {
            if (id.matches(e.getKey())) {
                return "ignored: " + e.getValue();
            }
        }
        return null;
    }
}
