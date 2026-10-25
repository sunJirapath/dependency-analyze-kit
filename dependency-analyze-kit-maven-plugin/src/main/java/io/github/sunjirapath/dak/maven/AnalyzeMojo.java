package io.github.sunjirapath.dak.maven;

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
import java.util.TreeSet;
import javax.inject.Inject;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.analyzer.DependencyUsage;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzer;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzerException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;

/**
 * Classifies every declared dependency by joining the default bytecode analysis, the resolved dependency
 * graph and the spring-probe reports written during the tests.
 */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class AnalyzeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** Fail the build when any dependency is an UNUSED_CANDIDATE. */
    @Parameter(property = "dak.failOnUnused", defaultValue = "false")
    private boolean failOnUnused;

    /** {@code groupId:artifactId} patterns ({@code *} wildcard) never reported as unused. */
    @Parameter
    private List<String> ignored = List.of();

    /** Starters whose matched auto-configuration counts as usage, on top of the built-in list. */
    @Parameter
    private List<String> sideEffectStarters = List.of();

    /** Report on test-scope dependencies too. */
    @Parameter(property = "dak.includeTestScope", defaultValue = "false")
    private boolean includeTestScope;

    /** Where this goal writes report.json. The system property overrides both probe and report directories. */
    @Parameter(property = "dak.outputDirectory", defaultValue = "${project.build.directory}/dependency-analyze-kit")
    private File outputDirectory;

    /** Match the probe's default even when the Maven build or report directory is customized. */
    @Parameter(property = "dak.outputDirectory", defaultValue = "${project.basedir}/target/dependency-analyze-kit")
    private File probeDirectory;

    @Parameter(property = "dak.skip", defaultValue = "false")
    private boolean skip;

    private final ProjectDependencyAnalyzer analyzer;
    private final RepositorySystem repositorySystem;

    @Inject
    public AnalyzeMojo(ProjectDependencyAnalyzer analyzer, RepositorySystem repositorySystem) {
        this.analyzer = analyzer;
        this.repositorySystem = repositorySystem;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip || "pom".equals(project.getPackaging())) {
            return;
        }
        try {
            List<ProbeReport> probes = readProbes(locations());
            List<Classifier.Declared> declared = project.getDependencies().stream()
                    .filter(d -> includeTestScope || !"test".equals(d.getScope()))
                    .map(d -> new Classifier.Declared(new ArtifactId(d.getGroupId(), d.getArtifactId()),
                            d.getScope() == null ? "compile" : d.getScope()))
                    .distinct()
                    .toList();
            List<Classifier.Result> results = new Classifier(ignored, sideEffectStarters)
                    .classify(declared, Classifier.owners(closures(), testScoped()), bytecode(), probes);

            Reports.table(results).lines().forEach(getLog()::info);
            Files.createDirectories(outputDirectory.toPath());
            File report = new File(outputDirectory, "report.json");
            Files.writeString(report.toPath(), Reports.json(results, probes.size()));
            getLog().info("Report: " + report + " (" + probes.size() + " probe report(s))");

            long unused = results.stream().filter(r -> r.verdict() == Verdict.UNUSED_CANDIDATE).count();
            if (failOnUnused && unused > 0) {
                throw new MojoFailureException(unused + " unused dependency candidate(s); see " + report);
            }
        } catch (IOException | ProjectDependencyAnalyzerException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /** Artifacts referenced from project classes (main and test), with the referenced class names. */
    private Map<ArtifactId, Set<String>> bytecode() throws ProjectDependencyAnalyzerException {
        ProjectDependencyAnalysis analysis = analyzer.analyze(project, null);
        Map<ArtifactId, Set<String>> out = new HashMap<>();
        for (Map<Artifact, Set<DependencyUsage>> m : List.of(analysis.getUsedDeclaredArtifactsWithUsages(),
                analysis.getUsedUndeclaredArtifactsWithUsages())) {
            m.forEach((a, usages) -> usages.forEach(u ->
                    out.computeIfAbsent(id(a), k -> new TreeSet<>()).add(u.getDependencyClass())));
        }
        try {
            annotationReferences().forEach((artifact, classes) ->
                    out.computeIfAbsent(artifact, k -> new TreeSet<>()).addAll(classes));
        } catch (IOException e) {
            getLog().warn("Could not scan annotation type references: " + e.getMessage());
        }
        return out;
    }

    /**
     * Supplements maven-dependency-analyzer with references kept only in class metadata, notably annotations.
     * Annotations such as {@code @EnableFeignClients} are starter usage even though they are not instructions in a
     * method body, and therefore are not always reported by the default analyzer.
     */
    private Map<ArtifactId, Set<String>> annotationReferences() throws IOException {
        Set<String> references = ClassReferenceScanner.scan(List.of(
                Path.of(project.getBuild().getOutputDirectory()), Path.of(project.getBuild().getTestOutputDirectory())));
        Map<ArtifactId, Set<String>> out = new HashMap<>();
        for (Artifact artifact : project.getArtifacts()) {
            File file = artifact.getFile();
            if (file == null || !file.isFile() || !file.getName().endsWith(".jar")) {
                continue;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
                ArtifactId id = id(artifact);
                for (String reference : references) {
                    if (jar.getJarEntry(reference.replace('.', '/') + ".class") != null) {
                        out.computeIfAbsent(id, k -> new TreeSet<>()).add(reference);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Each declared dependency's own transitive closure, collected separately so that an artifact reachable
     * from two declared dependencies is seen under both (a single conflict-resolved tree keeps only one path).
     */
    private Map<ArtifactId, Set<ArtifactId>> closures() throws MojoExecutionException {
        ArtifactTypeRegistry types = session.getRepositorySession().getArtifactTypeRegistry();
        List<org.eclipse.aether.graph.Dependency> managed = project.getDependencyManagement() == null ? List.of()
                : project.getDependencyManagement().getDependencies().stream()
                        .map(d -> RepositoryUtils.toDependency(d, types)).toList();
        Map<ArtifactId, Set<ArtifactId>> closures = new LinkedHashMap<>();
        for (Dependency d : project.getDependencies()) {
            CollectRequest request = new CollectRequest()
                    .setRoot(RepositoryUtils.toDependency(d, types))
                    .setManagedDependencies(managed)
                    .setRepositories(project.getRemoteProjectRepositories());
            DependencyNode root;
            try {
                root = repositorySystem.collectDependencies(session.getRepositorySession(), request).getRoot();
            } catch (DependencyCollectionException e) {
                getLog().warn("Partial dependency graph for " + d.getManagementKey() + ": " + e.getMessage());
                root = e.getResult().getRoot();
            }
            Set<ArtifactId> closure = closures.computeIfAbsent(new ArtifactId(d.getGroupId(), d.getArtifactId()),
                    k -> new HashSet<>());
            collect(root, closure);
        }
        return closures;
    }

    private Set<ArtifactId> testScoped() {
        Set<ArtifactId> out = new HashSet<>();
        project.getDependencies().stream().filter(d -> "test".equals(d.getScope()))
                .forEach(d -> out.add(new ArtifactId(d.getGroupId(), d.getArtifactId())));
        return out;
    }

    private static void collect(DependencyNode node, Set<ArtifactId> into) {
        if (node == null) {
            return;
        }
        if (node.getArtifact() != null) {
            into.add(new ArtifactId(node.getArtifact().getGroupId(), node.getArtifact().getArtifactId()));
        }
        // a collect rooted at a dependency keeps that dependency's optional children; the project never gets them
        node.getChildren().stream().filter(c -> c.getDependency() == null || !c.getDependency().isOptional())
                .forEach(c -> collect(c, into));
    }

    /** Canonical jar or classes-directory path to artifact; the project's own output maps to PROJECT. */
    private Map<String, ArtifactId> locations() throws IOException {
        Map<String, ArtifactId> out = new HashMap<>();
        for (Artifact a : project.getArtifacts()) {
            if (a.getFile() != null) {
                out.put(a.getFile().getCanonicalPath(), id(a));
            }
        }
        out.put(new File(project.getBuild().getOutputDirectory()).getCanonicalPath(), ArtifactId.PROJECT);
        out.put(new File(project.getBuild().getTestOutputDirectory()).getCanonicalPath(), ArtifactId.PROJECT);
        return out;
    }

    /** Probe files from this build only: older ones belong to a previous run and may describe other code. */
    private List<ProbeReport> readProbes(Map<String, ArtifactId> locations) throws IOException {
        List<ProbeReport> probes = new ArrayList<>();
        File[] files = probeDirectory.listFiles((dir, name) -> name.startsWith("context-") && name.endsWith(".json"));
        long since = session.getStartTime().getTime();
        int stale = 0;
        for (File f : files == null ? new File[0] : files) {
            if (f.lastModified() >= since) {
                probes.add(ProbeReport.read(f.toPath(), locations::get));
            } else {
                getLog().debug("Skipping stale probe report " + f);
                stale++;
            }
        }
        if (stale > 0) {
            getLog().warn("Skipped " + stale + " probe report(s) in " + probeDirectory + " from an earlier build;"
                    + " run the tests in the same Maven invocation, e.g. mvn test dependency-analyze-kit:analyze");
        }
        return probes;
    }

    private static ArtifactId id(Artifact a) {
        return new ArtifactId(a.getGroupId(), a.getArtifactId());
    }
}
