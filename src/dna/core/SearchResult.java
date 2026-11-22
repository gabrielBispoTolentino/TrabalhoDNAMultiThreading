package dna.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resultado de uma busca: para cada padrão, as posições encontradas e as divergências. */
public final class SearchResult {

    public final byte[][] patterns;
    public final MatchList[] matches;
    public long elapsedNanos;
    public String method = "";

    public SearchResult(byte[][] patterns) {
        this.patterns = patterns;
        this.matches = new MatchList[patterns.length];
        for (int i = 0; i < patterns.length; i++) matches[i] = new MatchList();
    }

    public long totalMatches() {
        long t = 0;
        for (MatchList m : matches) t += m.size();
        return t;
    }

    public void sortAll() {
        for (MatchList m : matches) m.sort();
    }

    /** Compara com outro resultado; devolve null se forem idênticos, ou a descrição da primeira diferença. */
    public String diff(SearchResult o) {
        if (o.matches.length != matches.length) return "número de padrões diferente";
        for (int p = 0; p < matches.length; p++) {
            MatchList a = matches[p], b = o.matches[p];
            if (a.sameAs(b)) continue;
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                if (a.get(i) != b.get(i)) {
                    return String.format("padrão #%d (%s): índice %d -> pos %d/%d vs pos %d/%d",
                            p, new String(patterns[p], StandardCharsets.US_ASCII), i,
                            MatchList.position(a.get(i)), MatchList.mismatches(a.get(i)),
                            MatchList.position(b.get(i)), MatchList.mismatches(b.get(i)));
                }
            }
            return String.format("padrão #%d (%s): %d ocorrências vs %d",
                    p, new String(patterns[p], StandardCharsets.US_ASCII), a.size(), b.size());
        }
        return null;
    }

    /**
     * Formato de saída (texto):
     * <pre>
     * # pattern ACGTACGTACGT count 3
     * 1234 0
     * 99812 2
     * ...
     * </pre>
     */
    public void write(Path path) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.US_ASCII)) {
            StringBuilder sb = new StringBuilder(64);
            for (int p = 0; p < patterns.length; p++) {
                MatchList m = matches[p];
                w.write("# pattern " + new String(patterns[p], StandardCharsets.US_ASCII)
                        + " count " + m.size() + "\n");
                for (int i = 0; i < m.size(); i++) {
                    long c = m.get(i);
                    sb.setLength(0);
                    sb.append(MatchList.position(c)).append(' ').append(MatchList.mismatches(c)).append('\n');
                    w.write(sb.toString());
                }
            }
        }
    }

    public void printSummary(PrintStream out, int maxPatternsShown) {
        out.printf("%s: %d ocorrências em %.3f s%n", method, totalMatches(), elapsedNanos / 1e9);
        for (int p = 0; p < patterns.length && p < maxPatternsShown; p++) {
            MatchList m = matches[p];
            int[] byMis = new int[3 + 1];
            for (int i = 0; i < m.size(); i++) byMis[MatchList.mismatches(m.get(i))]++;
            out.printf("  %-20s total=%-8d (0 div=%d, 1 div=%d, 2 div=%d)%n",
                    new String(patterns[p], StandardCharsets.US_ASCII), m.size(), byMis[0], byMis[1], byMis[2]);
        }
        if (patterns.length > maxPatternsShown) out.printf("  ... (+%d padrões)%n", patterns.length - maxPatternsShown);
    }
}
