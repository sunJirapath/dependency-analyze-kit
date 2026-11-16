package io.github.sunjirapath.dak.gradle;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/** Instantiated at execution time, once per build, including configuration-cache reuse. */
public abstract class BuildStartService implements BuildService<BuildServiceParameters.None> {
    private final long startedAt;

    public BuildStartService() {
        startedAt = System.currentTimeMillis();
    }

    public long getStartedAt() { return startedAt; }
}
