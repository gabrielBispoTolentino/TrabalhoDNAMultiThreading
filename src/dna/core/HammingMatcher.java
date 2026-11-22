package dna.core;

/**
 * Núcleo da busca: conta as divergências (distância de Hamming) entre cada padrão
 * e cada posição inicial de um intervalo da sequência.
 *
 * Duas implementações com o MESMO resultado:
 *  - {@link #scanSimple}: comparação letra a letra com saída antecipada (a versão "óbvia",
 *    é também o que o kernel da GPU faz).
 *  - {@link #scan}: versão bit-paralela usada no CPU. Cada base é codificada em 2 bits
 *    (A=00 C=01 G=10 T=11) e mantém-se uma janela deslizante de 32 bases num long.
 *    Para um padrão codificado da mesma forma:
 *        x = janela XOR padrão          -> bits != 0 onde as bases diferem
 *        y = (x | x>>>1) & 0x5555...    -> 1 bit por base diferente
 *        distância = Long.bitCount(y & máscaraDoTamanho)
 *    Ou seja, a distância de Hamming de até 32 bases em ~6 instruções sem ramos.
 *    Letras que não são ACGT (ex.: N) contam sempre como divergência.
 *    Padrões com mais de 32 letras ou com letras fora de ACGT usam scanSimple.
 *
 * Em ambas, as posições iniciais procuradas são [fromStart, toStart), mas a leitura
 * pode ir até toStart + tamanho - 1: é isso que garante que uma ocorrência que cruza
 * a fronteira de um bloco é encontrada pelo bloco onde COMEÇA (e só por esse).
 */
public final class HammingMatcher {

    private static final long EVEN_BITS = 0x5555555555555555L;
    private static final int MAX_PACKED = 32;

    private HammingMatcher() {}

    /** Código de 2 bits de uma base, ou -1 se não for A/C/G/T. */
    static int code(byte b) {
        switch (b) {
            case 'A': return 0;
            case 'C': return 1;
            case 'G': return 2;
            case 'T': return 3;
            default: return -1;
        }
    }

    /** Procura UM padrão. */
    public static void scan(byte[] seq, int seqLen, int fromStart, int toStart,
                            byte[] pattern, int maxMismatches, long baseOffset, MatchList out) {
        scan(seq, seqLen, fromStart, toStart, new byte[][]{pattern}, maxMismatches, baseOffset, new MatchList[]{out});
    }

    /**
     * Procura VÁRIOS padrões numa única passagem pela sequência.
     *
     * @param seqLen     número de bytes válidos em seq
     * @param baseOffset somado à posição local para obter a posição global
     * @param out        out[j] recebe as ocorrências de patterns[j] (ordenadas por posição)
     */
    public static void scan(byte[] seq, int seqLen, int fromStart, int toStart,
                            byte[][] patterns, int maxMismatches, long baseOffset, MatchList[] out) {
        int np = patterns.length;
        int[] idx = new int[np];
        int[] len = new int[np];
        long[] pc = new long[np];
        long[] mask = new long[np];
        int k = 0, maxLen = 0;
        for (int j = 0; j < np; j++) {
            byte[] p = patterns[j];
            long c = packOrMinus(p);
            if (p.length == 0 || p.length > MAX_PACKED || c == Long.MIN_VALUE) {
                scanSimple(seq, seqLen, fromStart, toStart, p, maxMismatches, baseOffset, out[j]);
                continue;
            }
            idx[k] = j;
            len[k] = p.length;
            pc[k] = c;
            mask[k] = p.length == 32 ? EVEN_BITS : EVEN_BITS & ((1L << (2 * p.length)) - 1);
            maxLen = Math.max(maxLen, p.length);
            k++;
        }
        if (k == 0) return;

        // Processa em segmentos de SEGMENT posições: cada chamada a scanPacked é curta,
        // o que deixa o JIT (C2) compilar o ciclo interno normalmente.
        for (int s = fromStart; s < toStart; s += SEGMENT) {
            int e = (int) Math.min(toStart, (long) s + SEGMENT);
            scanPacked(seq, seqLen, s, e, k, idx, len, pc, mask, maxLen, maxMismatches, baseOffset, out);
        }
    }

    private static final int SEGMENT = 1 << 16;

    private static void scanPacked(byte[] seq, int seqLen, int fromStart, int toStart, int k,
                                   int[] idx, int[] len, long[] pc, long[] mask, int maxLen,
                                   int maxMismatches, long baseOffset, MatchList[] out) {
        final int end = (int) Math.min(seqLen, (long) toStart + maxLen - 1);
        long w = 0;   // janela: base mais recente nos 2 bits menos significativos
        long nm = 0;  // marca (bit par) das posições da janela que não são ACGT
        for (int i = fromStart; i < end; i++) {
            int c = code(seq[i]);
            w = (w << 2) | (c & 3);
            nm = (nm << 2) | (c < 0 ? 1 : 0);
            for (int j = 0; j < k; j++) {
                int s = i - len[j] + 1;              // posição inicial da janela deste padrão
                if (s < fromStart || s >= toStart) continue;
                long x = w ^ pc[j];
                int d = Long.bitCount(((x | (x >>> 1)) | nm) & mask[j]);
                if (d <= maxMismatches) out[idx[j]].add(baseOffset + s, d);
            }
        }
    }

    /** Empacota o padrão em 2 bits/base (primeira letra nos bits mais altos); MIN_VALUE se tiver letras fora de ACGT. */
    private static long packOrMinus(byte[] p) {
        if (p.length > MAX_PACKED) return Long.MIN_VALUE;
        long v = 0;
        for (byte b : p) {
            int c = code(b);
            if (c < 0) return Long.MIN_VALUE;
            v = (v << 2) | c;
        }
        return v;
    }

    /** Versão direta, letra a letra, com saída antecipada quando passa o limite. */
    public static void scanSimple(byte[] seq, int seqLen, int fromStart, int toStart,
                                  byte[] pattern, int maxMismatches, long baseOffset, MatchList out) {
        final int len = pattern.length;
        final int last = Math.min(toStart, seqLen - len + 1);
        for (int i = fromStart; i < last; i++) {
            int mis = 0;
            for (int q = 0; q < len; q++) {
                if ((seq[i + q] != pattern[q] || code(seq[i + q]) < 0) && ++mis > maxMismatches) break;
            }
            if (mis <= maxMismatches) out.add(baseOffset + i, mis);
        }
    }

    /** Distância de Hamming direta (útil para testes). */
    public static int distance(byte[] seq, int pos, byte[] pattern) {
        int d = 0;
        for (int q = 0; q < pattern.length; q++) if (seq[pos + q] != pattern[q]) d++;
        return d;
    }
}
