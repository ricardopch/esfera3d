package com.ricardopch.esfera3d;

/**
 * Tiempo de exposición y de lectura del obturador de los últimos fotogramas, indexados por
 * su marca de tiempo del sensor. Si un dato no llega, se usa el último conocido o un valor típico.
 */
final class FrameTiming {
    private static final int N = 64;
    private static final long DEFAULT_EXPOSURE = 16_000_000L, DEFAULT_SKEW = 22_000_000L;
    private final long[] ts = new long[N], exp = new long[N], skew = new long[N];
    private int head = -1;
    private long lastExp = DEFAULT_EXPOSURE, lastSkew = DEFAULT_SKEW;

    synchronized void put(long t, long exposureNs, long skewNs) {
        head = (head + 1) % N;
        ts[head] = t;
        exp[head] = exposureNs > 0 ? exposureNs : lastExp;
        skew[head] = skewNs > 0 ? skewNs : lastSkew;
        lastExp = exp[head];
        lastSkew = skew[head];
    }

    /** {exposición, lectura} en nanosegundos para el fotograma con esa marca de tiempo. */
    synchronized long[] get(long t) {
        for (int k = 0; k < N && head >= 0; k++) {
            int i = (head - k + N) % N;
            if (ts[i] == t) return new long[]{exp[i], skew[i]};
        }
        return new long[]{lastExp, lastSkew};
    }
}
