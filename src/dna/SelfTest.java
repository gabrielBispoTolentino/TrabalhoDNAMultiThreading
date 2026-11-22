package dna;

import dna.core.*;
import dna.threads.BlockThreadSearch;
import dna.threads.PatternThreadSearch;

import java.util.SplittableRandom;

/** Testes rápidos: dados pequenos com N, padrões de 1..40 letras, muitas divisões em blocos. */
final class SelfTest {

    static void run() throws Exception {
        SplittableRandom rnd = new SplittableRandom(7);
        byte[] bases = {'A', 'C', 'G', 'T', 'N'};
        int failures = 0, cases = 0;
        for (int it = 0; it < 40; it++) {
            int n = 1 + rnd.nextInt(5000);
            byte[] seq = new byte[n];
            for (int i = 0; i < n; i++) seq[i] = bases[rnd.nextInt(rnd.nextInt(10) == 0 ? 5 : 4)];
            byte[][] pats = new byte[1 + rnd.nextInt(15)][];
            for (int p = 0; p < pats.length; p++) {
                int len = 1 + rnd.nextInt(40);           // inclui > 32 (caminho letra-a-letra)
                if (rnd.nextBoolean() && n >= len) {     // copiar da sequência para garantir ocorrências
                    int s = rnd.nextInt(n - len + 1);
                    pats[p] = java.util.Arrays.copyOfRange(seq, s, s + len);
                    for (int q = 0; q < len; q++) if (pats[p][q] == 'N') pats[p][q] = 'A';
                } else {
                    pats[p] = new byte[len];
                    for (int q = 0; q < len; q++) pats[p][q] = bases[rnd.nextInt(4)];
                }
            }
            int div = rnd.nextInt(4);

            // referência: força bruta letra-a-letra
            SearchResult ref = new SearchResult(pats);
            for (int p = 0; p < pats.length; p++)
                HammingMatcher.scanSimple(seq, n, 0, n, pats[p], div, 0, ref.matches[p]);

            cases++;
            failures += check("sequencial", ref, SequentialSearch.search(seq, pats, div));
            for (int t : new int[]{1, 2, 3, 7, 16, 64}) {
                cases += 2;
                failures += check("blocos " + t, ref, BlockThreadSearch.search(seq, pats, div, t));
                failures += check("padrões " + t, ref, PatternThreadSearch.search(seq, pats, div, t));
            }
        }
        System.out.printf("selftest: %d casos, %d falhas%n", cases, failures);
        if (failures > 0) System.exit(1);
    }

    private static int check(String name, SearchResult ref, SearchResult r) {
        String d = ref.diff(r);
        if (d == null) return 0;
        System.out.println("FALHA " + name + ": " + d);
        return 1;
    }
}
