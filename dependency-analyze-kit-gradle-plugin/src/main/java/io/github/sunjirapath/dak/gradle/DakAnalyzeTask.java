package io.github.sunjirapath.dak.gradle;

import io.github.sunjirapath.dak.core.ArtifactId;
import io.github.sunjirapath.dak.core.Classifier;
import io.github.sunjirapath.dak.core.ProbeReport;
import io.github.sunjirapath.dak.core.Reports;
import io.github.sunjirapath.dak.core.Verdict;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Classifies the main runtime dependency graph after the project's tests have produced probe reports. */
public abstract class DakAnalyzeTask extends DefaultTask {
    private final ConfigurableFileCollection projectClasses = getProject().getObjects().fileCollection();
    private final ConfigurableFileCollection runtimeClasspath = getProject().getObjects().fileCollection();
    private DakExtension extension;
    private Configuration runtimeConfiguration;

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public ConfigurableFileCollection getProjectClasses() { return projectClasses; }

    @Classpath
    public ConfigurableFileCollection getRuntimeClasspath() { return runtimeClasspath; }

    @Internal
    public DakExtension getExtension() { return extension; }
    public void setExtension(DakExtension extension) { this.extension = extension; }

    @Internal
    public Configuration getRuntimeConfiguration() { return runtimeConfiguration; }
    public void setRuntimeConfiguration(Configuration runtimeConfiguration) { this.runtimeConfiguration = runtimeConfiguration; }

    @OutputFile
    public File getReportFile() { return new File(extension.getOutputDirectory(), "report.json"); }

    @TaskAction
    public void analyze() {
        try {
            Map<String, ArtifactId> locations = locations();
            List<ProbeReport> probes = probes(locations);
            Map<ArtifactId, Set<ArtifactId>> closures = closures();
            List<Classifier.Declared> declared = declared();
            List<Classifier.Result> results = new Classifier(extension.getIgnored(), extension.getSideEffectStarters())
                    .classify(declared, Classifier.owners(closures, Set.of()), bytecode(), probes);
            Reports.table(results).lines().forEach(line -> getLogger().lifecycle(line));
            Files.createDirectories(getReportFile().toPath().getParent());
            Files.writeString(getReportFile().toPath(), Reports.json(results, probes.size()));
            getLogger().lifecycle("Dependency analysis report: " + getReportFile());
            long unused = results.stream().filter(r -> r.verdict() == Verdict.UNUSED_CANDIDATE).count();
            if (extension.isFailOnUnused() && unused > 0) {
                throw new GradleException(unused + " unused dependency candidate(s); see " + getReportFile());
            }
        } catch (IOException e) {
            throw new GradleException("Could not analyze dependencies", e);
        }
    }

    private List<Classifier.Declared> declared() {
        List<Classifier.Declared> out = new ArrayList<>();
        for (DependencyResult edge : runtimeConfiguration.getIncoming().getResolutionResult().getRoot().getDependencies()) {
            if (edge instanceof ResolvedDependencyResult resolved) {
                ArtifactId id = id(resolved.getSelected().getId());
                if (id != null) out.add(new Classifier.Declared(id, "runtime"));
            }
        }
        return out.stream().distinct().toList();
    }

    private Map<ArtifactId, Set<ArtifactId>> closures() {
        Map<ArtifactId, Set<ArtifactId>> out = new LinkedHashMap<>();
        for (DependencyResult edge : runtimeConfiguration.getIncoming().getResolutionResult().getRoot().getDependencies()) {
            if (edge instanceof ResolvedDependencyResult resolved) {
                ArtifactId root = id(resolved.getSelected().getId());
                if (root != null) {
                    Set<ArtifactId> closure = new HashSet<>();
                    visit(resolved.getSelected(), closure, new HashSet<>());
                    out.put(root, closure);
                }
            }
        }
        return out;
    }

    private static void visit(ResolvedComponentResult component, Set<ArtifactId> closure, Set<ComponentIdentifier> seen) {
        if (!seen.add(component.getId())) return;
        ArtifactId id = id(component.getId());
        if (id != null) closure.add(id);
        for (DependencyResult edge : component.getDependencies()) {
            if (edge instanceof ResolvedDependencyResult resolved) visit(resolved.getSelected(), closure, seen);
        }
    }

    private Map<ArtifactId, Set<String>> bytecode() throws IOException {
        Set<Path> roots = new HashSet<>();
        for (File file : projectClasses.getFiles()) roots.add(file.toPath());
        Set<String> references = ClassReferenceScanner.scan(roots);
        Map<ArtifactId, Set<String>> out = new HashMap<>();
        for (ResolvedArtifactResult artifact : runtimeConfiguration.getIncoming().getArtifacts().getArtifacts()) {
            ArtifactId id = id(artifact.getId().getComponentIdentifier());
            File file = artifact.getFile();
            if (id == null || !file.isFile() || !file.getName().endsWith(".jar")) continue;
            try (JarFile jar = new JarFile(file)) {
                for (String reference : references) {
                    if (jar.getJarEntry(reference.replace('.', '/') + ".class") != null) {
                        out.computeIfAbsent(id, ignored -> new TreeSet<>()).add(reference);
                    }
                }
            }
        }
        return out;
    }

    private Map<String, ArtifactId> locations() throws IOException {
        Map<String, ArtifactId> out = new HashMap<>();
        for (ResolvedArtifactResult artifact : runtimeConfiguration.getIncoming().getArtifacts().getArtifacts()) {
            ArtifactId id = id(artifact.getId().getComponentIdentifier());
            if (id != null) out.put(artifact.getFile().getCanonicalPath(), id);
        }
        for (File directory : projectClasses.getFiles()) out.put(directory.getCanonicalPath(), ArtifactId.PROJECT);
        return out;
    }

    private List<ProbeReport> probes(Map<String, ArtifactId> locations) throws IOException {
        File[] files = extension.getOutputDirectory().listFiles((dir, name) ->
                name.startsWith("context-") && name.endsWith(".json"));
        List<ProbeReport> out = new ArrayList<>();
        for (File file : files == null ? new File[0] : files) out.add(ProbeReport.read(file.toPath(), locations::get));
        return out;
    }

    private static ArtifactId id(ComponentIdentifier component) {
        if (component instanceof ModuleComponentIdentifier module) return new ArtifactId(module.getGroup(), module.getModule());
        return null;
    }
}
