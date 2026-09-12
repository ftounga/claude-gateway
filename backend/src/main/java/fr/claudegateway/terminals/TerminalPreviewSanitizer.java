package fr.claudegateway.terminals;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>Ce qui borne et nettoie un aperçu de terminal</b> (F-76 / SF-76-01).
 *
 * <p><b>Pourquoi au serveur.</b> L'écran pourrait très bien tronquer lui-même — et il le fera, pour
 * ne pas envoyer dix kilo-octets toutes les cinq secondes. Mais une borne tenue par l'appelant
 * n'est pas une borne : elle décrit le comportement du client d'aujourd'hui, pas ce que la table
 * accepte. La troncature est donc rejouée ici, et les tests la vérifient <b>depuis l'API</b>.</p>
 *
 * <p><b>Pourquoi nettoyer.</b> Une sortie de commande porte des séquences d'échappement ANSI — les
 * couleurs d'un {@code npm test}, la barre de progression d'un téléchargement. Un émulateur de
 * terminal les interprète ; une tuile HTML, non : elles s'y afficheraient telles quelles
 * ({@code ESC[31m}) et rendraient l'aperçu illisible, précisément là où l'on veut lire d'un coup
 * d'œil. On les retire une fois, à l'écriture, plutôt qu'à chaque affichage.</p>
 *
 * <p>Ce nettoyage n'est <b>pas</b> une protection contre l'injection : Angular échappe ce qu'il
 * rend. C'est de la lisibilité.</p>
 */
final class TerminalPreviewSanitizer {

    /** Nombre de lignes conservées. Les <b>dernières</b> : un aperçu montre où l'on en est. */
    static final int MAX_LINES = 6;

    /** Longueur d'une ligne. Au-delà, une tuile ne montrerait de toute façon que le début. */
    static final int MAX_LINE_LENGTH = 160;

    /** Longueur du détail d'activité (« npm test »). */
    static final int MAX_DETAIL_LENGTH = 120;

    /**
     * Séquences d'échappement ANSI : CSI (couleurs, curseur), OSC (titre de fenêtre) et les
     * échappements à deux caractères. Tout est retiré, rien n'est traduit — on ne cherche pas à
     * restituer des couleurs dans une tuile, on cherche à lire le texte.
     */
    private static final Pattern ANSI = Pattern.compile(
            "\\x1B\\[[0-?]*[ -/]*[@-~]"
                    + "|\\x1B\\][^\\x07]*(?:\\x07|\\x1B\\\\)"
                    + "|\\x1B[@-Z\\\\_]");

    /**
     * Caractères de contrôle restants (retour chariot d'une barre de progression, cloche, NUL…).
     * Les fins de ligne, elles, ont déjà servi à découper : il n'en reste aucune à ce stade.
     */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}]");

    private TerminalPreviewSanitizer() {
    }

    /**
     * Les <b>dernières</b> lignes exploitables, nettoyées et bornées. Une entrée qui ne contient
     * plus rien après nettoyage est écartée — une ligne vide dans une tuile est une ligne perdue.
     *
     * @param lines ce que l'écran a envoyé, éventuellement {@code null}
     * @return au plus {@link #MAX_LINES} lignes, jamais {@code null}
     */
    static List<String> lines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(lines.size());
        for (String line : lines) {
            String value = line(line);
            if (value != null) {
                cleaned.add(value);
            }
        }
        if (cleaned.size() <= MAX_LINES) {
            return List.copyOf(cleaned);
        }
        return List.copyOf(cleaned.subList(cleaned.size() - MAX_LINES, cleaned.size()));
    }

    /** Une ligne nettoyée et tronquée, ou {@code null} s'il n'en reste rien. */
    static String line(String raw) {
        String value = clean(raw, MAX_LINE_LENGTH);
        return value == null || value.isEmpty() ? null : value;
    }

    /** Le détail d'activité, nettoyé et tronqué, ou {@code null} s'il n'en reste rien. */
    static String detail(String raw) {
        String value = clean(raw, MAX_DETAIL_LENGTH);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String clean(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String value = ANSI.matcher(raw).replaceAll("");
        value = CONTROL.matcher(value).replaceAll("");
        // Les tabulations et espaces multiples d'une sortie alignée n'ont plus de sens hors d'une
        // grille monospace de largeur connue : on les ramène à un espace.
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
