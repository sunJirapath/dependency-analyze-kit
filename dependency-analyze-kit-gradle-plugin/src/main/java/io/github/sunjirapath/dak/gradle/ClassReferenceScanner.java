package io.github.sunjirapath.dak.gradle;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds all class names referenced by compiled project classes, including annotation metadata. */
final class ClassReferenceScanner {
    private static final int MAGIC = 0xCAFEBABE;
    private static final Pattern DESCRIPTOR = Pattern.compile("L([A-Za-z0-9_$/]+);");

    private ClassReferenceScanner() {}

    static Set<String> scan(Iterable<Path> roots) throws IOException {
        Set<String> out = new HashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) out.addAll(read(file));
            }
        }
        return out;
    }

    private static Set<String> read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC) return Set.of();
            in.readUnsignedShort(); in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classes = new int[count];
            for (int i = 1; i < count; i++) switch (in.readUnsignedByte()) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> skip(in, 4);
                case 5, 6 -> { skip(in, 8); i++; }
                case 7 -> classes[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> skip(in, 2);
                case 9, 10, 11, 12, 17, 18 -> skip(in, 4);
                case 15 -> skip(in, 3);
                default -> throw new IOException("unknown class-file constant-pool tag: " + file);
            };
            Set<String> out = new HashSet<>();
            for (int index : classes) add(out, utf8[index]);
            for (String value : utf8) if (value != null) DESCRIPTOR.matcher(value).results()
                    .map(m -> m.group(1)).forEach(name -> add(out, name));
            return out;
        }
    }

    private static void add(Set<String> out, String name) {
        if (name != null && !name.startsWith("[")) out.add(name.replace('/', '.'));
    }

    private static void skip(DataInputStream in, int bytes) throws IOException {
        for (int remaining = bytes; remaining > 0;) {
            int skipped = in.skipBytes(remaining);
            if (skipped == 0) { in.readByte(); skipped = 1; }
            remaining -= skipped;
        }
    }
}
