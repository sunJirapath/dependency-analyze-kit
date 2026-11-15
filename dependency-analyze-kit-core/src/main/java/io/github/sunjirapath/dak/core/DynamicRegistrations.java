package io.github.sunjirapath.dak.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Registrations in a declared dependency's own jar that can cause invisible runtime usage. */
public final class DynamicRegistrations {
    static final String SERVICES = "META-INF/services";
    static final String FACTORIES = "spring.factories";
    static final String IMPORTS = "spring/*.imports";

    private DynamicRegistrations() {}

    /** Unsafe or unreadable jars retain their dependency rather than establishing absence of registrations. */
    public static Map<ArtifactId, String> scan(Map<ArtifactId, Path> jars) {
        Map<ArtifactId, String> out = new LinkedHashMap<>();
        jars.forEach((id, path) -> {
            try {
                String reason = scan(path);
                if (reason != null) out.put(id, reason);
            } catch (IOException | SecurityException e) {
                out.put(id, "jar unreadable");
            }
        });
        return out;
    }

    private static String scan(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular jar");
        if (Files.size(path) > 256L * 1024 * 1024) throw new IOException("Jar exceeds 256 MiB");
        // Metadata only: avoid signature verification loading a manifest before its size is checked.
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.size() > 100_000) throw new IOException("Jar exceeds 100000 entries");
            Set<String> names = new HashSet<>();
            String reason = null;
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name) || name.startsWith("/") || name.contains("\\")
                        || List.of(name.split("/")).contains("..")) {
                    throw new IOException("Unsafe or duplicate jar entry name");
                }
                boolean service = name.startsWith("META-INF/services/");
                boolean factories = name.equals("META-INF/spring.factories");
                boolean imports = name.startsWith("META-INF/spring/") && name.endsWith(".imports");
                if ((service || factories || imports || name.equalsIgnoreCase("META-INF/MANIFEST.MF"))
                        && (entry.getSize() < 0 || entry.getSize() > 1024 * 1024)) {
                    throw new IOException("Jar metadata exceeds 1 MiB");
                }
                if (entry.isDirectory()) continue;
                if (service) {
                    // A single byte establishes non-emptiness without inflating arbitrary metadata.
                    try (var in = jar.getInputStream(entry)) {
                        if (in.read() != -1) reason = SERVICES;
                    }
                } else if (factories && !SERVICES.equals(reason)) {
                    reason = FACTORIES;
                } else if (imports && reason == null) {
                    reason = IMPORTS;
                }
            }
            return reason;
        }
    }
}
