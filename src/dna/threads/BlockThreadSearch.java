package dna.threads;

import dna.core.Blocks;
import dna.core.HammingMatcher;
import dna.core.MatchList;
import dna.core.SearchResult;

/**
 * Estratégia 1 — divisão da SEQUÊNCIA em blocos.
 * Cada thread recebe um intervalo de posições iniciais [início, fim) e procura
 * TODOS os padrões nesse intervalo. Como a memória é partilhada, a thread pode
 * ler até (fim + tamanhoPadrão - 1): as ocorrências que cruzam a fronteira são
 * encontradas pela thread do bloco onde começam, e nunca duplicadas.
 * Cada thread escreve em listas próprias (sem sincronização); no fim juntam-se
 * pela ordem dos blocos, o que já dá posições ordenadas.
 */
public final class BlockThreadSearch {

    private BlockThreadSearch() {}

    public static SearchResult search(byte[] seq, byte[][] patterns, int maxMismatches, int numThreads)
            throws InterruptedException {
        long t0 = System.nanoTime();
        int n = seq.length;
        MatchList[][] partial = new MatchList[numThreads][patterns.length];
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final int id = t;
            final int from = Blocks.start(t, n, numThreads);
            final int to = Blocks.end(t, n, numThreads);
            threads[t] = new Thread(() -> {
                MatchList[] local = new MatchList[patterns.length];
                for (int p = 0; p < patterns.length; p++) local[p] = new MatchList();
                // uma passagem pelo bloco para todos os padrões
                HammingMatcher.scan(seq, n, from, to, patterns, maxMismatches, 0, local);
                partial[id] = local;
            }, "bloco-" + t);
            threads[t].start();
        }
        for (Thread th : threads) th.join();

        SearchResult r = new SearchResult(patterns);
        for (int p = 0; p < patterns.length; p++)
            for (int t = 0; t < numThreads; t++) r.matches[p].addAll(partial[t][p]);

        r.elapsedNanos = System.nanoTime() - t0;
        r.method = "threads/blocos (" + numThreads + ")";
        return r;
    }
}
