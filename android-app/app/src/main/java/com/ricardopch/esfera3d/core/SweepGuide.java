package com.ricardopch.esfera3d.core;

/**
 * Lógica del barrido guiado: qué vuelta toca (horizonte, arriba, abajo), qué parte de
 * cada vuelta está ya cubierta y qué indicación hay que dar al usuario.
 */
public final class SweepGuide {
    public enum Hint { START, TURN, SLOWER, RAISE, LOWER, STRAIGHTEN, RING_DONE, ALL_DONE }

    public static final class Ring {
        public final double pitchDeg;
        public final boolean[] bins = new boolean[360];
        public int covered;
        /** Grados girados dentro de la vuelta (para cerrarla con algo de solape). */
        public double travel;

        Ring(double pitchDeg) {
            this.pitchDeg = pitchDeg;
        }

        public double progress() {
            return Math.min(covered / 360.0, travel / REQUIRED_TRAVEL);
        }
    }

    public static final double PITCH_TOL = 7;
    /** Giro exigido por vuelta: una vuelta entera más un solape para fundir el cierre. */
    public static final double REQUIRED_TRAVEL = 385;
    public static final double ROLL_TOL = 15;
    public static final double SPEED_WARN = 30;   // grados por segundo
    public static final double SPEED_MAX = 50;

    public final Ring[] rings;
    private int current;
    private boolean hasLast;
    private double lastHeading;
    private long lastTimeNs;
    private double speed; // grados/s suavizado
    private Hint hint = Hint.START;
    private long ringDoneAtNs;

    /** @param full true: horizonte + arriba + abajo (esfera completa); false: solo horizonte. */
    public SweepGuide(boolean full, double upDownPitchDeg) {
        if (full) {
            rings = new Ring[]{new Ring(0), new Ring(upDownPitchDeg), new Ring(-upDownPitchDeg)};
        } else {
            rings = new Ring[]{new Ring(0)};
        }
    }

    public int currentIndex() {
        return current;
    }

    public Ring currentRing() {
        return current < rings.length ? rings[current] : null;
    }

    public boolean isDone() {
        return current >= rings.length;
    }

    public Hint hint() {
        return hint;
    }

    public double speedDegPerSec() {
        return speed;
    }

    public double totalProgress() {
        double p = 0;
        for (Ring r : rings) p += r.progress();
        return p / rings.length;
    }

    /** Fuerza el paso a la siguiente vuelta (botón "saltar"). */
    public void skipRing() {
        if (current < rings.length) current++;
        hasLast = false;
        hint = isDone() ? Hint.ALL_DONE : Hint.RING_DONE;
    }

    /**
     * Actualiza con la orientación actual (ya referida al primer fotograma).
     *
     * @return true si en esta llamada se ha completado una vuelta
     */
    public boolean update(double headingDeg, double pitchDeg, double rollDeg, long timeNs) {
        if (isDone()) {
            hint = Hint.ALL_DONE;
            return false;
        }
        Ring ring = rings[current];
        double delta = 0;
        if (hasLast) {
            delta = wrap180(headingDeg - lastHeading);
            double dt = (timeNs - lastTimeNs) * 1e-9;
            if (dt > 0.001) {
                double inst = Math.abs(delta) / dt;
                speed = speed * 0.8 + inst * 0.2;
            }
        }
        boolean pitchOk = Math.abs(pitchDeg - ring.pitchDeg) <= PITCH_TOL;
        boolean rollOk = Math.abs(rollDeg) <= ROLL_TOL;
        boolean speedOk = speed <= SPEED_MAX;

        if (hasLast && pitchOk && rollOk && speedOk && Math.abs(delta) < 15) {
            markBins(ring, lastHeading, headingDeg);
            ring.travel += Math.abs(delta);
        } else if (!hasLast && pitchOk && rollOk) {
            markBins(ring, headingDeg, headingDeg);
        }
        lastHeading = headingDeg;
        lastTimeNs = timeNs;
        hasLast = true;

        if (ring.covered >= 360 && ring.travel >= REQUIRED_TRAVEL) {
            current++;
            hasLast = false;
            ringDoneAtNs = timeNs;
            hint = isDone() ? Hint.ALL_DONE : Hint.RING_DONE;
            return true;
        }
        if (hint == Hint.RING_DONE && timeNs - ringDoneAtNs < 2_500_000_000L && !pitchOk) {
            return false; // mantener el aviso de vuelta terminada unos segundos
        }
        if (!rollOk) hint = Hint.STRAIGHTEN;
        else if (pitchDeg < ring.pitchDeg - PITCH_TOL * 0.6) hint = Hint.RAISE;
        else if (pitchDeg > ring.pitchDeg + PITCH_TOL * 0.6) hint = Hint.LOWER;
        else if (speed > SPEED_WARN) hint = Hint.SLOWER;
        else hint = Hint.TURN;
        return false;
    }

    private static void markBins(Ring ring, double fromDeg, double toDeg) {
        double d = wrap180(toDeg - fromDeg);
        int steps = (int) Math.ceil(Math.abs(d)) + 1;
        for (int i = 0; i <= steps; i++) {
            double h = fromDeg + d * i / steps;
            int b = (int) Math.floor(((h % 360) + 360) % 360);
            if (b >= 360) b = 359;
            if (!ring.bins[b]) {
                ring.bins[b] = true;
                ring.covered++;
            }
        }
    }

    public static double wrap180(double a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }
}
