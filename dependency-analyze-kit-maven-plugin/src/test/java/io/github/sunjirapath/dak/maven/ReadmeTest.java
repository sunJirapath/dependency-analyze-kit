package io.github.sunjirapath.dak.maven;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReadmeTest {
    @Test
    void profileRecipeActivatesSpringProfile() throws IOException {
        String readme = Files.readString(Path.of("..", "README.md"));
        String recipe = readme.split("#### Run once per profile", 2)[1].split("#### ", 2)[0];
        String command = recipe.lines().filter(line -> line.startsWith("mvn ")).findFirst().orElseThrow();

        assertTrue(Arrays.asList(command.split("\\s+")).contains("-Dspring.profiles.active=prod"),
                "The profile recipe must activate Spring's prod profile, not just a Maven profile");
    }
}
