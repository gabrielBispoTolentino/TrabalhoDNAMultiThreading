package dna.core;

/** Divisão de [0, n) em k blocos contíguos de tamanho quase igual (usada por threads, MPJ e gerador). */
public final class Blocks {

    private Blocks() {}

    public static int start(int block, int n, int k) {
        return (int) ((long) block * n / k);
    }

    public static int end(int block, int n, int k) {
        return start(block + 1, n, k);
    }

    /**
     * Conta, para k blocos, quantas ocorrências do resultado cruzam uma fronteira
     * (começam num bloco e terminam no seguinte). Serve para mostrar que estas
     * ocorrências também foram encontradas.
     */
    public static long countCrossing(SearchResult r, int n, int k) {
        long count = 0;
        for (int p = 0; p < r.patterns.length; p++) {
            int len = r.patterns[p].length;
            MatchList m = r.matches[p];
            for (int i = 0; i < m.size(); i++) {
                long pos = MatchList.position(m.get(i));
                for (int b = 1; b < k; b++) {
                    int s = start(b, n, k);
                    if (pos < s && pos + len > s) { count++; break; }
                }
            }
        }
        return count;
    }
}
