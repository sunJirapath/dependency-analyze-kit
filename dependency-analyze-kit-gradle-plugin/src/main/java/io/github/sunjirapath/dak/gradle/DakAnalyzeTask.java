package io.github.sunjirapath.dak.gradle;

import io.github.sunjirapath.dak.core.ArtifactId;
import io.github.sunjirapath.dak.core.Classifier;
import io.github.sunjirapath.dak.core.ClassReferenceScanner;
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
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.Category;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Classifies the main runtime dependency graph after the project's tests have produced probe reports. */
public abstract class DakAnalyzeTask extends DefaultTask {
    private static final Attribute<String> CATEGORY = Attribute.of(Category.CATEGORY_ATTRIBUTE.getName(), String.class);

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProjectClasses();

    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    /** The {@code context-*.json} files the probe wrote, one directory per Test task. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProbeReports();

    @Input
    public abstract Property<ResolvedComponentResult> getRootComponent();

    @Internal
    public abstract SetProperty<ResolvedArtifactResult> getArtifacts();

    /** Reported scope by {@code group:name} of the directly declared dependencies. */
    @Input
    public abstract MapProperty<String, String> getScopes();

    @Input
    public abstract ListProperty<String> getIgnored();

    @Input
    public abstract ListProperty<String> getSideEffectStarters();

    @Input
    public abstract Property<Boolean> getFailOnUnused();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void analyze() {
        File report = getReportFile().get().getAsFile();
        try {
            Map<ComponentIdentifier, ArtifactId> ids = ids();
            Map<String, ArtifactId> locations = locations(ids);
            List<ProbeReport> probes = probes(locations);
            Set<ArtifactId> modules = new HashSet<>();
            ids.forEach((component, id) -> {
                if (component instanceof ProjectComponentIdentifier && id != ArtifactId.PROJECT) modules.add(id);
            });
            List<Classifier.Declared> declared = declared(ids);
            Map<String, String> scopes = getScopes().get();
            Set<ArtifactId> runtimeOnly = new HashSet<>();
            declared.forEach(d -> { if ("runtime".equals(scopes.get(d.id().toString()))) runtimeOnly.add(d.id()); });
            List<Classifier.Result> results = new Classifier(getIgnored().get(), getSideEffectStarters().get(), modules)
                    .classify(declared, Classifier.owners(closures(ids), Set.of(), runtimeOnly), bytecode(ids), probes);
            Reports.table(results).lines().forEach(line -> getLogger().lifecycle(line));
            Files.createDirectories(report.toPath().getParent());
            Files.writeString(report.toPath(), Reports.json(results, probes.size()));
            getLogger().lifecycle("Dependency analysis report: " + report + " (" + probes.size() + " probe report(s))");
            long unused = results.stream().filter(r -> r.verdict() == Verdict.UNUSED_CANDIDATE).count();
            if (getFailOnUnused().get() && unused > 0) {
                throw new GradleException(unused + " unused dependency candidate(s); see " + report);
            }
        } catch (IOException e) {
            throw new GradleException("Could not analyze dependencies", e);
        }
    }

    /** {@code group:name} of every resolved component, sibling projects included; the root is the project itself. */
    private Map<ComponentIdentifier, ArtifactId> ids() {
        Map<ComponentIdentifier, ArtifactId> out = new HashMap<>();
        ResolvedComponentResult root = getRootComponent().get();
        List<ResolvedComponentResult> queue = new ArrayList<>(List.of(root));
        while (!queue.isEmpty()) {
            ResolvedComponentResult component = queue.remove(queue.size() - 1);
            ModuleVersionIdentifier module = component.getModuleVersion();
            if (out.containsKey(component.getId()) || module == null) continue;
            out.put(component.getId(), component == root ? ArtifactId.PROJECT : new ArtifactId(module.getGroup(), module.getName()));
            for (DependencyResult edge : component.getDependencies()) {
                if (edge instanceof ResolvedDependencyResult resolved) queue.add(resolved.getSelected());
            }
        }
        return out;
    }

