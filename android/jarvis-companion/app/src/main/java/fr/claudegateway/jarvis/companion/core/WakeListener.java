package fr.claudegateway.jarvis.companion.core;

/**
 * <b>L'écoute du mot d'éveil</b>, sans le micro : on lui pousse des paquets PCM 16 bits à 16 kHz
 * (tels que les rend {@code AudioRecord}), il découpe les mots isolés et rend l'empreinte de chacun.
 * Portage de la boucle {@code onaudioprocess} de SF-231-05.
 *
 * <p>Deux secondes de signal au plus en mémoire — de quoi reprendre le mot qui vient de finir — et
 * rien d'autre : <b>rien n'est enregistré, rien ne sort</b>.</p>
 */
public final class WakeListener {

    /** Reçoit l'empreinte de chaque mot isolé entendu. */
    public interface WordSink {
        void onWord(double[][] features);
    }

    private static final int KEEP = WakeEngine.RATE * 2;

    private final WordSink sink;
    private final WordSegmenter segmenter = new WordSegmenter();
    private float[] buffer = new float[0];
    private long bufferStartFrame = 0;
    private float[] carry = new float[0];

    public WakeListener(WordSink sink) {
        this.sink = sink;
    }

    /** Écoute « Jarvis » : appelle {@code onWake} à chaque mot qui ressemble aux gabarits. */
    public static WakeListener forWake(WakeProfile profile, Runnable onWake) {
        return new WakeListener(features -> {
            if (WakeEngine.matches(features, profile)) {
                onWake.run();
            }
        });
    }

    /** Pousse {@code count} échantillons PCM 16 bits. */
    public void push(short[] pcm, int count) {
        float[] joined = new float[carry.length + count];
        System.arraycopy(carry, 0, joined, 0, carry.length);
        for (int i = 0; i < count; i++) {
            joined[carry.length + i] = pcm[i] / 32768f;
        }
        int usable = joined.length - (joined.length % WakeEngine.HOP);
        carry = java.util.Arrays.copyOfRange(joined, usable, joined.length);
        float[] chunk = java.util.Arrays.copyOfRange(joined, 0, usable);
        buffer = concat(buffer, chunk);
        for (double energy : WakeEngine.frameEnergies(chunk, chunk.length)) {
            long[] word = segmenter.push(energy);
            if (word != null) {
                int from = (int) Math.max(0, (word[0] - 5 - bufferStartFrame) * WakeEngine.HOP);
                int to = (int) Math.max(from, (word[1] + 3 - bufferStartFrame) * WakeEngine.HOP);
                float[] slice = java.util.Arrays.copyOfRange(buffer, from, Math.min(buffer.length, to));
                sink.onWord(WakeEngine.mfcc(slice));
            }
        }
        if (buffer.length > KEEP) {
            int over = buffer.length - KEEP;
            int drop = over - (over % WakeEngine.HOP);
            buffer = java.util.Arrays.copyOfRange(buffer, drop, buffer.length);
            bufferStartFrame += drop / WakeEngine.HOP;
        }
    }

    private static float[] concat(float[] a, float[] b) {
        float[] out = new float[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
