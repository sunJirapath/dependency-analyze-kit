package io.github.sunjirapath.dak.core;

/** One observation that ties a declared dependency to (non-)usage. */
public record Evidence(Kind kind, String detail) {

    public enum Kind {
        BYTECODE,
        BEAN,
        AUTOCONFIG_MATCHED,
        AUTOCONFIG_UNMATCHED,
        IGNORED
    }
}