    /** The project's own dependency edges, minus {@code platform()} BOM imports, which bring no classes. */
    private List<ResolvedDependencyResult> directEdges() {
        List<ResolvedDependencyResult> out = new ArrayList<>();
        for (DependencyResult edge : getRootComponent().get().getDependencies()) {
            if (edge instanceof ResolvedDependencyResult resolved && !platform(resolved)) out.add(resolved);
        }
        return out;
    }

    private static boolean platform(ResolvedDependencyResult edge) {
        return isPlatform(edge.getRequested().getAttributes().getAttribute(CATEGORY))
                || isPlatform(edge.getResolvedVariant().getAttributes().getAttribute(CATEGORY));
    }

    private static boolean isPlatform(String category) {
        return Category.REGULAR_PLATFORM.equals(category) || Category.ENFORCED_PLATFORM.equals(category);
    }

    private List<Classifier.Declared> declared(Map<ComponentIdentifier, ArtifactId> ids) {
        Map<String, String> scopes = getScopes().get();
        List<Classifier.Declared> out = new ArrayList<>();
        for (ResolvedDependencyResult edge : directEdges()) {
            ArtifactId id = ids.get(edge.getSelected().getId());
            if (id != null) out.add(new Classifier.Declared(id, scopes.getOrDefault(id.toString(), "runtime")));
        }
        return out.stream().distinct().toList();
    }

    private Map<ArtifactId, Set<ArtifactId>> closures(Map<ComponentIdentifier, ArtifactId> ids) {
        Map<ArtifactId, Set<ArtifactId>> out = new LinkedHashMap<>();
        for (ResolvedDependencyResult edge : directEdges()) {
            ArtifactId root = ids.get(edge.getSelected().getId());
            if (root == null) continue;
            Set<ArtifactId> closure = out.computeIfAbsent(root, k -> new HashSet<>());
            visit(edge.getSelected(), ids, closure, new HashSet<>());
        }
        return out;
    }

    private static void visit(ResolvedComponentResult component, Map<ComponentIdentifier, ArtifactId> ids,
                              Set<ArtifactId> closure, Set<ComponentIdentifier> seen) {
        if (!seen.add(component.getId())) return;
        ArtifactId id = ids.get(component.getId());
        if (id != null && id != ArtifactId.PROJECT) closure.add(id);
        for (DependencyResult edge : component.getDependencies()) {
            if (edge instanceof ResolvedDependencyResult resolved) visit(resolved.getSelected(), ids, closure, seen);
        }
    }

    private Map<ArtifactId, Set<String>> bytecode(Map<ComponentIdentifier, ArtifactId> ids) throws IOException {
        Set<Path> roots = new HashSet<>();
        for (File file : getProjectClasses().getFiles()) roots.add(file.toPath());
        Map<ArtifactId, Path> jars = new LinkedHashMap<>();
        for (ResolvedArtifactResult artifact : getArtifacts().get()) {
            ArtifactId id = ids.get(artifact.getId().getComponentIdentifier());
            File file = artifact.getFile();
            if (id != null && file.isFile() && file.getName().endsWith(".jar")) jars.putIfAbsent(id, file.toPath());
        }
        return ClassReferenceScanner.owners(ClassReferenceScanner.scan(roots), jars);
    }

    private Map<String, ArtifactId> locations(Map<ComponentIdentifier, ArtifactId> ids) throws IOException {
        Map<String, ArtifactId> out = new HashMap<>();
        for (ResolvedArtifactResult artifact : getArtifacts().get()) {
            ArtifactId id = ids.get(artifact.getId().getComponentIdentifier());
            if (id != null) out.put(artifact.getFile().getCanonicalPath(), id);
        }
        for (File directory : getProjectClasses().getFiles()) out.put(directory.getCanonicalPath(), ArtifactId.PROJECT);
        return out;
    }

    private List<ProbeReport> probes(Map<String, ArtifactId> locations) throws IOException {
        List<ProbeReport> out = new ArrayList<>();
        for (File file : getProbeReports().getFiles()) out.add(ProbeReport.read(file.toPath(), locations::get));
        return out;
    }
}
