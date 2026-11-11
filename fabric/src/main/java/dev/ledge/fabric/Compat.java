package dev.ledge.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Java 8 stand-ins for the Java 9+ helpers the newer branches use (Minecraft 1.16.5 may run on Java 8). */
final class Compat {
    private Compat() { }

    @SafeVarargs
    static <T> List<T> listOf(T... values) { return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(values))); }

    static <T> List<T> copyOf(Collection<? extends T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }

    static byte[] readNBytes(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(out, read, length - read);
            if (n < 0) return Arrays.copyOf(out, read);
            read += n;
        }
        return out;
    }
}
