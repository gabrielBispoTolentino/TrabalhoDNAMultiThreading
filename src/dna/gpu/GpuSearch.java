package dna.gpu;

import dna.core.PatternReader;
import dna.core.SearchResult;
import org.jocl.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.jocl.CL.*;

/**
 * Versão GPU com OpenCL (JOCL): um work-item por posição inicial da sequência.
 *
 * A sequência é enviada ao device em trechos (para não esgotar a memória da GPU).
 * Tal como no MPJ, cada trecho leva (maxLen - 1) bytes extra do trecho seguinte,
 * para que ocorrências que cruzam a fronteira sejam detetadas.
 * As ocorrências são escritas num buffer com contador atómico (ordem arbitrária);
 * no fim, cada lista é ordenada por posição.
 */
public final class GpuSearch {

    private static final int DEFAULT_CHUNK = 64 * 1024 * 1024; // posições por trecho
    private static final int LOCAL_SIZE = 256;

    private GpuSearch() {}

    public static SearchResult search(byte[] seq, byte[][] patterns, int maxMismatches) throws IOException {
        return search(seq, patterns, maxMismatches, DEFAULT_CHUNK);
    }

    public static SearchResult search(byte[] seq, byte[][] patterns, int maxMismatches, int chunkStarts)
            throws IOException {
        CL.setExceptionsEnabled(true);
        long t0 = System.nanoTime();

        // ---------- escolher plataforma/dispositivo (GPU preferida, senão qualquer) ----------
        cl_device_id device = null;
        cl_platform_id platform = null;
        int[] num = new int[1];
        try {
            clGetPlatformIDs(0, null, num);
        } catch (CLException e) {
            num[0] = 0; // CL_PLATFORM_NOT_FOUND_KHR: loader instalado mas nenhum driver
        }
        if (num[0] == 0) throw new IllegalStateException("Nenhuma plataforma OpenCL encontrada - instale o driver OpenCL da GPU (ou pocl para CPU)");
        cl_platform_id[] platforms = new cl_platform_id[num[0]];
        clGetPlatformIDs(platforms.length, platforms, null);
        // Ordem de preferência explícita. (Não usar CL_DEVICE_TYPE_ALL: no JOCL vale -1 como long
        // e alguns drivers, como o pocl, rejeitam-no com CL_INVALID_DEVICE_TYPE.)
        for (long type : new long[]{CL_DEVICE_TYPE_GPU, CL_DEVICE_TYPE_ACCELERATOR, CL_DEVICE_TYPE_CPU}) {
            for (cl_platform_id pl : platforms) {
                int[] nd = new int[1];
                try {
                    clGetDeviceIDs(pl, type, 0, null, nd);
                } catch (CLException e) {
                    continue; // CL_DEVICE_NOT_FOUND para este tipo
                }
                if (nd[0] > 0) {
                    cl_device_id[] ds = new cl_device_id[nd[0]];
                    clGetDeviceIDs(pl, type, nd[0], ds, null);
                    device = ds[0];
                    platform = pl;
                    break;
                }
            }
            if (device != null) break;
        }
        if (device == null) throw new IllegalStateException("Nenhum dispositivo OpenCL encontrado");
        String devName = deviceName(device);
        System.out.println("Dispositivo OpenCL: " + devName);

        cl_context_properties props = new cl_context_properties();
        props.addProperty(CL_CONTEXT_PLATFORM, platform);
        cl_context ctx = clCreateContext(props, 1, new cl_device_id[]{device}, null, null, null);
        // clCreateCommandQueue (OpenCL 1.2) em vez da versão 2.0, para funcionar também em GPUs NVIDIA
        @SuppressWarnings("deprecation")
        cl_command_queue queue = clCreateCommandQueue(ctx, device, 0, null);

        cl_program program = clCreateProgramWithSource(ctx, 1, new String[]{loadKernel()}, null, null);
        try {
            clBuildProgram(program, 0, null, null, null, null);
        } catch (CLException e) {
            long[] sz = new long[1];
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, 0, null, sz);
            byte[] log = new byte[(int) sz[0]];
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, log.length, Pointer.to(log), null);
            throw new IllegalStateException("Erro ao compilar kernel:\n" + new String(log, StandardCharsets.US_ASCII), e);
        }
        cl_kernel kernel = clCreateKernel(program, "motif_search", null);

        // ---------- padrões (constantes para todo o processamento) ----------
        int numPats = patterns.length;
        int maxLen = PatternReader.maxLength(patterns);
        int[] off = new int[numPats], len = new int[numPats];
        int total = 0;
        for (int p = 0; p < numPats; p++) { off[p] = total; len[p] = patterns[p].length; total += len[p]; }
        byte[] packed = new byte[total];
        for (int p = 0; p < numPats; p++) System.arraycopy(patterns[p], 0, packed, off[p], len[p]);

        cl_mem dPats = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, total, Pointer.to(packed), null);
        cl_mem dOff = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (long) Sizeof.cl_int * numPats, Pointer.to(off), null);
        cl_mem dLen = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (long) Sizeof.cl_int * numPats, Pointer.to(len), null);

        int n = seq.length;
        int chunk = Math.min(chunkStarts, Math.max(1, n));
        cl_mem dSeq = clCreateBuffer(ctx, CL_MEM_READ_ONLY, (long) chunk + maxLen, null, null);
        cl_mem dCounter = clCreateBuffer(ctx, CL_MEM_READ_WRITE, Sizeof.cl_int, null, null);

        int capacity = 1 << 20;
        cl_mem[] out = allocOutput(ctx, capacity);

        SearchResult r = new SearchResult(patterns);
        int[] counter = new int[1];
        long tSetup = System.nanoTime() - t0, tUpload = 0, tKernel = 0, tDownload = 0;

        for (int start = 0; start < n; start += chunk) {
            int numStarts = Math.min(chunk, n - start);
            int chunkLen = Math.min(n - start, numStarts + maxLen - 1); // sobreposição
            long ta = System.nanoTime();
            clEnqueueWriteBuffer(queue, dSeq, CL_TRUE, 0, chunkLen, Pointer.to(seq).withByteOffset(start), 0, null, null);
            tUpload += System.nanoTime() - ta;
            ta = System.nanoTime();

            while (true) {
                counter[0] = 0;
                clEnqueueWriteBuffer(queue, dCounter, CL_TRUE, 0, Sizeof.cl_int, Pointer.to(counter), 0, null, null);
                int a = 0;
                clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(dSeq));
                clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{chunkLen}));
                clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{numStarts}));
                clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(dPats));
                clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(dOff));
                clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(dLen));
                clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{numPats}));
                clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{maxMismatches}));
                clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(dCounter));
                clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(out[0]));
                clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(out[1]));
                clSetKernelArg(kernel, a++, Sizeof.cl_mem, Pointer.to(out[2]));
                clSetKernelArg(kernel, a++, Sizeof.cl_int, Pointer.to(new int[]{capacity}));

                long global = ((long) numStarts + LOCAL_SIZE - 1) / LOCAL_SIZE * LOCAL_SIZE;
                clEnqueueNDRangeKernel(queue, kernel, 1, null, new long[]{global}, new long[]{LOCAL_SIZE}, 0, null, null);
                clEnqueueReadBuffer(queue, dCounter, CL_TRUE, 0, Sizeof.cl_int, Pointer.to(counter), 0, null, null);

                if (counter[0] <= capacity) break;
                // buffer cheio: aumentar e repetir este trecho
                for (cl_mem m : out) clReleaseMemObject(m);
                capacity = (int) Math.min(Integer.MAX_VALUE / 8, (long) counter[0] * 3 / 2);
                out = allocOutput(ctx, capacity);
            }

            tKernel += System.nanoTime() - ta;
            ta = System.nanoTime();
            int found = counter[0];
            if (found > 0) {
                int[] pos = new int[found], pat = new int[found];
                byte[] mis = new byte[found];
                clEnqueueReadBuffer(queue, out[0], CL_TRUE, 0, (long) Sizeof.cl_int * found, Pointer.to(pos), 0, null, null);
                clEnqueueReadBuffer(queue, out[1], CL_TRUE, 0, (long) Sizeof.cl_int * found, Pointer.to(pat), 0, null, null);
                clEnqueueReadBuffer(queue, out[2], CL_TRUE, 0, found, Pointer.to(mis), 0, null, null);
                for (int k = 0; k < found; k++) r.matches[pat[k]].add((long) start + pos[k], mis[k]);
            }
            tDownload += System.nanoTime() - ta;
        }
        long ts = System.nanoTime();
        r.sortAll(); // atomic_inc dá ordem arbitrária
        long tSort = System.nanoTime() - ts;
        System.out.printf("  tempos GPU: preparação (contexto + compilar kernel) %.3f s | envio da sequência %.3f s | "
                        + "kernel %.3f s | receção dos resultados %.3f s | ordenação %.3f s%n",
                tSetup / 1e9, tUpload / 1e9, tKernel / 1e9, tDownload / 1e9, tSort / 1e9);

        for (cl_mem m : out) clReleaseMemObject(m);
        clReleaseMemObject(dSeq); clReleaseMemObject(dCounter);
        clReleaseMemObject(dPats); clReleaseMemObject(dOff); clReleaseMemObject(dLen);
        clReleaseKernel(kernel); clReleaseProgram(program);
        clReleaseCommandQueue(queue); clReleaseContext(ctx);

        r.elapsedNanos = System.nanoTime() - t0;
        r.method = "GPU/OpenCL (" + devName + ")";
        return r;
    }

    private static cl_mem[] allocOutput(cl_context ctx, int capacity) {
        return new cl_mem[]{
                clCreateBuffer(ctx, CL_MEM_WRITE_ONLY, (long) Sizeof.cl_int * capacity, null, null),
                clCreateBuffer(ctx, CL_MEM_WRITE_ONLY, (long) Sizeof.cl_int * capacity, null, null),
                clCreateBuffer(ctx, CL_MEM_WRITE_ONLY, capacity, null, null)};
    }

    private static String deviceName(cl_device_id d) {
        long[] sz = new long[1];
        clGetDeviceInfo(d, CL_DEVICE_NAME, 0, null, sz);
        byte[] b = new byte[(int) sz[0]];
        clGetDeviceInfo(d, CL_DEVICE_NAME, b.length, Pointer.to(b), null);
        return new String(b, 0, Math.max(0, b.length - 1), StandardCharsets.US_ASCII).trim();
    }

    private static String loadKernel() throws IOException {
        try (InputStream in = GpuSearch.class.getResourceAsStream("motif_search.cl")) {
            if (in == null) throw new IOException("motif_search.cl não encontrado no classpath");
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
