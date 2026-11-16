package io.github.sunjirapath.dak.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OutputDirectoryLexicalTest {
    private final Path project = Path.of("lexical-project").toAbsolutePath().normalize();
    private final Path build = project.resolve("build");

    @Test
    void rejectsBuildDirectoryItself() {
        assertThrows(IllegalArgumentException.class, () -> OutputDirectory.validateLexically(build, build, project));
    }

    @Test
    void rejectsProjectEvenWhenBuildIsItsAncestor() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validateLexically(project, project.getParent(), project));
    }

    @Test
    void rejectsParentTraversalOutsideBuild() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputDirectory.validateLexically(build.resolve("../outside"), build, project));
    }

    @Test
    void acceptsNonExistingNestedDirectory() {
        assertEquals(build.resolve("reports/dak"),
                OutputDirectory.validateLexically(build.resolve("reports/unused/../dak"), build, project));
    }
}
