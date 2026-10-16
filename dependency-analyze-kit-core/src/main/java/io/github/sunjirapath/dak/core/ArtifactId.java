package io.github.sunjirapath.dak.core;

/** Versionless {@code groupId:artifactId}. */
public record ArtifactId(String groupId, String artifactId) {

    /** Marks a class location inside the analyzed project's own output directories. */
    public static final ArtifactId PROJECT = new ArtifactId("", "<project>");

    public static ArtifactId parse(String ga) {
        int i = ga.indexOf(':');
        return new ArtifactId(ga.substring(0, i), ga.substring(i + 1));
    }

    /** {@code pattern} is {@code groupId:artifactId}, where {@code *} matches any run of characters. */
    public boolean matches(String pattern) {
        return toString().matches(("\\Q" + pattern.trim() + "\\E").replace("*", "\\E.*\\Q"));
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
