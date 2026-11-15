package io.github.sunjirapath.dak.maven;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sunjirapath.dak.core.ArtifactId;
import io.github.sunjirapath.dak.core.Classifier;
import io.github.sunjirapath.dak.core.ProbeReport;
import io.github.sunjirapath.dak.core.Verdict;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenProbeSecurityTest {
    @TempDir Path dir;

    @Test void malformedProbeAlongsideValidEvidenceAttackDegradesToUnknown() throws Exception {
        // Kills caller-side fail-open: ignoring a corrupt current probe while another full probe authorizes unused.
        AnalyzeMojo mojo = mojo(new Date(1));
        Files.writeString(dir.resolve("context-bad.json"), "{");
        String identity = ProbeReport.location(dir).replace("\\", "\\\\");
        Files.writeString(dir.resolve("context-good.json"), "{\"id\":\"full\",\"fullAutoConfig\":true,"
                + "\"profiles\":[],\"beans\":[],\"autoConfigs\":[],\"capturedAt\":2,\"projectDirectory\":\"" + identity + "\"}");
        List<ProbeReport> probes = assertDoesNotThrow(() -> read(mojo));
        assertEquals(1, probes.size());
        @SuppressWarnings("unchecked")
        List<String> problems = (List<String>) field(mojo, "analysisProblems");
        assertFalse(problems.isEmpty());
        ArtifactId lib = new ArtifactId("example", "needed");
        var results = new Classifier(List.of(), List.of()).classify(List.of(new Classifier.Declared(lib, "compile")),
                Map.of(lib, lib), Map.of(), probes, problems);
        assertEquals(Verdict.UNKNOWN, results.get(0).verdict());
    }

    @Test void staleProbeMtimeAttackIsNotRead() throws Exception {
        // Kills accepting a previous invocation's evidence merely because its filename looks current.
        Path file = Files.writeString(dir.resolve("context-stale.json"), "{");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1));
        assertTrue(read(mojo(new Date(10000))).isEmpty());
    }

    @Test void refreshedMtimeCannotLaunderOldCaptureAttack() throws Exception {
        // Kills checking mtime alone: the capture timestamp must also be from this invocation.
        String identity = ProbeReport.location(dir).replace("\\", "\\\\");
        Files.writeString(dir.resolve("context-laundered.json"), "{\"fullAutoConfig\":true,\"capturedAt\":1,"
                + "\"projectDirectory\":\"" + identity + "\",\"profiles\":[],\"beans\":[],\"autoConfigs\":[]}");
        assertTrue(read(mojo(new Date(10000))).isEmpty());
    }

    @Test void reactorCoordinatePathTraversalAttackIsRejected() throws Exception {
        // Kills resolving untrusted group/artifact coordinates outside the configured report base.
        for (String part : List.of("..", ".", "../outside", "a/b", "a\\b", "C:outside")) {
            assertThrows(java.io.IOException.class, () -> AnalyzeMojo.moduleReportDirectory(dir, part, "app"));
            assertThrows(java.io.IOException.class, () -> AnalyzeMojo.moduleReportDirectory(dir, "group", part));
        }
        assertEquals(dir.resolve("example.group").resolve("app"), AnalyzeMojo.moduleReportDirectory(dir, "example.group", "app"));
    }

    private AnalyzeMojo mojo(Date start) throws Exception {
        AnalyzeMojo mojo = new AnalyzeMojo(null, null);
        MavenProject project = new MavenProject();
        project.setFile(dir.resolve("pom.xml").toFile());
        var request = new DefaultMavenExecutionRequest();
        request.setStartTime(start);
        set(mojo, "project", project);
        set(mojo, "session", new MavenSession(null, null, request, new DefaultMavenExecutionResult()));
        set(mojo, "probeDirectory", dir.toFile());
        return mojo;
    }

    @SuppressWarnings("unchecked")
    private static List<ProbeReport> read(AnalyzeMojo mojo) throws Exception {
        var method = AnalyzeMojo.class.getDeclaredMethod("readProbes", Map.class);
        method.setAccessible(true);
        return (List<ProbeReport>) method.invoke(mojo, Map.of());
    }

    private static Object field(Object target, String name) throws Exception {
        var field = AnalyzeMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = AnalyzeMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
