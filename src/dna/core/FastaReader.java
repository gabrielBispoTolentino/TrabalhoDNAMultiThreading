package dna.core;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Lê um ficheiro FASTA para um único byte[] (apenas as letras da sequência,
 * em maiúsculas; letras fora de ACGT passam a 'N'). Linhas de cabeçalho ('>') e comentários (';') são ignoradas.
 * Se o ficheiro tiver vários registos, as sequências são concatenadas.
 */
public final class FastaReader {

    private FastaReader() {}

    public static byte[] read(String path) throws IOException {
        try (InputStream in = new FileInputStream(path)) {
            return read(in);
        }
    }

    public static byte[] read(InputStream raw) throws IOException {
        InputStream in = raw instanceof BufferedInputStream ? raw : new BufferedInputStream(raw, 1 << 20);
        byte[] seq = new byte[1 << 24];
        int len = 0;
        byte[] buf = new byte[1 << 20];
        boolean inHeader = false;
        boolean lineStart = true;
        int n;
        while ((n = in.read(buf)) > 0) {
            if (len + n > seq.length) {
                long newCap = Math.max((long) seq.length * 2, (long) len + n);
                if (newCap > Integer.MAX_VALUE - 8) newCap = Integer.MAX_VALUE - 8;
                if (len + n > newCap) throw new IOException("Sequência demasiado grande (> 2 GB)");
                seq = Arrays.copyOf(seq, (int) newCap);
            }
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                if (b == '\n' || b == '\r') {
                    inHeader = false;
                    lineStart = true;
                    continue;
                }
                if (lineStart && (b == '>' || b == ';')) {
                    inHeader = true;
                }
                lineStart = false;
                if (inHeader) continue;
                if (b >= 'a' && b <= 'z') b -= 32;          // maiúsculas
                if (b >= 'A' && b <= 'Z') {                 // ignora espaços/dígitos
                    // tudo o que não é A/C/G/T (N, R, Y, ...) passa a 'N' = divergência garantida
                    seq[len++] = (b == 'A' || b == 'C' || b == 'G' || b == 'T') ? b : (byte) 'N';
                }
            }
        }
        return len == seq.length ? seq : Arrays.copyOf(seq, len);
    }
}
