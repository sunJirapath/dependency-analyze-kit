package io.github.sunjirapath.dak.core;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/** Finds type names embedded in compiled-class metadata, including annotation descriptors. */
public final class ClassReferenceScanner {

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    private ClassReferenceScanner() {}

    /** Missing roots are optional; malformed class metadata is an analysis error, never zero usage. */
    public static Set<String> scan(Iterable<Path> roots) throws IOException {
        Set<String> references = new HashSet<>();
        for (Path root : roots) {
            if (root != null && Files.isSymbolicLink(root)) throw new IOException("Symbolic scan root");
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                var paths = files.iterator();
                int visited = 0;
                while (paths.hasNext()) {
                    Path file = paths.next();
                    if (++visited > 100_000) throw new IOException("Class scan exceeds 100000 paths");
                    if (Files.isSymbolicLink(file)) throw new IOException("Symbolic path in class output");
                    if (!file.toString().endsWith(".class")) continue;
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Non-regular class file");
                    }
                    references.addAll(scanClass(file));
                }
            }
        }
        return references;
    }

    /**
     * Credits each reference to one jar: the one whose groupId shares the most leading package segments with it,
     * else the first on the classpath. Multi-release entries use the build JVM's version. A fat or shaded jar that
     * bundles another library's classes is not credited.
     */
    public static Map<ArtifactId, Set<String>> owners(Set<String> references, Map<ArtifactId, Path> jars)
            throws IOException {
        Map<String, List<ArtifactId>> holders = new HashMap<>();
        for (Map.Entry<ArtifactId, Path> e : jars.entrySet()) {
            if (Files.size(e.getValue()) > 256L * 1024 * 1024) throw new IOException("Jar exceeds 256 MiB");
            try (JarFile jar = new JarFile(e.getValue().toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                if (jar.size() > 100_000) throw new IOException("Jar exceeds 100000 entries");
                Set<String> names = new HashSet<>();
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (!names.add(name) || name.startsWith("/") || name.contains("\\")
                            || List.of(name.split("/")).contains("..")) {
                        throw new IOException("Unsafe or duplicate jar entry name");
                    }
                    if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")
                            && (entry.getSize() < 0 || entry.getSize() > 1024 * 1024)) {
                        throw new IOException("Jar manifest exceeds 1 MiB");
                    }
                }
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
        byte[] data;
        try (var in = Files.newInputStream(file)) {
            data = in.readNBytes(16 * 1024 * 1024 + 1);
        }
        if (data.length > 16 * 1024 * 1024) throw new IOException("Class exceeds 16 MiB");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != CLASS_MAGIC) {
                throw new IOException("invalid class magic in " + file);
            }
            in.readUnsignedShort();
            int major = in.readUnsignedShort();
            if (major < 45 || major > Runtime.version().feature() + 44) {
                throw new IOException("Unsupported class-file version");
            }
            int count = in.readUnsignedShort();
            if (count == 0) throw new IOException("Empty constant pool");
            String[] utf8 = new String[count];
            int[] classNames = new int[count];
            java.util.Arrays.fill(classNames, -1);
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
                if (nameIndex == -1) continue;
                if (nameIndex <= 0 || nameIndex >= utf8.length || utf8[nameIndex] == null) {
                    throw new IOException("invalid constant-pool class index in " + file);
                }
                add(references, utf8[nameIndex]);
            }
            for (String value : utf8) {
                if (value != null) {
                    descriptorReferences(references, value);
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

    private static void descriptorReferences(Set<String> references, String value) {
        int start = -1;
        for (int i = 0; i < value.length();) {
            int c = value.codePointAt(i);
            if (start >= 0 && c == ';') {
                if (i > start) add(references, value.substring(start, i));
                start = -1;
            } else if (start < 0 && c == 'L') {
                start = i + 1;
            } else if (start >= 0 && c != '/' && !Character.isJavaIdentifierPart(c)) {
                start = -1;
            }
            i += Character.charCount(c);
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
