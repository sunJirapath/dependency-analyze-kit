package io.github.sunjirapath.dak.gradle;

final class ProbeFreshness {
    private ProbeFreshness() {}

    static boolean isFresh(long fileLastModified, long capturedAt, long buildStart) {
        return fileLastModified >= buildStart && capturedAt >= buildStart;
    }
}
