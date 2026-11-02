package fr.claudegateway.jarvis;

import java.util.List;

/**
 * <b>Combien de mots Jarvis dit</b> (F-240 / SF-240-01) — même règle que l'écran ({@code spokenWords},
 * {@code jarvis-oral.ts}) : un mot est un groupe séparé par des blancs qui contient au moins une lettre ou un
 * chiffre. L'élision et le trait d'union ne coupent pas (« l'exposition », « dites-moi », « aujourd'hui » = un mot) ;
 * la ponctuation, les marques (🎙, ↪) et le balisage ne comptent pas ; « Monsieur » compte.
 *
 * <p>Pur et déterministe : aucune dépendance.</p>
 */
public final class JarvisSpokenWords {

    private JarvisSpokenWords() {
    }

    /** Le nombre de mots de {@code text} ; 0 pour un texte vide ou {@code null}. */
    public static int count(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int words = 0;
        for (String token : text.strip().split("\\s+")) {
            if (token.codePoints().anyMatch(Character::isLetterOrDigit)) {
                words++;
            }
        }
        return words;
    }

    /** Percentile au rang le plus proche d'une liste <b>triée</b> ; {@code null} si vide. */
    public static Integer percentile(List<Integer> sorted, int p) {
        if (sorted == null || sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
