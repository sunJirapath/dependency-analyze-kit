package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        // Configuration-supplied Class.forName names are invisible; reflection-only use needs an explicit ignore.
        var probe = new ProbeReport("full", true, List.of(), List.of(), List.of());
        var result = new Classifier(List.of("example:dynamic-provider"), List.of()).classify(
                List.of(new Classifier.Declared(LIB, "compile")), Map.of(LIB, LIB), Map.of(), List.of(probe)).get(0);
        assertEquals(Verdict.IGNORED, result.verdict());
    }

    @Test void serviceLoaderFalseUnusedAttack() throws Exception {
        // Kills treating an arbitrary SPI provider as unused just because it is not injected as a bean.
        retain("runtime", List.of(), metadata("META-INF/services/example.Service", "example.Provider"));
    }

    @Test void springFactoriesFalseUnusedAttack() throws Exception {
        // Kills treating spring.factories registration as zero runtime usage.
        retain("compile", List.of(), metadata("META-INF/spring.factories",
                "org.springframework.context.ApplicationListener=example.Listener"));
    }

    @Test void conditionalSideEffectBeanFalseUnusedAttack() {
        // Kills assuming every useful bean must be reachable by an injection edge from a project bean.
        retain("compile", List.of(new ProbeReport.Bean("startupListener", "example.Listener", Set.of(LIB), null, List.of(), true)));
    }

    @Test void runtimeProvidedAndTestScopeFalseUnusedAttack() {
        // Runtime stays reportable, covered by the Gradle IT; full contexts cannot prove test/provided usage absent.
        for (String scope : List.of("provided", "test")) retain(scope, List.of());
    }

    @Test void autoConfigurationImportsSideEffectFalseUnusedAttack() throws Exception {
        // Retain registrations the probe has not evaluated; matched/unmatched evidence explicitly exempts imports.
        retain("compile", List.of(), metadata("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                "example.ProviderAutoConfiguration"));
    }

    @Test void unmappedProbeLocationFalseUnusedAttack() throws Exception {
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

    private Map<ArtifactId, String> metadata(String name, String content) throws Exception {
        Path jar = dir.resolve("provider.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(name)); out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return DynamicRegistrations.scan(Map.of(LIB, jar));
    }

    private static void retain(String scope, List<ProbeReport.Bean> beans) {
        retain(scope, beans, Map.of());
    }

    private static void retain(String scope, List<ProbeReport.Bean> beans, Map<ArtifactId, String> registrations) {
        ProbeReport probe = new ProbeReport("full", true, List.of(), List.of(), beans);
        Classifier.Result result = new Classifier(List.of(), List.of()).classify(
                List.of(new Classifier.Declared(LIB, scope)), Map.of(LIB, LIB), Map.of(), List.of(probe),
                List.of(), registrations).get(0);
        assertNotEquals(Verdict.UNUSED_CANDIDATE, result.verdict(), "Retain dynamically used dependency: " + scope);
    }
}
