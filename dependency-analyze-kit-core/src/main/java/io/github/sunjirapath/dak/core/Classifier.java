package io.github.sunjirapath.dak.core;

import io.github.sunjirapath.dak.core.Evidence.Kind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Joins bytecode references and probe reports into one verdict per declared dependency.
 *
 * <p>Evidence found on any artifact is credited to that artifact's <em>owner</em>: the declared dependency itself,
 * or the single declared dependency whose transitive closure contains it. Artifacts reachable from more than one
 * declared dependency have no owner and count for nobody (the starter rule).
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
            "org.springframework.boot:spring-boot-starter-web-services");

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
        BUILT_IN_IGNORES.put("org.springframework.boot:spring-boot-configuration-processor", "annotation processor");
        BUILT_IN_IGNORES.put("org.projectlombok:lombok", "annotation processor");
        BUILT_IN_IGNORES.put("org.springframework.boot:spring-boot-devtools", "development-time tooling");
        BUILT_IN_IGNORES.put("org.springframework.boot:spring-boot-docker-compose", "development-time tooling");
    }

    private final List<String> ignored;
    private final List<String> sideEffect;

    /** Patterns are {@code groupId:artifactId} with {@code *} wildcards; they extend the built-in lists. */
    public Classifier(Collection<String> ignored, Collection<String> sideEffect) {
        this.ignored = List.copyOf(ignored);
        this.sideEffect = new ArrayList<>(BUILT_IN_SIDE_EFFECT);
        this.sideEffect.addAll(sideEffect);
    }

    /**
     * Maps every artifact to its owning declared dependency.
     *
     * @param closures each declared dependency to its transitive closure
     */
    public static Map<ArtifactId, ArtifactId> owners(Map<ArtifactId, ? extends Collection<ArtifactId>> closures) {
        Map<ArtifactId, Set<ArtifactId>> claimedBy = new HashMap<>();
        closures.forEach((d, c) -> c.forEach(a -> claimedBy.computeIfAbsent(a, k -> new HashSet<>()).add(d)));
        Map<ArtifactId, ArtifactId> owner = new HashMap<>();
        claimedBy.forEach((a, ds) -> {
            if (ds.size() == 1) {
                owner.put(a, ds.iterator().next());
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
            beanEvidence(probe, found, owner);
        }

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
                summary = matched != null ? matched.detail() + ", but no project class or bean uses it"
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
    private static void beanEvidence(ProbeReport probe, Map<ArtifactId, Set<Evidence>> found,
                                     Map<ArtifactId, ArtifactId> owner) {
        Map<String, ProbeReport.Bean> byName = probe.beans().stream()
                .collect(Collectors.toMap(ProbeReport.Bean::name, b -> b, (a, b) -> a));
        Set<String> roots = new HashSet<>();
        probe.beans().stream().filter(b -> b.artifacts().contains(ArtifactId.PROJECT)).forEach(b -> roots.add(b.name()));
        // beans declared by a project @Configuration are the project's own choices too
        probe.beans().stream().filter(b -> roots.contains(b.factoryBean())).forEach(b -> roots.add(b.name()));

        Map<String, String> reachedFrom = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        roots.forEach(r -> { reachedFrom.put(r, r); queue.add(r); });
        while (!queue.isEmpty()) {
            String name = queue.poll();
            ProbeReport.Bean bean = byName.get(name);
            if (bean == null) {
                continue;
            }
            for (String dep : bean.dependencies()) {
                if (reachedFrom.putIfAbsent(dep, reachedFrom.get(name)) == null) {
                    queue.add(dep);
                }
            }
        }
        reachedFrom.forEach((name, root) -> {
            ProbeReport.Bean bean = byName.get(name);
            if (bean != null && !roots.contains(name)) {
                credit(found, owner, bean.artifacts(), new Evidence(Kind.BEAN,
                        "bean '" + name + "' (" + bean.type() + ") used by project bean '" + root + "'"));
            }
        });
    }

    private static void credit(Map<ArtifactId, Set<Evidence>> found, Map<ArtifactId, ArtifactId> owner,
                               Set<ArtifactId> artifacts, Evidence evidence) {
        artifacts.stream().map(owner::get).filter(Objects::nonNull).distinct()
                .forEach(o -> found.computeIfAbsent(o, k -> new LinkedHashSet<>()).add(evidence));
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
