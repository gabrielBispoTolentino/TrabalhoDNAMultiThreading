package dna.mpj;

import dna.core.Blocks;
import dna.core.FastaReader;
import dna.core.HammingMatcher;
import dna.core.MatchList;
import dna.core.PatternReader;
import dna.core.SearchResult;
import dna.core.SequentialSearch;
import mpi.MPI;

import java.nio.file.Path;

/**
 * Versão MPJ Express (memória distribuída).
 *
 * 1. O processo 0 lê o FASTA e os padrões.
 * 2. Os padrões são difundidos (Bcast) para todos.
 * 3. A sequência é dividida em P trechos. O processo r é "dono" das posições
 *    iniciais [s_r, e_r), mas recebe os bytes [s_r, e_r + maxLen - 1): os trechos
 *    SOBREPÕEM-SE pelo tamanho do maior padrão (menos 1), para que uma ocorrência
 *    que começa no fim do trecho r e termina no trecho r+1 seja vista por r.
 *    Como cada processo só reporta posições que começam no seu intervalo, não há duplicados.
 * 4. Cada processo procura todos os padrões no seu trecho e devolve ao processo 0
 *    (contagens por padrão + lista de ocorrências codificadas em long).
 * 5. O processo 0 junta tudo pela ordem dos processos (posições já ficam ordenadas).
 *
 * Execução (ver README):
 *   mpjrun.sh -np 4 -cp build/classes dna.mpj.MpjSearch dados.fa padroes.txt [maxDiv] [saida.txt] [--verify]
 */
public final class MpjSearch {

    private static final int TAG_SEQ = 1, TAG_COUNTS = 2, TAG_MATCHES = 3;

