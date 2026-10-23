package io.github.sunjirapath.dak.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Configuration for the {@code io.github.sunjirapath.dependency-analyze-kit} Gradle plugin. Not final: Gradle subclasses extensions. */
public class DakExtension {
    private boolean failOnUnused;
    private final List<String> ignored = new ArrayList<>();
    private final List<String> sideEffectStarters = new ArrayList<>();
    private File outputDirectory;

    public boolean isFailOnUnused() { return failOnUnused; }
    public void setFailOnUnused(boolean failOnUnused) { this.failOnUnused = failOnUnused; }
    public List<String> getIgnored() { return ignored; }
    public void setIgnored(List<String> ignored) { this.ignored.clear(); this.ignored.addAll(ignored); }
    public List<String> getSideEffectStarters() { return sideEffectStarters; }
    public void setSideEffectStarters(List<String> starters) { sideEffectStarters.clear(); sideEffectStarters.addAll(starters); }
    public File getOutputDirectory() { return outputDirectory; }
    public void setOutputDirectory(File outputDirectory) { this.outputDirectory = outputDirectory; }
}
