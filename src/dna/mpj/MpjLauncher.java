package dna.mpj;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Executa o MpjSearch com o MPJ Express EMBUTIDO no jar (modo multicore), sem instalar o MPJ
 * nem usar o mpjrun:
 *
 *   java -jar TrabalhoDNA.jar mpj 4 dados.fa padroes.txt [div] [saida.txt] [--verify]
 *
 * Faz o mesmo que o runtime.starter.MulticoreStarter do MPJ Express 0.44:
 *  - cada processo MPI é uma thread dentro de um ThreadGroup xdev.smpdev.SMPDevProcess
 *    (é assim que o dispositivo smpdev sabe o rank de quem chama);
 *  - cada processo tem o seu próprio ClassLoader para as classes mpi.* / mpjdev.* / dna.*,
 *    porque o MPI guarda estado em campos estáticos (ex.: MPI.COMM_WORLD);
 *  - o dispositivo (xdev.*, mpjbuf.*) e as classes de mpiExp.jar são partilhados por todos.
 *  - argumentos do main de cada processo: [rank, nProcessos, "smpdev", args da aplicação...]
 *
 * O MPI.Init lê $MPJ_HOME/conf/mpjexpress.conf. Se MPJ_HOME não existir, o launcher extrai esse
 * ficheiro (que vem dentro do jar) para uma pasta temporária e relança a JVM com MPJ_HOME definido.
 */
public final class MpjLauncher {

    /** Classes do mpiExp.jar: no MPJ original estão no classpath do sistema, partilhadas por todos os processos. */
    private static final Set<String> SHARED = Set.of(
            "mpi.MPIException", "mpjdev.CompletionHandler", "mpjdev.Constants", "mpjdev.MPJDevException",
            "mpjdev.Request", "mpjdev.Request$WaitanyQue", "mpjdev.Status");

    private static final String APP_CLASS = "dna.mpj.MpjSearch";

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("uso: mpj <nProcessos> <dados.fa> <padroes.txt> [div=2] [saida.txt] [--verify]");
            System.exit(2);
        }
        int np = Integer.parseInt(args[0]);
        String[] appArgs = Arrays.copyOfRange(args, 1, args.length);

        String mpjHome = System.getenv("MPJ_HOME");
        if (mpjHome == null || !new File(mpjHome, "conf/mpjexpress.conf").isFile()) {
            System.exit(relaunchWithMpjHome(args));
        }

        System.out.println("MPJ Express (embutido) em modo multicore com " + np + " processos");
        Class<?> groupClass = Class.forName("xdev.smpdev.SMPDevProcess");
        ClassLoader parent = MpjLauncher.class.getClassLoader();
        Object lock = new Object();
        Thread[] threads = new Thread[np];
        Throwable[] errors = new Throwable[np];

        for (int r = 0; r < np; r++) {
            final int rank = r;
            ThreadGroup group = (ThreadGroup) groupClass.getConstructor(String.class).newInstance("MPI" + r);
            threads[r] = new Thread(group, () -> {
                try {
                    Method main;
                    synchronized (lock) { // como no MulticoreStarter: carregar as classes uma de cada vez
                        RankClassLoader cl = new RankClassLoader(parent);
                        Thread.currentThread().setContextClassLoader(cl);
                        main = Class.forName(APP_CLASS, true, cl).getMethod("main", String[].class);
                    }
                    String[] a = new String[appArgs.length + 3];
                    a[0] = String.valueOf(rank);
                    a[1] = String.valueOf(np);
                    a[2] = "smpdev";
                    System.arraycopy(appArgs, 0, a, 3, appArgs.length);
                    main.invoke(null, (Object) a);
                } catch (Throwable t) {
                    errors[rank] = t;
                }
            }, String.valueOf(r));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        for (int r = 0; r < np; r++) {
            if (errors[r] != null) {
                System.err.println("Processo " + r + " falhou:");
                errors[r].printStackTrace();
                System.exit(1);
            }
        }
    }

    /** Extrai conf/mpjexpress.conf para uma pasta temporária e corre outra JVM com MPJ_HOME a apontar para lá. */
    private static int relaunchWithMpjHome(String[] args) throws IOException, InterruptedException {
        Path home = Files.createTempDirectory("mpj-home");
        Path conf = home.resolve("conf");
        Files.createDirectories(conf);
        Files.createDirectories(home.resolve("logs"));
        try (InputStream in = MpjLauncher.class.getResourceAsStream("mpjexpress.conf")) {
            if (in == null) throw new IOException("mpjexpress.conf não está no jar");
            Files.copy(in, conf.resolve("mpjexpress.conf"), StandardCopyOption.REPLACE_EXISTING);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // mantém as opções da JVM atual (ex.: -Xmx2g)
        cmd.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("dna.Main");
        cmd.add("mpj");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().put("MPJ_HOME", home.toString());
        int code = pb.start().waitFor();
        deleteRecursively(home.toFile());
        return code;
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    /**
     * ClassLoader de um processo MPI: carrega ele próprio (child-first) as classes mpi.*, mpjdev.* e dna.*,
     * lendo os .class do jar; tudo o resto (JDK, xdev, mpjbuf, log4j, mpiExp) vem do pai e é partilhado.
     */
    static final class RankClassLoader extends ClassLoader {
        RankClassLoader(ClassLoader parent) { super(parent); }

        private static boolean perRank(String name) {
            if (SHARED.contains(name) || name.equals(MpjLauncher.class.getName())
                    || name.startsWith(RankClassLoader.class.getName())) return false;
            return name.startsWith("mpi.") || name.startsWith("mpjdev.") || name.startsWith("dna.");
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!perRank(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    String res = name.replace('.', '/') + ".class";
                    try (InputStream in = getParent().getResourceAsStream(res)) {
                        if (in == null) throw new ClassNotFoundException(name);
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        in.transferTo(buf);
                        byte[] b = buf.toByteArray();
                        c = defineClass(name, b, 0, b.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
