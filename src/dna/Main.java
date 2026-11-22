package dna;

import dna.core.*;
import dna.net.MotifClient;
import dna.net.MotifServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ponto de entrada (linha de comandos).
 *
 * <pre>
 *   generate &lt;saida.fa&gt; &lt;padroes.txt&gt; [--size-mb 500] [--patterns 100] [--min 12] [--max 20] [--seed 42]
 *   search   &lt;seq|blocks|patterns|gpu&gt; &lt;dados.fa&gt; &lt;padroes.txt&gt; [--threads N] [--div 2] [--out res.txt] [--verify]
 *   bench    &lt;dados.fa&gt; &lt;padroes.txt&gt; [--threads 1,2,4,8] [--div 2] [--gpu]
 *   server   [--port 5000]
 *   client   &lt;seq|blocks|patterns|gpu&gt; &lt;dados.fa&gt; &lt;padroes.txt&gt; [--host localhost] [--port 5000] [--threads N] [--div 2] [--out res.txt]
 * </pre>
 * A versão MPJ é um programa à parte: dna.mpj.MpjSearch (ver README / run-mpj.sh).
 */
public final class Main {

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) { usage(); return; }
        if (argv[0].equals("mpj")) {   // MPJ Express embutido: dna.mpj.MpjLauncher (só se compilado com MPJ)
            String[] rest = java.util.Arrays.copyOfRange(argv, 1, argv.length);
            try {
                Class.forName("dna.mpj.MpjLauncher").getMethod("main", String[].class).invoke(null, (Object) rest);
            } catch (ClassNotFoundException e) {
                System.err.println("Erro: suporte MPJ não incluído nesta compilação (defina MPJ_HOME ao compilar).");
                System.exit(1);
            }
            return;
        }
        List<String> pos = new ArrayList<>();
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                boolean flag = key.equals("verify") || key.equals("gpu");
                opt.put(key, flag ? "true" : argv[++i]);
            } else pos.add(a);
        }
        int needed = 0;
        if (argv[0].equals("generate") || argv[0].equals("bench")) needed = 2;
        if (argv[0].equals("search") || argv[0].equals("client")) needed = 3;
        if (pos.size() < needed) {
            System.err.printf("Erro: '%s' precisa de %d argumentos (recebeu %d).%n%n", argv[0], needed, pos.size());
            usage();
            System.exit(2);
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int div = Integer.parseInt(opt.getOrDefault("div", "2"));
        if (div < 0 || div > 3) throw new IllegalArgumentException("--div deve estar entre 0 e 3");

        switch (argv[0]) {
            case "generate":
                DataGenerator.generate(pos.get(0), pos.get(1),
                    Long.parseLong(opt.getOrDefault("size-mb", "500")) * 1_000_000L,
                    Integer.parseInt(opt.getOrDefault("patterns", "100")),
                    Integer.parseInt(opt.getOrDefault("min", "12")),
                    Integer.parseInt(opt.getOrDefault("max", "20")),
                    Long.parseLong(opt.getOrDefault("seed", "42")));
                break;

            case "search": {
                String method = pos.get(0);
                int threads = Integer.parseInt(opt.getOrDefault("threads", String.valueOf(cores)));
                Loaded d = load(pos.get(1), pos.get(2));
                SearchResult r;
                try {
                    r = Methods.run(method, d.seq, d.patterns, div, threads);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    System.err.println("Erro: " + e.getMessage());
                    System.exit(1);
                    return;
                }
                r.printSummary(System.out, 10);
                if (method.equals("blocks"))
                    System.out.printf("Ocorrências que cruzam fronteiras entre os %d blocos: %,d%n",
                            threads, Blocks.countCrossing(r, d.seq.length, threads));
                if (opt.containsKey("out")) { r.write(Path.of(opt.get("out"))); System.out.println("Escrito: " + opt.get("out")); }
                if (opt.containsKey("verify") && !method.equals("seq")) verify(d, r, div);
                break;
            }

            case "bench": {
                Loaded d = load(pos.get(0), pos.get(1));
                String[] tlist = opt.getOrDefault("threads", "1,2,4," + cores).split(",");
                // aquecimento do JIT num pedaço pequeno, para os tempos serem comparáveis
                byte[] warm = java.util.Arrays.copyOf(d.seq, Math.min(d.seq.length, 2_000_000));
                for (int i = 0; i < 3; i++) { SequentialSearch.search(warm, d.patterns, div); Methods.run("blocks", warm, d.patterns, div, cores); }
                System.out.println("\n== referência sequencial ==");
                SearchResult ref = SequentialSearch.search(d.seq, d.patterns, div);
                ref.printSummary(System.out, 5);
                double tSeq = ref.elapsedNanos / 1e9;

                List<String[]> rows = new ArrayList<>();
                rows.add(new String[]{"sequencial", fmt(tSeq), String.format("%.2f", 1.0), "-", "referência"});
                for (String ts : tlist) {
                    int t = Integer.parseInt(ts.trim());
                    for (String m : new String[]{"blocks", "patterns"}) {
                        System.gc();
                        SearchResult r = Methods.run(m, d.seq, d.patterns, div, t);
                        String crossing = m.equals("blocks") ? String.format("%,d", Blocks.countCrossing(r, d.seq.length, t)) : "-";
                        rows.add(row(r, tSeq, ref, crossing));
                    }
                }
                if (opt.containsKey("gpu")) {
                    try {
                        rows.add(row(Methods.run("gpu", d.seq, d.patterns, div, 0), tSeq, ref, "-"));
                    } catch (Exception e) {
                        System.out.println("GPU indisponível: " + e.getMessage());
                    }
                }
                System.out.printf("%n%-28s %10s %8s %12s  %s%n", "método", "tempo (s)", "speedup", "cruzam fronteira", "resultado vs sequencial");
                for (String[] rw : rows) System.out.printf("%-28s %10s %8s %16s  %s%n", (Object[]) rw);
                break;
            }

            case "selftest":
                SelfTest.run();
                break;

            case "server":
                MotifServer.serve(Integer.parseInt(opt.getOrDefault("port", "5000")));
                break;

            case "client": {
                int threads = Integer.parseInt(opt.getOrDefault("threads", String.valueOf(cores)));
                SearchResult r = MotifClient.request(opt.getOrDefault("host", "localhost"),
                        Integer.parseInt(opt.getOrDefault("port", "5000")), pos.get(0), threads, div, pos.get(1), pos.get(2));
                r.printSummary(System.out, 10);
                String outPath = opt.getOrDefault("out", "resultado.txt");
                r.write(Path.of(outPath));
                System.out.println("Resultados completos em " + outPath);
                break;
            }

            default:
                usage();
        }
    }

    private static final class Loaded {
        final byte[] seq;
        final byte[][] patterns;

        Loaded(byte[] seq, byte[][] patterns) { this.seq = seq; this.patterns = patterns; }
    }

    private static Loaded load(String fasta, String pats) throws Exception {
        long t = System.nanoTime();
        byte[] seq = FastaReader.read(fasta);
        byte[][] p = PatternReader.read(pats);
        System.out.printf("Sequência: %,d bases | %d padrões (%d-%d letras) | leitura %.2f s%n", seq.length, p.length,
                minLen(p), PatternReader.maxLength(p), (System.nanoTime() - t) / 1e9);
        return new Loaded(seq, p);
    }

    private static void verify(Loaded d, SearchResult r, int div) {
        SearchResult ref = SequentialSearch.search(d.seq, d.patterns, div);
        String diff = ref.diff(r);
        System.out.printf("Verificação contra sequencial (%.2f s, speedup %.2fx): %s%n", ref.elapsedNanos / 1e9,
                (double) ref.elapsedNanos / r.elapsedNanos, diff == null ? "IDÊNTICO OK" : "DIFERENTE (ERRO) " + diff);
    }

    private static String[] row(SearchResult r, double tSeq, SearchResult ref, String crossing) {
        double t = r.elapsedNanos / 1e9;
        String diff = ref.diff(r);
        return new String[]{r.method, fmt(t), String.format("%.2f", tSeq / t), crossing,
                diff == null ? "IDÊNTICO OK (" + r.totalMatches() + ")" : "DIFERENTE (ERRO) " + diff};
    }

    private static String fmt(double s) { return String.format("%.3f", s); }

    private static int minLen(byte[][] p) {
        int m = Integer.MAX_VALUE;
        for (byte[] x : p) m = Math.min(m, x.length);
        return p.length == 0 ? 0 : m;
    }

    private static void usage() {
        String[] lines = {
                "Busca de motivos em DNA (até N divergências, distância de Hamming)",
                "",
                "  generate <saida.fa> <padroes.txt> [--size-mb 500] [--patterns 100] [--min 12] [--max 20] [--seed 42]",
                "  search   <seq|blocks|patterns|gpu> <dados.fa> <padroes.txt> [--threads N] [--div 2] [--out res.txt] [--verify]",
                "  bench    <dados.fa> <padroes.txt> [--threads 1,2,4,8] [--div 2] [--gpu]",
                "  selftest (testes rápidos de correção: bit-paralelo vs letra-a-letra, várias divisões em blocos)",
                "  server   [--port 5000]",
                "  client   <seq|blocks|patterns|gpu> <dados.fa> <padroes.txt> [--host localhost] [--port 5000] [--threads N] [--out res.txt]",
                "",
                "  mpj      <nProcessos> <dados.fa> <padroes.txt> [div] [saida.txt] [--verify]"
        };
        for (String l : lines) System.out.println(l);
    }
}
