package fr.claudegateway.runner.host;

import java.util.Optional;

/**
 * Comparaison de deux versions de runner (F-81 / SF-81-03).
 *
 * <p>Volontairement <b>minimale</b> : trois nombres séparés par des points, et tout ce qui suit un
 * {@code -} (le {@code -SNAPSHOT} de Maven) est ignoré. Ce n'est pas un analyseur SemVer complet, et
 * il ne doit pas le devenir : la seule question posée est « ce runner est-il plus ancien que celui
 * que je distribue ? », et une réponse indécidable vaut mieux qu'une réponse inventée.</p>
 *
 * <p><b>Une version illisible n'est pas une erreur.</b> Un runner recompilé à la main peut déclarer
 * n'importe quoi. On le retient et on l'affiche — c'est une information sur ce qui tourne réellement
 * — mais on ne le compare pas, et on ne le juge pas.</p>
 */
public final class RunnerVersions {

    private RunnerVersions() {
    }

    /**
     * Vrai si {@code declared} est <b>strictement antérieure</b> à {@code reference}.
     *
     * <p>Faux dès que la comparaison est impossible : version absente, illisible, ou référence
     * inconnue. Le doute ne produit jamais de ligne de journal — une alerte fausse coûte plus cher
     * que l'absence d'alerte, parce qu'elle apprend à ne plus les lire.</p>
     */
    public static boolean isOlder(String declared, String reference) {
        Optional<int[]> gauche = parse(declared);
        Optional<int[]> droite = parse(reference);
        if (gauche.isEmpty() || droite.isEmpty()) {
            return false;
        }
        int[] a = gauche.get();
        int[] b = droite.get();
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) {
                return ai < bi;
            }
        }
        return false;
    }

    /** Les segments numériques d'une version, ou vide si elle n'en est pas une. */
    static Optional<int[]> parse(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        String noyau = version.trim();
        int qualificatif = noyau.indexOf('-');
        if (qualificatif >= 0) {
            noyau = noyau.substring(0, qualificatif);
        }
        String[] segments = noyau.split("\\.");
        if (segments.length == 0) {
            return Optional.empty();
        }
        int[] nombres = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            try {
                nombres[i] = Integer.parseInt(segments[i]);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            if (nombres[i] < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(nombres);
    }
}
