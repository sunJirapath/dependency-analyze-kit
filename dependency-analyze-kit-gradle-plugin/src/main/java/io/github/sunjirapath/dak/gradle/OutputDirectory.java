package io.github.sunjirapath.dak.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.gradle.api.GradleException;

final class OutputDirectory {
    private OutputDirectory() {}

    /** Configuration-cache providers must not observe filesystem state. */
    static Path validateLexically(Path output, Path build, Path project) {
        Path normalizedOutput = output.toAbsolutePath().normalize();
        Path normalizedBuild = build.toAbsolutePath().normalize();
        Path normalizedProject = project.toAbsolutePath().normalize();
        if (!normalizedOutput.startsWith(normalizedBuild) || normalizedOutput.equals(normalizedBuild)
                || normalizedProject.startsWith(normalizedOutput)) {
            throw new IllegalArgumentException("unsafe output directory " + output + "; build directory " + build);
        }
        return normalizedOutput;
    }

    static Path validate(Path output, Path build, Path project) throws IOException {
        Path realOutput = resolve(validateLexically(output, build, project));
        Path realBuild = resolve(build.toAbsolutePath().normalize());
        Path realProject = resolve(project.toAbsolutePath().normalize());
        if (!realOutput.startsWith(realBuild) || realOutput.equals(realBuild)
                || realProject.startsWith(realOutput)) {
            throw new IllegalArgumentException("unsafe output directory " + output + "; build directory " + build);
        }
        return realOutput;
    }

    static Path validateForExecution(Path output, Path build, Path project) {
        try {
            return validate(output, build, project);
        } catch (IOException | IllegalArgumentException e) {
            throw new GradleException("unsafe output directory " + output + "; build directory " + build, e);
        }
    }

    /** Resolve existing links even when the output directory has not been created yet. */
    private static Path resolve(Path path) throws IOException {
        Path ancestor = path;
        while (Files.notExists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
    }
}
