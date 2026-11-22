package dna.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lê a lista de padrões (um por linha; linhas vazias e começadas por '#' ou '>' são ignoradas). */
public final class PatternReader {

    private PatternReader() {}

    public static byte[][] read(String path) throws IOException {
        List<String> lines = Files.readAllLines(Path.of(path), StandardCharsets.US_ASCII);
        return parse(lines);
    }

    public static byte[][] parse(List<String> lines) {
        List<byte[]> out = new ArrayList<>();
        for (String l : lines) {
            String s = l.trim().toUpperCase();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith(">")) continue;
            out.add(s.getBytes(StandardCharsets.US_ASCII));
        }
        return out.toArray(new byte[0][]);
    }

    public static int maxLength(byte[][] patterns) {
        int m = 0;
        for (byte[] p : patterns) m = Math.max(m, p.length);
        return m;
    }
}
