package io.github.sunjirapath.dak.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.CommandLineArgumentProvider;

/** Adds Spring-aware dependency analysis to a Gradle Java project. */
public final class DakPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        DakExtension extension = project.getExtensions().create("dak", DakExtension.class);
        extension.setOutputDirectory(project.getLayout().getBuildDirectory()
                .dir("dependency-analyze-kit").get().getAsFile());
        project.getPluginManager().withPlugin("java", ignored -> configure(project, extension));
    }

    private static void configure(Project project, DakExtension extension) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);
        Provider<File> outputDirectory = project.provider(extension::getOutputDirectory);
        project.getTasks().register("dakClean", Delete.class, task -> task.delete(outputDirectory));
        // each Test task owns a subdirectory, so one task's run neither wipes nor stales another's probe reports
        project.getTasks().withType(Test.class).configureEach(task -> {
            String name = task.getName();
            ProbeDirectory probes = new ProbeDirectory(outputDirectory.map(dir -> new File(dir, name)));
            task.getOutputs().dir(probes.getDirectory());
            task.systemProperty("dak.enabled", true);
            task.getJvmArgumentProviders().add(probes);
            task.doFirst(probes);
        });
        Configuration runtime = project.getConfigurations().getByName("runtimeClasspath");
        TaskProvider<DakAnalyzeTask> analyze = project.getTasks().register("dependencyAnalyzeKit", DakAnalyzeTask.class, task -> {
            task.dependsOn(project.getTasks().withType(Test.class));
            task.getProjectClasses().from(main.getOutput().getClassesDirs(), test.getOutput().getClassesDirs());
            task.getRuntimeClasspath().from(runtime);
            task.getProbeReports().from(project.fileTree(outputDirectory, tree -> tree.include("**/context-*.json")));
            task.getRootComponent().set(runtime.getIncoming().getResolutionResult().getRootComponent());
            task.getArtifacts().set(runtime.getIncoming().getArtifacts().getResolvedArtifacts());
            task.getScopes().set(project.provider(() -> scopes(project)));
            task.getIgnored().set(project.provider(extension::getIgnored));
            task.getSideEffectStarters().set(project.provider(extension::getSideEffectStarters));
            task.getFailOnUnused().set(project.provider(extension::isFailOnUnused));
            task.getReportFile().set(project.getLayout().file(outputDirectory.map(dir -> new File(dir, "report.json"))));
        });
        project.getTasks().named("check").configure(task -> task.dependsOn(analyze));
    }

    /** Scope of each dependency declared in a main configuration; a compile-time declaration wins. */
    private static Map<String, String> scopes(Project project) {
        Map<String, String> out = new HashMap<>();
        for (List<String> entry : List.of(List.of("runtimeOnly", "runtime"), List.of("implementation", "compile"),
                List.of("api", "compile"))) {
            Configuration configuration = project.getConfigurations().findByName(entry.get(0));
            if (configuration == null) continue;
            for (Dependency d : configuration.getDependencies()) {
                if (d.getGroup() != null) out.put(d.getGroup() + ":" + d.getName(), entry.get(1));
            }
        }
        return out;
    }

    /** Empties a Test task's probe directory before it runs and points the probe at it. */
    static final class ProbeDirectory implements Action<Task>, CommandLineArgumentProvider {
        private final Provider<File> directory;

        ProbeDirectory(Provider<File> directory) { this.directory = directory; }

        @Internal
        Provider<File> getDirectory() { return directory; }

        @Override
        public Iterable<String> asArguments() {
            return List.of("-Ddak.outputDirectory=" + directory.get().getAbsolutePath());
        }

        @Override
        public void execute(Task task) {
            Path dir = directory.get().toPath();
            try {
                if (Files.exists(dir)) {
                    try (Stream<Path> paths = Files.walk(dir)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not reset " + dir, e);
            }
        }
    }
}
