package fr.claudegateway.jarvis.companion.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class WakeEngineTest {

    private static double[][] features(float[] word) {
        return WakeEngine.mfcc(word);
    }

    @Test
    public void mfccRendDouzeCoefficientsCentresParTrame() {
        double[][] f = features(Signals.sweep(300, 1200, 0.5, 0.3));
        assertEquals((8000 - 400) / 160 + 1, f.length);
        for (int c = 0; c < WakeEngine.CEPSTRA; c++) {
            double sum = 0;
            for (double[] frame : f) {
                assertEquals(WakeEngine.CEPSTRA, frame.length);
                sum += frame[c];
            }
            assertEquals(0, sum / f.length, 1e-9);
        }
    }

    @Test
    public void mfccDunSignalTropCourtEstVide() {
        assertEquals(0, WakeEngine.mfcc(new float[100]).length);
    }

    @Test
    public void dtwNulleSurElleMemeEtSymetrique() {
        double[][] a = features(Signals.sweep(300, 1200, 0.5, 0.3));
        double[][] b = features(Signals.sweep(2500, 600, 0.6, 0.3));
        assertEquals(0, WakeEngine.dtw(a, a), 1e-9);
        assertEquals(WakeEngine.dtw(a, b), WakeEngine.dtw(b, a), 1e-9);
        assertTrue(Double.isInfinite(WakeEngine.dtw(a, new double[0][])));
    }

    @Test
    public void reconnaitLeMotAppris_etRejetteUnAutre() {
        double[][][] templates = {
            features(Signals.sweep(300, 1200, 0.50, 0.30)),
            features(Signals.sweep(320, 1180, 0.55, 0.25)),
            features(Signals.sweep(290, 1250, 0.48, 0.35)),
        };
        WakeProfile profile = WakeProfile.learn(templates);
        assertTrue(profile.threshold() > 0);

        assertTrue(WakeEngine.matches(features(Signals.sweep(310, 1220, 0.52, 0.28)), profile));
        assertFalse(WakeEngine.matches(features(Signals.sweep(3000, 3200, 0.52, 0.28)), profile));
    }

    @Test
    public void unMotTropCourtNeReveillePas() {
        double[][] word = features(Signals.sweep(300, 1200, 0.5, 0.3));
        WakeProfile profile = WakeProfile.learn(new double[][][] {word, word, word});
        double[][] tooShort = features(Signals.sweep(300, 1200, 0.1, 0.3));
        assertFalse(WakeEngine.matches(tooShort, profile));
        assertFalse(WakeEngine.matches(word, null));
    }

    @Test
    public void leProfilFaitLAllerRetour() {
        double[][][] templates = {
            features(Signals.sweep(300, 1200, 0.50, 0.30)),
            features(Signals.sweep(320, 1180, 0.55, 0.25)),
        };
        WakeProfile profile = WakeProfile.learn(templates);
        WakeProfile back = WakeProfile.decode(profile.encode());
        assertNotNull(back);
        assertEquals(profile.threshold(), back.threshold(), 1e-4);
        assertEquals(2, back.templates().length);
        assertEquals(templates[1].length, back.templates()[1].length);
        assertEquals(templates[1][3][4], back.templates()[1][3][4], 1e-3);
        assertNull(WakeProfile.decode("abîmé"));
        assertNull(WakeProfile.decode(""));
        assertNull(WakeProfile.decode(null));
    }

    @Test
    public void lEcouteDecoupeUnMotDansLeFluxEtLeReconnait() {
        double[][][] templates = {
            features(Signals.sweep(300, 1200, 0.50, 0.30)),
            features(Signals.sweep(320, 1180, 0.55, 0.25)),
            features(Signals.sweep(290, 1250, 0.48, 0.35)),
        };
        WakeProfile profile = WakeProfile.learn(templates);
        int[] wakes = {0};
        WakeListener listener = WakeListener.forWake(profile, () -> wakes[0]++);

        short[] stream = Signals.pcm(Signals.concat(Signals.silence(1.0), Signals.sweep(310, 1220, 0.52, 0.3),
                Signals.silence(1.0), Signals.sweep(3000, 3200, 0.52, 0.3), Signals.silence(1.0)));
        // Paquets de taille irrégulière, comme ceux d'AudioRecord.
        int at = 0;
        int size = 1234;
        while (at < stream.length) {
            int n = Math.min(size, stream.length - at);
            short[] packet = new short[n];
            System.arraycopy(stream, at, packet, 0, n);
            listener.push(packet, n);
            at += n;
        }
        assertEquals(1, wakes[0]);
    }

    @Test
    public void lApprentissageRendChaqueMotIsole() {
        List<double[][]> words = new ArrayList<>();
        WakeListener listener = new WakeListener(words::add);
        short[] stream = Signals.pcm(Signals.concat(Signals.silence(0.5), Signals.sweep(300, 1200, 0.5, 0.3),
                Signals.silence(0.6), Signals.sweep(300, 1200, 0.5, 0.3), Signals.silence(0.6)));
        listener.push(stream, stream.length);
        assertEquals(2, words.size());
    }
}
