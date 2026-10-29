package io.github.sunjirapath.dak.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeReportTest {
    @Test
    void jarRecordedThroughALinkResolvesToTheSameArtifact(@TempDir Path dir) throws IOException {
        Path real = Files.createDirectories(dir.resolve("real"));
        Path jar = Files.createFile(real.resolve("lib.jar"));
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows needs a privilege for symbolic links but not for junctions, the case seen in the field (~/.m2)
            assumeTrue(System.getProperty("os.name").startsWith("Windows"), "symbolic links not available: " + e);
            assumeTrue(junction(link, real), "neither symbolic links nor junctions available: " + e);
        }
        ArtifactId lib = new ArtifactId("g", "lib");
        // the build sees the jar through the link, the test JVM (JDK 24+ on Windows) recorded where the link points
        Map<String, ArtifactId> locations = Map.of(ProbeReport.location(link.resolve("lib.jar")), lib);
        Path file = dir.resolve("context-1.json");
        Files.writeString(file, "{\"id\":\"1\",\"autoConfigs\":[{\"name\":\"A\",\"matched\":true,\"locations\":[\""
                + jar.toAbsolutePath().toString().replace("\\", "\\\\") + "\"]}]}");

        ProbeReport report = ProbeReport.read(file, locations::get);

        assertEquals(Set.of(lib), report.autoConfigs().get(0).artifacts());
    }

    private static boolean junction(Path link, Path target) {
        try {
            return new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
