package dna.net;

import dna.core.MatchList;
import dna.core.PatternReader;
import dna.core.SearchResult;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Cliente: envia o FASTA e os padrões ao servidor e recebe as ocorrências de cada padrão. */
public final class MotifClient {

    public static SearchResult request(String host, int port, String method, int threads, int maxMis,
                                       String fastaPath, String patternsPath) throws IOException {
        byte[][] patterns = PatternReader.read(patternsPath);
        long fastaSize = Files.size(Path.of(fastaPath));
        try (Socket s = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 20));
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 20))) {
            out.writeUTF(method);
            out.writeInt(threads);
            out.writeInt(maxMis);
            out.writeInt(patterns.length);
            for (byte[] p : patterns) out.writeUTF(new String(p, StandardCharsets.US_ASCII));
            out.writeLong(fastaSize);
            try (InputStream f = new FileInputStream(fastaPath)) {
                f.transferTo(out);
            }
            out.flush();
            System.out.printf("Enviados %,d bytes de FASTA e %d padrões; à espera do resultado...%n", fastaSize, patterns.length);

            String status = in.readUTF();
            if (!status.equals("OK")) throw new IOException("Servidor: " + status);
            String desc = in.readUTF();
            long nanos = in.readLong();
            int np = in.readInt();
            byte[][] pats = new byte[np][];
            MatchList[] lists = new MatchList[np];
            for (int p = 0; p < np; p++) {
                pats[p] = in.readUTF().getBytes(StandardCharsets.US_ASCII);
                int c = in.readInt();
                lists[p] = new MatchList();
                for (int i = 0; i < c; i++) {
                    long pos = in.readLong();
                    int mis = in.readByte();
                    lists[p].add(pos, mis);
                }
            }
            SearchResult r = new SearchResult(pats);
            for (int p = 0; p < np; p++) r.matches[p].addAll(lists[p]);
            r.method = "servidor: " + desc;
            r.elapsedNanos = nanos;
            return r;
        }
    }
}
