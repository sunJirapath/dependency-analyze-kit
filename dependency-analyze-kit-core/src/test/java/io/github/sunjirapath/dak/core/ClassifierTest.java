package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sunjirapath.dak.core.Classifier.Declared;
import io.github.sunjirapath.dak.core.Classifier.Result;
import io.github.sunjirapath.dak.core.Evidence.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassifierTest {

    static final ArtifactId WEB = id("org.springframework.boot:spring-boot-starter-web");
    static final ArtifactId REDIS = id("org.springframework.boot:spring-boot-starter-data-redis");
    static final ArtifactId LANG3 = id("org.apache.commons:commons-lang3");
    static final ArtifactId H2 = id("com.h2database:h2");
    static final ArtifactId ACTUATOR = id("org.springframework.boot:spring-boot-starter-actuator");
    static final ArtifactId SPRING_WEB = id("org.springframework:spring-web");
    static final ArtifactId DATA_REDIS = id("org.springframework.data:spring-data-redis");
    static final ArtifactId ACTUATOR_AC = id("org.springframework.boot:spring-boot-actuator-autoconfigure");
    static final ArtifactId BOOT = id("org.springframework.boot:spring-boot");
    static final ArtifactId AUTOCONFIGURE = id("org.springframework.boot:spring-boot-autoconfigure");

    // web and redis both bring spring-boot + spring-boot-autoconfigure: those are shared
    static final Map<ArtifactId, ArtifactId> OWNERS = Classifier.owners(Map.of(
            WEB, Set.of(SPRING_WEB, BOOT, AUTOCONFIGURE),
            REDIS, Set.of(DATA_REDIS, BOOT, AUTOCONFIGURE),
            LANG3, Set.of(),
            H2, Set.of(),
            ACTUATOR, Set.of(ACTUATOR_AC, BOOT, AUTOCONFIGURE)));

    static final List<Declared> DECLARED = List.of(
            new Declared(WEB, "compile"), new Declared(REDIS, "compile"), new Declared(LANG3, "compile"),
            new Declared(H2, "runtime"), new Declared(ACTUATOR, "compile"));

    final Classifier classifier = new Classifier(List.of(), List.of());

    @Test
    void ownersCreditExclusiveTransitivesAndNeverSharedOnes() {
        assertEquals(WEB, OWNERS.get(SPRING_WEB));
        assertEquals(REDIS, OWNERS.get(DATA_REDIS));
        assertNull(OWNERS.get(BOOT));
        assertEquals(LANG3, OWNERS.get(LANG3));
    }

    @Test
    void declaredDependencyOwnsItsJarEvenWhenAlsoTransitive() {
        Map<ArtifactId, ArtifactId> owners = Classifier.owners(Map.of(WEB, Set.of(LANG3), LANG3, Set.of()));
        assertEquals(LANG3, owners.get(LANG3));
    }

    @Test
    void webRedisApp() {
        // redis auto-config matches on class presence and creates beans nobody injects
        ProbeReport probe = new ProbeReport("app", true, List.of(), List.of(
                ac("o.s.b.a.data.redis.RedisAutoConfiguration", true, AUTOCONFIGURE, DATA_REDIS),
                ac("o.s.b.a.data.redis.RedisAutoConfiguration#redisTemplate", true, AUTOCONFIGURE, DATA_REDIS)),
                List.of(bean("app", "demo.App", null, List.of(), ArtifactId.PROJECT),
                        bean("helloController", "demo.HelloController", null, List.of(), ArtifactId.PROJECT),
                        bean("redisTemplate", "o.s.d.r.c.RedisTemplate", null, List.of("redisConnectionFactory"), DATA_REDIS)));
        Map<ArtifactId, Result> r = run(Map.of(SPRING_WEB, Set.of("org.springframework.web.bind.annotation.RestController"),
                BOOT, Set.of("org.springframework.boot.SpringApplication")), probe);

        assertEquals(Verdict.USED_BYTECODE, r.get(WEB).verdict());
        assertTrue(r.get(WEB).summary().contains("RestController"));
        assertEquals(Verdict.UNUSED_CANDIDATE, r.get(REDIS).verdict());
        assertEquals("o.s.b.a.data.redis.RedisAutoConfiguration matched, but no project class or bean uses it",
                r.get(REDIS).summary());
        assertEquals(1, r.get(REDIS).evidence().size(), "bean-method outcomes are skipped");
        assertEquals(Verdict.UNUSED_CANDIDATE, r.get(LANG3).verdict());
        assertEquals(Verdict.IGNORED, r.get(H2).verdict());
    }

    @Test
    void beanReachableFromProjectBeanIsUsed() {
        ProbeReport probe = new ProbeReport("app", true, List.of(), List.of(), List.of(
                bean("service", "demo.Service", null, List.of("redisTemplate"), ArtifactId.PROJECT),
                bean("redisTemplate", "o.s.d.r.c.RedisTemplate", null, List.of("redisConnectionFactory"), DATA_REDIS)));
        Result redis = run(Map.of(), probe).get(REDIS);
        assertEquals(Verdict.USED_BEAN, redis.verdict());
        assertEquals("bean 'redisTemplate' (o.s.d.r.c.RedisTemplate) used by project bean 'service'", redis.summary());
    }

    @Test
    void beanDeclaredByProjectConfigurationIsARoot() {
        ProbeReport probe = new ProbeReport("app", true, List.of(), List.of(), List.of(
                bean("config", "demo.Config", null, List.of(), ArtifactId.PROJECT),
                bean("myClient", "o.s.web.client.RestClient", "config", List.of("redisTemplate"), SPRING_WEB),
                bean("redisTemplate", "o.s.d.r.c.RedisTemplate", null, List.of(), DATA_REDIS)));
        Map<ArtifactId, Result> r = run(Map.of(), probe);
        assertEquals(Verdict.USED_BEAN, r.get(REDIS).verdict());
        assertEquals(Verdict.UNUSED_CANDIDATE, r.get(WEB).verdict(), "a root bean is not evidence for its own jar");
    }

    @Test
    void sharedArtifactsCountForNobody() {
        ProbeReport probe = new ProbeReport("app", true, List.of(), List.of(), List.of());
        Map<ArtifactId, Result> r = run(Map.of(BOOT, Set.of("org.springframework.boot.SpringApplication")), probe);
        assertEquals(Verdict.UNUSED_CANDIDATE, r.get(WEB).verdict());
        assertEquals(Verdict.UNUSED_CANDIDATE, r.get(REDIS).verdict());
    }

    @Test
    void sideEffectStarterIsUsedByMatchedAutoConfig() {
        ProbeReport probe = new ProbeReport("app", true, List.of(),
                List.of(ac("o.s.b.actuate.autoconfigure.health.HealthEndpointAutoConfiguration", true, ACTUATOR_AC)),
                List.of());
        assertEquals(Verdict.USED_AUTOCONFIG, run(Map.of(), probe).get(ACTUATOR).verdict());
    }

    @Test
    void unmatchedConditionIsNamed() {
        ProbeReport probe = new ProbeReport("app", true, List.of(), List.of(ac("demo.FeatureAutoConfiguration", false,
                List.of("OnPropertyCondition: @ConditionalOnProperty (feature.enabled=true) did not find property 'feature.enabled'"),
                LANG3)), List.of());
        Result lang3 = run(Map.of(), probe).get(LANG3);
        assertEquals(Verdict.UNUSED_CANDIDATE, lang3.verdict());
        assertTrue(lang3.summary().contains("did not find property 'feature.enabled'"), lang3.summary());
        assertEquals(Kind.AUTOCONFIG_UNMATCHED, lang3.evidence().get(0).kind());
    }

    @Test
    void noProbeMeansUnknownExceptBytecode() {
        Map<ArtifactId, Result> r = run(Map.of(SPRING_WEB, Set.of("x.Y")));
        assertEquals(Verdict.USED_BYTECODE, r.get(WEB).verdict());
        assertEquals(Verdict.UNKNOWN, r.get(REDIS).verdict());
        assertEquals(Verdict.UNKNOWN, r.get(LANG3).verdict());
    }

    @Test
    void sliceOnlyContextsAreNotEnoughForUnused() {
        ProbeReport slice = new ProbeReport("slice", false, List.of(), List.of(), List.of());
        Result lang3 = run(Map.of(), slice).get(LANG3);
        assertEquals(Verdict.UNKNOWN, lang3.verdict());
        assertTrue(lang3.summary().contains("slice"));
    }

    @Test
    void userIgnorePatternsApplyOnlyToUnusedOrUnknown() {
        Classifier c = new Classifier(List.of("org.apache.commons:*", "org.springframework.boot:*-web"), List.of());
        ProbeReport probe = new ProbeReport("app", true, List.of(), List.of(), List.of());
        List<Result> results = c.classify(DECLARED, OWNERS, Map.of(SPRING_WEB, Set.of("x.Y")), List.of(probe));
        Map<ArtifactId, Result> r = byId(results);
        assertEquals(Verdict.IGNORED, r.get(LANG3).verdict());
        assertEquals("ignored by configuration (org.apache.commons:*)", r.get(LANG3).summary());
        assertEquals(Verdict.USED_BYTECODE, r.get(WEB).verdict());
    }

    @Test
    void artifactPatternMatching() {
        assertTrue(LANG3.matches("org.apache.commons:commons-lang3"));
        assertTrue(LANG3.matches("org.apache.*:commons-*"));
        assertFalse(LANG3.matches("org.apache.commons:commons-lang"));
        assertTrue(id("a.b:c").matches("a.b:c")); // '.' is literal
        assertFalse(id("aXb:c").matches("a.b:c"));
    }

    @Test
    void readsProbeJsonAndWritesReport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("context-1-1.json");
        Files.writeString(file, """
                {"id":"application","fullAutoConfig":true,"profiles":["test"],
                 "autoConfigs":[{"name":"x.RedisAutoConfiguration","matched":true,
                                 "locations":["/r/autoconfigure.jar","/r/data-redis.jar"],"messages":[]}],
                 "beans":[{"name":"svc","type":"demo.Svc","locations":["/p/target/classes"],
                           "factoryBean":null,"dependencies":["redisTemplate"]},
                          {"name":"redisTemplate","type":"x.RedisTemplate","locations":["/r/data-redis.jar","/jdk"],
                           "dependencies":[]}]}
                """);
        Map<String, ArtifactId> locations = Map.of("/r/autoconfigure.jar", AUTOCONFIGURE,
                "/r/data-redis.jar", DATA_REDIS, "/p/target/classes", ArtifactId.PROJECT);
        ProbeReport probe = ProbeReport.read(file, locations::get);
        assertEquals(List.of("test"), probe.profiles());
        assertEquals(Set.of(DATA_REDIS), probe.beans().get(1).artifacts());

        List<Result> results = classifier.classify(DECLARED, OWNERS, Map.of(), List.of(probe));
        assertEquals(Verdict.USED_BEAN, byId(results).get(REDIS).verdict());
        String json = Reports.json(results, 1);
        assertTrue(json.contains("\"verdict\" : \"USED_BEAN\""), json);
        assertTrue(Reports.table(results).startsWith("DEPENDENCY"));
    }

    private Map<ArtifactId, Result> run(Map<ArtifactId, Set<String>> bytecode, ProbeReport... probes) {
        return byId(classifier.classify(DECLARED, OWNERS, bytecode, List.of(probes)));
    }

    private static Map<ArtifactId, Result> byId(List<Result> results) {
        return results.stream().collect(java.util.stream.Collectors.toMap(Result::dependency, r -> r));
    }

    private static ArtifactId id(String ga) {
        return ArtifactId.parse(ga);
    }

    private static ProbeReport.AutoConfig ac(String name, boolean matched, ArtifactId... artifacts) {
        return ac(name, matched, List.of(), artifacts);
    }

    private static ProbeReport.AutoConfig ac(String name, boolean matched, List<String> messages, ArtifactId... artifacts) {
        return new ProbeReport.AutoConfig(name, matched, Set.of(artifacts), messages);
    }

    private static ProbeReport.Bean bean(String name, String type, String factoryBean, List<String> deps,
                                         ArtifactId... artifacts) {
        return new ProbeReport.Bean(name, type, Set.of(artifacts), factoryBean, deps);
    }
}
