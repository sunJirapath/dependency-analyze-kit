package io.github.sunjirapath.dak.maven;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReadmeTest {
    @Test
    void introductionNamesBothBuildTools() throws IOException {
        String introduction = Files.readString(Path.of("..", "README.md")).split("## English", 2)[0];
        assertTrue(introduction.contains("Maven and Gradle plugins"));
    }

    @Test
    void gradleSupportSeparatesTestedVersionFromUnestablishedMinimum() throws IOException {
        String readme = Files.readString(Path.of("..", "README.md"));
        String pom = Files.readString(Path.of("..", "pom.xml"));
        String api = pom.split("<gradle.api.version>", 2)[1].split("</gradle.api.version>", 2)[0];
        var ci = Pattern.compile("(?m)^\\s*gradle-version: ([\\d.]+)\\s*$")
                .matcher(Files.readString(Path.of("..", ".github", "workflows", "build.yml")));
        assertTrue(ci.find(), "CI Gradle version not found");
        assertTrue(readme.contains("CI exercises Gradle **" + ci.group(1) + "**"));
        assertTrue(readme.contains("Compiled against Gradle API **" + api + "**"));
        assertTrue(readme.contains("The minimum supported Gradle version is not established"));
    }

    @Test
    void profileRecipeActivatesSpringProfile() throws IOException {
        String readme = Files.readString(Path.of("..", "README.md"));
        String recipe = readme.split("#### Run once per profile", 2)[1].split("#### ", 2)[0];
        String command = recipe.lines().filter(line -> line.startsWith("mvn ")).findFirst().orElseThrow();

        assertTrue(Arrays.asList(command.split("\\s+")).contains("-Dspring.profiles.active=prod"),
                "The profile recipe must activate Spring's prod profile, not just a Maven profile");
    }
}
