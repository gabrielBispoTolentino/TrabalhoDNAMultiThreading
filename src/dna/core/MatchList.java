package dna.core;

import java.util.Arrays;

/**
 * Lista compacta de ocorrências de UM padrão.
 * Cada ocorrência é guardada num long: (posição << 2) | divergências (0..3).
 * Assim a ordenação dos longs coincide com a ordenação por posição.
 */
public final class MatchList {

    private long[] data = new long[16];
    private int size;

    public static long encode(long pos, int mismatches) {
        return (pos << 2) | mismatches;
    }

    public static long position(long code) { return code >>> 2; }

    public static int mismatches(long code) { return (int) (code & 3); }

    public void add(long pos, int mismatches) {
        addCode(encode(pos, mismatches));
    }

    public void addCode(long code) {
        if (size == data.length) data = Arrays.copyOf(data, size * 2);
        data[size++] = code;
    }

    public void addAll(MatchList other) {
        ensure(size + other.size);
        System.arraycopy(other.data, 0, data, size, other.size);
        size += other.size;
    }

    public void addAll(long[] codes, int from, int count) {
        ensure(size + count);
        System.arraycopy(codes, from, data, size, count);
        size += count;
    }

    private void ensure(int cap) {
        if (cap > data.length) data = Arrays.copyOf(data, Math.max(cap, data.length * 2));
    }

    public int size() { return size; }

    public long get(int i) { return data[i]; }

    public long[] toArray() { return Arrays.copyOf(data, size); }

    public void sort() { Arrays.sort(data, 0, size); }

    public boolean sameAs(MatchList o) {
        return size == o.size && Arrays.equals(data, 0, size, o.data, 0, o.size);
    }
}
