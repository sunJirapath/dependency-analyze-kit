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
        String value = toString();
        String glob = pattern.trim();
        int v = 0, p = 0, star = -1, retry = 0;
        while (v < value.length()) {
            if (p < glob.length() && glob.charAt(p) != '*' && glob.charAt(p) == value.charAt(v)) {
                p++;
                v++;
            } else if (p < glob.length() && glob.charAt(p) == '*') {
                star = p++;
                retry = v;
            } else if (star >= 0) {
                p = star + 1;
                v = ++retry;
            } else {
                return false;
            }
        }
        while (p < glob.length() && glob.charAt(p) == '*') p++;
        return p == glob.length();
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
