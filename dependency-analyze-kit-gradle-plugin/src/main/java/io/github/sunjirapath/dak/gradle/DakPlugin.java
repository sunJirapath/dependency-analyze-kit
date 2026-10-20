package io.github.sunjirapath.dak.gradle;

import java.io.File;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

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
        TaskProvider<Delete> clean = project.getTasks().register("dakClean", Delete.class,
                task -> task.delete(extension.getOutputDirectory()));
        project.getTasks().named("test", Test.class).configure(task -> {
            task.dependsOn(clean);
            task.systemProperty("dak.enabled", true);
            task.systemProperty("dak.outputDirectory", extension.getOutputDirectory().getAbsolutePath());
        });
        TaskProvider<DakAnalyzeTask> analyze = project.getTasks().register("dependencyAnalyzeKit", DakAnalyzeTask.class, task -> {
            task.dependsOn(project.getTasks().named("test"));
            task.setExtension(extension);
            task.setRuntimeConfiguration(project.getConfigurations().getByName("runtimeClasspath"));
            task.getProjectClasses().from(main.getOutput().getClassesDirs(), test.getOutput().getClassesDirs());
            task.getRuntimeClasspath().from(project.getConfigurations().getByName("runtimeClasspath"));
        });
        project.getTasks().named("check").configure(task -> task.dependsOn(analyze));
    }
}
