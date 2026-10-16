package io.github.sunjirapath.dak.core;

/** Outcome per declared dependency, strongest first. */
public enum Verdict {
    USED_BYTECODE,
    USED_BEAN,
    USED_AUTOCONFIG,
    UNUSED_CANDIDATE,
    IGNORED,
    UNKNOWN
}
