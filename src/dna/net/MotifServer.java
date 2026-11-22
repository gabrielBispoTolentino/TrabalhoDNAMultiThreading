package dna.net;

import dna.Methods;
import dna.core.FastaReader;
import dna.core.MatchList;
import dna.core.SearchResult;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Serviço de busca de motivos. Cada cliente é atendido numa thread própria.
 *
 * Protocolo (DataInput/DataOutput, big-endian):
 * <pre>
 * cliente -> servidor:
 *   UTF método ("seq" | "blocks" | "patterns" | "gpu")
 *   int threads, int maxDivergencias
 *   int nPadrões, nPadrões x UTF padrão
 *   long tamanhoFasta, tamanhoFasta bytes (ficheiro FASTA tal como está no disco)
 * servidor -> cliente:
 *   UTF "OK" | "ERR mensagem"
 *   UTF descrição do método, long nanossegundos, int nPadrões
 *   por padrão: UTF padrão, int nOcorrências, nOcorrências x (long posição, byte divergências)
 * </pre>
 */
public final class MotifServer {

    public static void serve(int port) throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Servidor de busca de motivos à escuta na porta " + port);
            while (true) {
                Socket s = ss.accept();
                Thread t = new Thread(() -> handle(s), "cliente-" + s.getRemoteSocketAddress());
                t.start();
            }
        }
    }

    private static void handle(Socket s) {
        String who = String.valueOf(s.getRemoteSocketAddress());
        try (s;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 20));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 20))) {
            String method = in.readUTF();
            int threads = in.readInt();
            int maxMis = in.readInt();
            int np = in.readInt();
            byte[][] patterns = new byte[np][];
            for (int i = 0; i < np; i++) patterns[i] = in.readUTF().trim().toUpperCase().getBytes(StandardCharsets.US_ASCII);
            long fastaSize = in.readLong();
            byte[] seq = FastaReader.read(new LimitedInputStream(in, fastaSize));
            System.out.printf("[%s] método=%s threads=%d %,d bases, %d padrões%n", who, method, threads, seq.length, np);

            SearchResult r;
            try {
                r = Methods.run(method, seq, patterns, maxMis, threads);
            } catch (Exception e) {
                out.writeUTF("ERR " + e.getMessage());
                return;
            }
            out.writeUTF("OK");
            out.writeUTF(r.method);
            out.writeLong(r.elapsedNanos);
            out.writeInt(np);
            for (int p = 0; p < np; p++) {
                out.writeUTF(new String(patterns[p], StandardCharsets.US_ASCII));
                MatchList m = r.matches[p];
                out.writeInt(m.size());
                for (int i = 0; i < m.size(); i++) {
                    out.writeLong(MatchList.position(m.get(i)));
                    out.writeByte(MatchList.mismatches(m.get(i)));
                }
            }
            out.flush();
            System.out.printf("[%s] %d ocorrências enviadas (%.2f s de busca)%n", who, r.totalMatches(), r.elapsedNanos / 1e9);
        } catch (IOException e) {
            System.err.println("[" + who + "] erro: " + e.getMessage());
        }
    }

    /** Lê no máximo {@code remaining} bytes do stream subjacente (sem o fechar). */
    static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() { /* não fecha o socket */ }
    }
}
