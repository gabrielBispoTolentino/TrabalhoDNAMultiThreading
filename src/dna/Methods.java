package dna;

import dna.core.SearchResult;
import dna.core.SequentialSearch;
import dna.threads.BlockThreadSearch;
import dna.threads.PatternThreadSearch;

/** Seleciona a implementação pelo nome. A GPU é carregada por reflexão (só precisa do JOCL se for usada). */
public final class Methods {

    public static final String[] NAMES = {"seq", "blocks", "patterns", "gpu"};

    private Methods() {}

    public static SearchResult run(String method, byte[] seq, byte[][] patterns, int maxMis, int threads)
            throws Exception {
        switch (method) {
            case "seq":
                return SequentialSearch.search(seq, patterns, maxMis);
            case "blocks":
                return BlockThreadSearch.search(seq, patterns, maxMis, threads);
            case "patterns":
                return PatternThreadSearch.search(seq, patterns, maxMis, threads);
            case "gpu":
                try {
                    Class<?> c = Class.forName("dna.gpu.GpuSearch");
                    return (SearchResult) c.getMethod("search", byte[].class, byte[][].class, int.class)
                            .invoke(null, seq, patterns, maxMis);
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    throw new IllegalStateException("Suporte GPU não compilado (falta lib/jocl-*.jar)", e);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new RuntimeException(cause);
                }
            default:
                throw new IllegalArgumentException("método desconhecido: " + method
                        + " (use seq | blocks | patterns | gpu; MPJ: comando mpj)");
        }
    }
}
