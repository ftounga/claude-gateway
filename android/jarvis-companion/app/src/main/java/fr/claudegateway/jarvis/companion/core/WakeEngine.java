package fr.claudegateway.jarvis.companion.core;

/**
 * <b>« Jarvis… » — le mot d'éveil, sur l'appareil</b> (F-232 / SF-232-01).
 *
 * <p>Portage Java du moteur de SF-231-05 ({@code frontend/src/app/jarvis/jarvis-wake.ts}) : mêmes
 * constantes, même empreinte (MFCC), même comparaison (DTW), même seuil. Un seul comportement
 * d'éveil, que le PO parle à l'écran Jarvis ouvert ou au téléphone verrouillé.</p>
 *
 * <p>Pur Java : aucune dépendance Android, testable hors SDK.</p>
 */
public final class WakeEngine {

    /** Fréquence de travail des empreintes. */
    public static final int RATE = 16_000;
    static final int FRAME = 400; // 25 ms
    public static final int HOP = 160; // 10 ms
    static final int FFT_SIZE = 512;
    static final int MEL_BANDS = 26;
    public static final int CEPSTRA = 12;

    /** Un mot isolé dure entre ces bornes (en trames de 10 ms). */
    public static final int MIN_WORD_FRAMES = 20;
    public static final int MAX_WORD_FRAMES = 150;
    /** Autant de trames calmes closent un mot. */
    public static final int END_SILENCE_FRAMES = 25;
    /** Nombre de répétitions demandées à l'apprentissage. */
    public static final int ENROLL_COUNT = 3;
    /** Marge du seuil au-dessus de l'écart maximal entre gabarits. */
    static final double THRESHOLD_MARGIN = 1.35;

    private static final double[][] MEL = melFilters();
    private static final double[] WINDOW = hamming();

    private WakeEngine() {
    }

    /** L'énergie (RMS) de chaque trame de 10 ms. */
    public static double[] frameEnergies(float[] samples, int length) {
        int count = length / HOP;
        double[] energies = new double[count];
        for (int f = 0; f < count; f++) {
            double sum = 0;
            for (int i = f * HOP; i < (f + 1) * HOP; i++) {
                sum += samples[i] * samples[i];
            }
            energies[f] = Math.sqrt(sum / HOP);
        }
        return energies;
    }

    /**
     * L'empreinte d'un mot : 12 coefficients cepstraux par trame de 25 ms (pas de 10 ms), centrés
     * (moyenne retirée) pour ne pas dépendre du micro ni du volume.
     */
    public static double[][] mfcc(float[] samples) {
        int count = samples.length < FRAME ? 0 : (samples.length - FRAME) / HOP + 1;
        double[][] frames = new double[count][];
        float[] frame = new float[FRAME];
        for (int f = 0; f < count; f++) {
            int start = f * HOP;
            for (int i = 0; i < FRAME; i++) {
                float previous = start + i > 0 ? samples[start + i - 1] : 0f;
                frame[i] = (float) ((samples[start + i] - 0.97 * previous) * WINDOW[i]);
            }
            double[] power = powerSpectrum(frame);
            double[] logMel = new double[MEL_BANDS];
            for (int m = 0; m < MEL_BANDS; m++) {
                double acc = 0;
                for (int k = 0; k < power.length; k++) {
                    acc += MEL[m][k] * power[k];
                }
                logMel[m] = Math.log(1e-10 + acc);
            }
            double[] coeffs = new double[CEPSTRA];
            for (int c = 1; c <= CEPSTRA; c++) {
                double sum = 0;
                for (int m = 0; m < MEL_BANDS; m++) {
                    sum += logMel[m] * Math.cos(Math.PI * c * (m + 0.5) / MEL_BANDS);
                }
                coeffs[c - 1] = sum;
            }
            frames[f] = coeffs;
        }
        if (count == 0) {
            return frames;
        }
        double[] mean = new double[CEPSTRA];
        for (double[] f : frames) {
            for (int i = 0; i < CEPSTRA; i++) {
                mean[i] += f[i] / count;
            }
        }
        for (double[] f : frames) {
            for (int i = 0; i < CEPSTRA; i++) {
                f[i] -= mean[i];
            }
        }
        return frames;
    }