    public static void main(String[] rawArgs) throws Exception {
        String[] args = MPI.Init(rawArgs);
        int rank = MPI.COMM_WORLD.Rank();
        int size = MPI.COMM_WORLD.Size();

        if (args.length < 2) {
            if (rank == 0) System.err.println("uso: MpjSearch <fasta> <padroes> [maxDiv=2] [saida] [--verify]");
            MPI.Finalize();
            return;
        }
        boolean verify = false;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (String a : args) { if (a.equals("--verify")) verify = true; else pos.add(a); }
        String fastaPath = pos.get(0), patPath = pos.get(1);
        int maxMis = pos.size() > 2 ? Integer.parseInt(pos.get(2)) : 2;
        String outPath = pos.size() > 3 ? pos.get(3) : null;

        // ---------- 1. leitura no processo 0 ----------
        byte[] seq = null;
        byte[][] patterns = null;
        int[] header = new int[3]; // [seqLen, numPatterns, totalPatternBytes]
        byte[] packed = null;
        long tRead = System.nanoTime();
        if (rank == 0) {
            seq = FastaReader.read(fastaPath);
            patterns = PatternReader.read(patPath);
            int total = 0;
            for (byte[] p : patterns) total += p.length;
            header[0] = seq.length; header[1] = patterns.length; header[2] = total;
            System.out.printf("[0] sequência: %,d bases, %d padrões, %d processos (leitura %.2f s)%n",
                    seq.length, patterns.length, size, (System.nanoTime() - tRead) / 1e9);
        }
        long t0 = System.nanoTime();

        // ---------- 2. difusão dos padrões ----------
        MPI.COMM_WORLD.Bcast(header, 0, 3, MPI.INT, 0);
        int n = header[0], numPat = header[1];
        int[] lens = new int[numPat];
        packed = new byte[header[2]];
        if (rank == 0) {
            int off = 0;
            for (int i = 0; i < numPat; i++) {
                lens[i] = patterns[i].length;
                System.arraycopy(patterns[i], 0, packed, off, lens[i]);
                off += lens[i];
            }
        }
        MPI.COMM_WORLD.Bcast(lens, 0, numPat, MPI.INT, 0);
        MPI.COMM_WORLD.Bcast(packed, 0, packed.length, MPI.BYTE, 0);
        if (rank != 0) {
            patterns = new byte[numPat][];
            int off = 0;
            for (int i = 0; i < numPat; i++) {
                patterns[i] = java.util.Arrays.copyOfRange(packed, off, off + lens[i]);
                off += lens[i];
            }
        }
        int maxLen = PatternReader.maxLength(patterns);

        // ---------- 3. distribuição dos trechos sobrepostos ----------
        int ownStart = Blocks.start(rank, n, size);
        int ownEnd = Blocks.end(rank, n, size);
        byte[] chunk;
        int chunkLen;
        if (rank == 0) {
            for (int r = 1; r < size; r++) {
                int s = Blocks.start(r, n, size);
                int e = Math.min(n, Blocks.end(r, n, size) + maxLen - 1); // sobreposição
                MPI.COMM_WORLD.Send(seq, s, e - s, MPI.BYTE, r, TAG_SEQ);
            }
            chunk = seq;                  // o processo 0 usa a sequência original
            chunkLen = Math.min(n, ownEnd + maxLen - 1);
        } else {
            chunkLen = Math.min(n, ownEnd + maxLen - 1) - ownStart;
            chunk = new byte[chunkLen];
            MPI.COMM_WORLD.Recv(chunk, 0, chunkLen, MPI.BYTE, 0, TAG_SEQ);
        }

        // ---------- 4. busca local ----------
        long tSearch = System.nanoTime();
        // índice 0 do trecho local corresponde à posição global ownStart (no rank 0, ownStart = 0)
        int localFrom = 0;
        int localTo = ownEnd - ownStart;         // só posições iniciais de que este processo é dono
        long base = ownStart;                    // conversão local -> global
        MatchList[] local = new MatchList[numPat];
        for (int p = 0; p < numPat; p++) local[p] = new MatchList();
        HammingMatcher.scan(chunk, chunkLen, localFrom, localTo, patterns, maxMis, base, local);
        System.out.printf("[%d] posições [%,d, %,d) trecho=%,d bytes -> %,d ocorrências em %.2f s%n",
                rank, ownStart, ownEnd, chunkLen, sum(local), (System.nanoTime() - tSearch) / 1e9);

        // ---------- 5. recolha no processo 0 ----------
        if (rank != 0) {
            int[] counts = new int[numPat];
            int total = 0;
            for (int p = 0; p < numPat; p++) { counts[p] = local[p].size(); total += counts[p]; }
            long[] all = new long[total];
            int off = 0;
            for (int p = 0; p < numPat; p++) {
                long[] a = local[p].toArray();
                System.arraycopy(a, 0, all, off, a.length);
                off += a.length;
            }
            MPI.COMM_WORLD.Send(counts, 0, numPat, MPI.INT, 0, TAG_COUNTS);
            MPI.COMM_WORLD.Send(all, 0, total, MPI.LONG, 0, TAG_MATCHES);
        } else {
            SearchResult result = new SearchResult(patterns);
            for (int p = 0; p < numPat; p++) result.matches[p].addAll(local[p]);
            for (int r = 1; r < size; r++) {        // pela ordem dos processos -> posições ordenadas
                int[] counts = new int[numPat];
                MPI.COMM_WORLD.Recv(counts, 0, numPat, MPI.INT, r, TAG_COUNTS);
                int total = 0;
                for (int c : counts) total += c;
                long[] all = new long[total];
                MPI.COMM_WORLD.Recv(all, 0, total, MPI.LONG, r, TAG_MATCHES);
                int off = 0;
                for (int p = 0; p < numPat; p++) {
                    result.matches[p].addAll(all, off, counts[p]);
                    off += counts[p];
                }
            }
            result.elapsedNanos = System.nanoTime() - t0;
            result.method = "MPJ (" + size + " processos)";
            result.printSummary(System.out, 10);
            System.out.printf("Ocorrências que cruzam fronteiras entre os %d trechos: %,d%n",
                    size, Blocks.countCrossing(result, n, size));
            if (outPath != null) {
                result.write(Path.of(outPath));
                System.out.println("Resultados escritos em " + outPath);
            }
            if (verify) {
                SearchResult ref = SequentialSearch.search(seq, patterns, maxMis);
                String d = ref.diff(result);
                System.out.printf("Verificação contra sequencial (%.2f s): %s%n", ref.elapsedNanos / 1e9,
                        d == null ? "IDÊNTICO OK" : "DIFERENTE (ERRO) " + d);
            }
        }
        MPI.Finalize();
    }

    private static long sum(MatchList[] l) {
        long s = 0;
        for (MatchList m : l) s += m.size();
        return s;
    }
}
