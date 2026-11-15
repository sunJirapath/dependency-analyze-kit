package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerdictSafetySecurityTest {
    private static final ArtifactId LIB = new ArtifactId("example", "dynamic-provider");
    @TempDir Path dir;

    @Test void reflectiveLoadingFalseUnusedAttack() {
        // FINDING: Class.forName names supplied by configuration are invisible to bytecode/bean evidence.
        // Kills treating absence of statically visible references as proof of no reflective usage.
        retain("compile", List.of());
    }

    @Test void serviceLoaderFalseUnusedAttack() throws Exception {
        // FINDING: Generic META-INF/services providers are not inspected; only known artifact names are ignored.
        // Kills treating an arbitrary SPI provider as unused just because it is not injected as a bean.
        metadata("META-INF/services/example.Service", "example.Provider");
        retain("runtime", List.of());
    }

    @Test void springFactoriesFalseUnusedAttack() throws Exception {
        // FINDING: Arbitrary spring.factories initializers/listeners can run without any bean consumer.
        // Kills treating spring.factories registration as zero runtime usage.
        metadata("META-INF/spring.factories", "org.springframework.context.ApplicationListener=example.Listener");
        retain("compile", List.of());
    }

    @Test void conditionalSideEffectBeanFalseUnusedAttack() {
        // FINDING: An unconsumed third-party bean can register a filter/listener or perform a startup side effect.
        // Kills assuming every useful bean must be reachable by an injection edge from a project bean.
        retain("compile", List.of(new ProbeReport.Bean("startupListener", "example.Listener", Set.of(LIB), null, List.of())));
    }

    @Test void runtimeProvidedAndTestScopeFalseUnusedAttack() {
        // FINDING: Runtime-only/provided declarations and test engines have no general keep/unknown rule.
        // Kills treating a full application context as complete evidence about every execution scope.
        for (String scope : List.of("runtime", "provided", "test")) retain(scope, List.of());
    }

    @Test void autoConfigurationImportsSideEffectFalseUnusedAttack() throws Exception {
        // FINDING: An arbitrary matched auto-configuration can perform side effects without an injected bean.
        // Kills treating AutoConfiguration.imports registrations as unused unless explicitly allowlisted.
        metadata("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports", "example.ProviderAutoConfiguration");
        var config = new ProbeReport.AutoConfig("example.ProviderAutoConfiguration", true, Set.of(LIB), List.of());
        var probe = new ProbeReport("full", true, List.of(), List.of(config), List.of());
        var result = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(LIB, "compile")),
                Map.of(LIB, LIB), Map.of(), List.of(probe)).get(0);
        assertNotEquals(Verdict.UNUSED_CANDIDATE, result.verdict(), "Retain startup auto-configuration with side effects");
    }

    @Test void unmappedProbeLocationFalseUnusedAttack() throws Exception {
        // FINDING: Unmapped jar locations silently erase a reached bean's artifact usage after cache relocation.
        // Kills dropping unresolved evidence while retaining the report's full-context authority.
        var app = Map.of("name", "app", "type", "example.App", "locations", List.of("/project"),
                "dependencies", List.of("provider"));
        var provider = Map.of("name", "provider", "type", "example.Provider", "locations", List.of("/foreign/needed.jar"),
                "dependencies", List.of());
        String json = com.fasterxml.jackson.jr.ob.JSON.std.asString(Map.of("id", "full", "capturedAt", 1,
                "fullAutoConfig", true, "profiles", List.of(), "autoConfigs", List.of(), "beans", List.of(app, provider)));
        Path file = Files.writeString(dir.resolve("context-relocated.json"), json);
        ProbeReport probe;
        try {
            probe = ProbeReport.read(file, location -> location.equals("/project") ? ArtifactId.PROJECT : null);
        } catch (java.io.IOException rejected) {
            return; // Rejecting unresolved evidence is also fail-closed; plugins then emit UNKNOWN.
        }
        var result = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(LIB, "compile")),
                Map.of(LIB, LIB), Map.of(), List.of(probe)).get(0);
        assertNotEquals(Verdict.UNUSED_CANDIDATE, result.verdict(), "Retain dependency when reached bean location cannot be resolved");
    }

    private void metadata(String name, String content) throws Exception {
        Path jar = dir.resolve("provider.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(name)); out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        // This is the entire jar input available to the classifier: metadata does not reach it.
        org.junit.jupiter.api.Assertions.assertTrue(ClassReferenceScanner.owners(Set.of(), Map.of(LIB, jar)).isEmpty());
    }

    private static void retain(String scope, List<ProbeReport.Bean> beans) {
        ProbeReport probe = new ProbeReport("full", true, List.of(), List.of(), beans);
        Classifier.Result result = new Classifier(List.of(), List.of()).classify(
                List.of(new Classifier.Declared(LIB, scope)), Map.of(LIB, LIB), Map.of(), List.of(probe)).get(0);
        assertNotEquals(Verdict.UNUSED_CANDIDATE, result.verdict(), "Retain dynamically used dependency: " + scope);
    }
}
