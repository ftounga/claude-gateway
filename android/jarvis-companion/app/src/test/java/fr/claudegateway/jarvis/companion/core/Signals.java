package fr.claudegateway.jarvis.companion.core;

/** Signaux synthétiques pour les tests : silence, « mots » (glissements de fréquence), PCM 16 bits. */
final class Signals {

    private Signals() {
    }

    static float[] silence(double seconds) {
        return new float[(int) (seconds * WakeEngine.RATE)];
    }

    /** Un « mot » : glissement de fréquence de {@code f0} à {@code f1} Hz, enveloppe douce. */
    static float[] sweep(double f0, double f1, double seconds, double amplitude) {
        int n = (int) (seconds * WakeEngine.RATE);
        float[] out = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            double f = f0 + (f1 - f0) * t;
            phase += 2 * Math.PI * f / WakeEngine.RATE;
            double envelope = Math.sin(Math.PI * t);
            out[i] = (float) (amplitude * envelope * (Math.sin(phase) + 0.4 * Math.sin(2.7 * phase)));
        }
        return out;
    }

    static float[] concat(float[]... parts) {
        int total = 0;
        for (float[] p : parts) {
            total += p.length;
        }
        float[] out = new float[total];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    static short[] pcm(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767)));
        }
        return out;
    }
}