    /** Distance d'alignement temporel (DTW) entre deux empreintes, rapportée à la longueur du chemin. */
    public static double dtw(double[][] a, double[][] b) {
        if (a.length == 0 || b.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        int n = a.length;
        int m = b.length;
        double[] previous = new double[m + 1];
        double[] current = new double[m + 1];
        java.util.Arrays.fill(previous, Double.POSITIVE_INFINITY);
        previous[0] = 0;
        for (int i = 1; i <= n; i++) {
            java.util.Arrays.fill(current, Double.POSITIVE_INFINITY);
            for (int j = 1; j <= m; j++) {
                double d = 0;
                double[] x = a[i - 1];
                double[] y = b[j - 1];
                for (int k = 0; k < x.length; k++) {
                    d += (x[k] - y[k]) * (x[k] - y[k]);
                }
                current[j] = Math.sqrt(d) + Math.min(previous[j], Math.min(current[j - 1], previous[j - 1]));
            }
            double[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[m] / (n + m);
    }

    /** Le seuil d'acceptation : l'écart le plus grand entre les gabarits appris, avec une marge. */
    public static double enrollThreshold(double[][][] templates) {
        double worst = 0;
        for (int i = 0; i < templates.length; i++) {
            for (int j = i + 1; j < templates.length; j++) {
                worst = Math.max(worst, dtw(templates[i], templates[j]));
            }
        }
        return worst * THRESHOLD_MARGIN;
    }

    /** Le mot entendu ressemble-t-il à « Jarvis » ? */
    public static boolean matches(double[][] features, WakeProfile profile) {
        if (profile == null || features.length < MIN_WORD_FRAMES || profile.templates().length == 0) {
            return false;
        }
        double best = Double.POSITIVE_INFINITY;
        for (double[][] template : profile.templates()) {
            best = Math.min(best, dtw(features, template));
        }
        return best <= profile.threshold();
    }

    // ------------------------------------------------------------------ internes

    private static double[] hamming() {
        double[] w = new double[FRAME];
        for (int i = 0; i < FRAME; i++) {
            w[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME - 1));
        }
        return w;
    }

    private static double toMel(double f) {
        return 2595 * Math.log10(1 + f / 700);
    }

    private static double fromMel(double m) {
        return 700 * (Math.pow(10, m / 2595) - 1);
    }

    private static double[][] melFilters() {
        double maxMel = toMel(RATE / 2.0);
        int[] bins = new int[MEL_BANDS + 2];
        for (int i = 0; i < bins.length; i++) {
            double f = fromMel(maxMel * i / (MEL_BANDS + 1));
            bins[i] = (int) Math.floor((FFT_SIZE + 1) * f / RATE);
        }
        double[][] filters = new double[MEL_BANDS][FFT_SIZE / 2 + 1];
        for (int m = 1; m <= MEL_BANDS; m++) {
            for (int k = bins[m - 1]; k < bins[m]; k++) {
                filters[m - 1][k] = (double) (k - bins[m - 1]) / Math.max(1, bins[m] - bins[m - 1]);
            }
            for (int k = bins[m]; k < bins[m + 1]; k++) {
                filters[m - 1][k] = (double) (bins[m + 1] - k) / Math.max(1, bins[m + 1] - bins[m]);
            }
        }
        return filters;
    }

    /** Spectre de puissance d'une trame (FFT radix-2 en place). */
    private static double[] powerSpectrum(float[] frame) {
        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        for (int i = 0; i < frame.length && i < FFT_SIZE; i++) {
            re[i] = frame[i];
        }
        for (int i = 1, j = 0; i < FFT_SIZE; i++) {
            int bit = FFT_SIZE >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
            }
        }
        for (int len = 2; len <= FFT_SIZE; len <<= 1) {
            double angle = -2 * Math.PI / len;
            for (int i = 0; i < FFT_SIZE; i += len) {
                for (int k = 0; k < len / 2; k++) {
                    double wr = Math.cos(angle * k);
                    double wi = Math.sin(angle * k);
                    double ar = re[i + k + len / 2];
                    double ai = im[i + k + len / 2];
                    double tr = ar * wr - ai * wi;
                    double ti = ar * wi + ai * wr;
                    re[i + k + len / 2] = re[i + k] - tr;
                    im[i + k + len / 2] = im[i + k] - ti;
                    re[i + k] += tr;
                    im[i + k] += ti;
                }
            }
        }
        double[] power = new double[FFT_SIZE / 2 + 1];
        for (int k = 0; k <= FFT_SIZE / 2; k++) {
            power[k] = (re[k] * re[k] + im[k] * im[k]) / FFT_SIZE;
        }
        return power;
    }
}
