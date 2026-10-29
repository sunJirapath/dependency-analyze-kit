package io.github.sunjirapath.dak.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds type names embedded in compiled-class metadata, including annotation descriptors. */
public final class ClassReferenceScanner {

    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([A-Za-z0-9_$/]+);");

    private ClassReferenceScanner() {}

    public static Set<String> scan(Iterable<Path> roots) throws IOException {
        Set<String> references = new HashSet<>();
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    references.addAll(scanClass(file));
                }
            }
        }
        return references;
    }

    /**
     * Credits each reference to one jar: the one whose groupId shares the most leading package segments with it,
     * else the first on the classpath. A fat or shaded jar that bundles another library's classes is not credited.
     */
    public static Map<ArtifactId, Set<String>> owners(Set<String> references, Map<ArtifactId, Path> jars)
            throws IOException {
        Map<String, List<ArtifactId>> holders = new HashMap<>();
        for (Map.Entry<ArtifactId, Path> e : jars.entrySet()) {
            try (JarFile jar = new JarFile(e.getValue().toFile())) {
                for (String reference : references) {
                    if (jar.getJarEntry(reference.replace('.', '/') + ".class") != null) {
                        holders.computeIfAbsent(reference, k -> new ArrayList<>()).add(e.getKey());
                    }
                }
            }
        }
        Map<ArtifactId, Set<String>> out = new HashMap<>();
        holders.forEach((reference, ids) -> {
            ArtifactId best = ids.get(0);
            for (ArtifactId id : ids) {
                if (sharedSegments(id.groupId(), reference) > sharedSegments(best.groupId(), reference)) {
                    best = id;
                }
            }
            out.computeIfAbsent(best, k -> new TreeSet<>()).add(reference);
        });
        return out;
    }

    private static int sharedSegments(String groupId, String className) {
        String[] a = groupId.split("\\.");
        String[] b = className.split("\\.");
        int n = 0;
        while (n < a.length && n < b.length && a[n].equals(b[n])) {
            n++;
        }
        return n;
    }

    private static Set<String> scanClass(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != CLASS_MAGIC) {
                return Set.of();
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNames = new int[count];
            for (int i = 1; i < count; i++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> skipFully(in, Integer.BYTES);
                    case 5, 6 -> {
                        skipFully(in, Long.BYTES);
                        i++;
                    }
                    case 7 -> classNames[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> skipFully(in, Short.BYTES);
                    case 9, 10, 11, 12, 17, 18 -> skipFully(in, Integer.BYTES);
                    case 15 -> skipFully(in, 3);
                    default -> throw new IOException("unknown constant-pool tag in " + file);
                }
            }
            Set<String> references = new HashSet<>();
            for (int nameIndex : classNames) {
                add(references, utf8[nameIndex]);
            }
            for (String value : utf8) {
                if (value != null) {
                    DESCRIPTOR_TYPE.matcher(value).results().map(m -> m.group(1)).forEach(n -> add(references, n));
                }
            }
            return references;
        }
    }

    private static void add(Set<String> references, String name) {
        if (name != null && !name.startsWith("[")) {
            references.add(name.replace('/', '.'));
        }
    }

    private static void skipFully(DataInputStream in, int bytes) throws IOException {
        for (int left = bytes; left > 0;) {
            int skipped = in.skipBytes(left);
            if (skipped == 0) {
                in.readByte();
                skipped = 1;
            }
            left -= skipped;
        }
    }
}
