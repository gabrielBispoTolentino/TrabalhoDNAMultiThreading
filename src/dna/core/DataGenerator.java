package dna.core;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/**
 * Gera uma sequência aleatória em FASTA e uma lista de padrões.
 * Para tornar o teste interessante, insere cópias (com 0, 1 ou 2 mutações) dos
 * padrões em posições aleatórias e, de propósito, A CRUZAR as fronteiras dos
 * blocos para 2..32 divisões — assim fica provado que nenhuma divisão as perde.
 */
public final class DataGenerator {

    private static final byte[] BASES = {'A', 'C', 'G', 'T'};

    private DataGenerator() {}

    public static void generate(String fastaPath, String patternsPath, long sizeBytes,
                                int numPatterns, int minLen, int maxLen, long seed) throws IOException {
        if (sizeBytes > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("tamanho máximo ~2 GB");
        int n = (int) sizeBytes;
        SplittableRandom rnd = new SplittableRandom(seed);

        byte[][] patterns = new byte[numPatterns][];
        for (int i = 0; i < numPatterns; i++) {
            int len = minLen + rnd.nextInt(maxLen - minLen + 1);
            patterns[i] = new byte[len];
            for (int k = 0; k < len; k++) patterns[i][k] = BASES[rnd.nextInt(4)];
        }

        System.out.printf("A gerar %,d bases...%n", n);
        byte[] seq = new byte[n];
        for (int i = 0; i < n; i++) seq[i] = BASES[rnd.nextInt(4)];

        // 1) implantes aleatórios
        int implants = Math.max(numPatterns * 20, n / 100_000);
        for (int j = 0; j < implants; j++) {
            byte[] p = patterns[rnd.nextInt(numPatterns)];
            int pos = rnd.nextInt(n - p.length);
            implant(seq, pos, p, rnd);
        }
        // 2) implantes a cruzar as fronteiras de blocos para k = 2..32
        int boundaryImplants = 0;
        for (int k = 2; k <= 32; k++) {
            for (int b = 1; b < k; b++) {
                byte[] p = patterns[rnd.nextInt(numPatterns)];
                int s = Blocks.start(b, n, k);
                int pos = s - 1 - rnd.nextInt(p.length - 1); // começa antes, termina depois da fronteira
                if (pos >= 0 && pos + p.length <= n) {
                    implant(seq, pos, p, rnd);
                    boundaryImplants++;
                }
            }
        }
        System.out.printf("Implantes: %,d aleatórios + %,d a cruzar fronteiras de blocos%n", implants, boundaryImplants);

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(fastaPath), 1 << 20)) {
            out.write((">synthetic_chr seed=" + seed + " length=" + n + "\n").getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < n; i += 80) {
                out.write(seq, i, Math.min(80, n - i));
                out.write('\n');
            }
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(patternsPath))) {
            for (byte[] p : patterns) {
                out.write(p);
                out.write('\n');
            }
        }
        System.out.println("Escrito: " + fastaPath + " e " + patternsPath);
    }

    private static void implant(byte[] seq, int pos, byte[] p, SplittableRandom rnd) {
        System.arraycopy(p, 0, seq, pos, p.length);
        int mutations = rnd.nextInt(3); // 0, 1 ou 2
        for (int m = 0; m < mutations; m++) {
            int k = pos + rnd.nextInt(p.length);
            byte b;
            do { b = BASES[rnd.nextInt(4)]; } while (b == seq[k]);
            seq[k] = b;
        }
    }
}
