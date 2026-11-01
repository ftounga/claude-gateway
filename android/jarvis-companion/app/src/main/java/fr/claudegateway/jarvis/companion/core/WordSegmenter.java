package fr.claudegateway.jarvis.companion.core;

/**
 * <b>La découpe des mots isolés</b> — pur : on lui donne l'énergie de chaque trame de 10 ms, il dit où
 * commence un mot et quand il est fini. Le bruit de fond est suivi (plancher adaptatif) ; un mot = de
 * l'énergie au-dessus de trois fois le plancher, entre 0,2 et 1,5 s, suivi de 0,25 s de calme.
 * Portage fidèle de {@code WordSegmenter} (SF-231-05).
 */
public final class WordSegmenter {

    /** Marque « phrase trop longue, attendre le calme » (le {@code -Infinity} du TypeScript). */
    private static final long TOO_LONG = Long.MIN_VALUE;
    private static final long NONE = -1;

    private double floor = 0.004;
    private long start = NONE;
    private int quiet = 0;
    private long index = 0;

    /** Rend {@code [début, fin]} (en trames) quand un mot vient de finir, sinon {@code null}. */
    public long[] push(double energy) {
        long i = index++;
        boolean speech = energy > Math.max(0.012, floor * 3);
        if (!speech && start == NONE) {
            floor = floor * 0.95 + energy * 0.05;
        }
        if (speech) {
            if (start == NONE) {
                start = i;
            }
            quiet = 0;
            if (start != TOO_LONG && i - start > WakeEngine.MAX_WORD_FRAMES) {
                // Trop long pour un mot : une phrase. On l'oublie et on attend le calme.
                start = TOO_LONG;
            }
            return null;
        }
        if (start == NONE) {
            return null;
        }
        quiet++;
        if (quiet < WakeEngine.END_SILENCE_FRAMES) {
            return null;
        }
        long begin = start;
        long end = i - quiet + 1;
        start = NONE;
        quiet = 0;
        if (begin == TOO_LONG || end - begin < WakeEngine.MIN_WORD_FRAMES) {
            return null;
        }
        return new long[] {begin, end};
    }

    /** Numéro de la prochaine trame. */
    public long position() {
        return index;
    }
}
