package io.github.sunjirapath.dak.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputDirectoryTest {
    @TempDir Path project;

    @Test
    void acceptsDirectoryInsideBuild() throws IOException {
        Path build = Files.createDirectories(project.resolve("build"));
        Path output = Files.createDirectory(build.resolve("dependency-analyze-kit"));
        assertEquals(output.toRealPath(), OutputDirectory.validate(output, build, project));
    }

    @Test
    void rejectsBuildDirectoryItself() {
        Path build = project.resolve("build");
        assertThrows(IllegalArgumentException.class, () -> OutputDirectory.validate(build, build, project));
    }

    @Test
    void rejectsProjectDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validate(project, project.resolve("build"), project));
    }

    @Test
    void rejectsParentOfProject() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validate(project.getParent(), project.resolve("build"), project));
    }

    @Test
    void rejectsFilesystemRoot() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validate(project.getRoot(), project.resolve("build"), project));
    }

    @Test
    void rejectsSiblingOutsideBuild() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validate(project.resolve("build-other/dak"), project.resolve("build"), project));
    }

    @Test
    void rejectsParentTraversalOutsideBuild() {
        Path build = project.resolve("build");
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validate(build.resolve("../x"), build, project));
    }

    @Test
    void acceptsNonExistingNestedDirectory() throws IOException {
        Path build = Files.createDirectory(project.resolve("build"));
        assertEquals(build.toRealPath().resolve("reports/dak"),
                OutputDirectory.validate(build.resolve("reports/dak"), build, project));
    }

    @Test
    void acceptsNonExistingBuildDirectory() throws IOException {
        Path build = project.resolve("build");
        assertEquals(project.toRealPath().resolve("build/dependency-analyze-kit"),
                OutputDirectory.validate(build.resolve("dependency-analyze-kit"), build, project));
    }

    @Test
    void rejectsSymlinkEscapeThroughExistingAncestor() throws IOException {
        Path build = Files.createDirectory(project.resolve("build"));
        Path outside = Files.createDirectory(project.resolve("outside"));
        Path link = symlink(build.resolve("link"), outside);
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validate(link.resolve("missing/dak"), build, project));
    }

    @Test
    void resolvesBuildDirectorySymlinkTheSameWay() throws IOException {
        Path realBuild = Files.createDirectory(project.resolve("real-build"));
        Path build = symlink(project.resolve("build"), realBuild);
        assertEquals(realBuild.toRealPath().resolve("reports/dak"),
                OutputDirectory.validate(build.resolve("reports/dak"), build, project));
    }

    @Test
    void rejectsProjectEvenWhenCustomBuildIsItsAncestor() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validate(project, project.getParent(), project));
    }

    private static Path symlink(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links not available: " + e);
            throw new AssertionError(e);
        }
    }
}
