package dna.threads;

import dna.core.HammingMatcher;
import dna.core.MatchList;
import dna.core.SearchResult;

/**
 * Estratégia 2 — divisão por PADRÕES.
 * Cada thread percorre a sequência INTEIRA, mas só para o seu subconjunto de padrões
 * (distribuição round-robin: thread t fica com os padrões t, t+T, t+2T, ...).
 * Não há fronteiras a tratar: cada padrão é procurado por uma só thread sobre a sequência toda.
 * Cada padrão tem a sua própria MatchList, por isso não há escrita partilhada.
 *
 * Limitação (a discutir no relatório): com T threads há T passagens completas pela
 * sequência e, se T > nº de padrões, sobram threads sem trabalho.
 */
public final class PatternThreadSearch {

    private PatternThreadSearch() {}

    public static SearchResult search(byte[] seq, byte[][] patterns, int maxMismatches, int numThreads)
            throws InterruptedException {
        long t0 = System.nanoTime();
        SearchResult r = new SearchResult(patterns);
        int used = Math.max(1, Math.min(numThreads, patterns.length));
        Thread[] threads = new Thread[used];

        for (int t = 0; t < used; t++) {
            final int id = t;
            threads[t] = new Thread(() -> {
                int count = 0;
                for (int p = id; p < patterns.length; p += used) count++;
                byte[][] mine = new byte[count][];
                MatchList[] outs = new MatchList[count];
                int c = 0;
                for (int p = id; p < patterns.length; p += used) {
                    mine[c] = patterns[p];
                    outs[c] = r.matches[p];
                    c++;
                }
                HammingMatcher.scan(seq, seq.length, 0, seq.length, mine, maxMismatches, 0, outs);
            }, "padroes-" + t);
            threads[t].start();
        }
        for (Thread th : threads) th.join();

        r.elapsedNanos = System.nanoTime() - t0;
        r.method = "threads/padrões (" + numThreads + ")";
        return r;
    }
}
