package dna.core;

/** Versão sequencial: a referência para validar todas as outras. */
public final class SequentialSearch {

    private SequentialSearch() {}

    public static SearchResult search(byte[] seq, byte[][] patterns, int maxMismatches) {
        long t0 = System.nanoTime();
        SearchResult r = new SearchResult(patterns);
        HammingMatcher.scan(seq, seq.length, 0, seq.length, patterns, maxMismatches, 0, r.matches);
        r.elapsedNanos = System.nanoTime() - t0;
        r.method = "sequencial";
        return r;
    }
}
