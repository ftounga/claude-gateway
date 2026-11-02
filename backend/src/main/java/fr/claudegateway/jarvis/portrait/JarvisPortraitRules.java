package fr.claudegateway.jarvis.portrait;

import java.time.LocalDate;

/**
 * <b>Les bornes du portrait</b> (F-241 / SF-241-01, D2-D4) — fonctions pures, sans base ni modèle.
 *
 * <ul>
 *   <li>D4 : ≤ {@value #MAX_TOKENS} jetons au total, soit ≈ {@value #MAX_CHARS} caractères (jetons ≈ caractères / 4)
 *   pour portrait, souvenirs et dates réunis ;</li>
 *   <li>D2 : {@value #MAX_SOUVENIRS} souvenirs au plus, {@value #MAX_SOUVENIR_CHARS} caractères chacun ;</li>
 *   <li>D3 : « à revoir » au-delà de {@value #STALE_MONTHS} mois.</li>
 * </ul>
 */
public final class JarvisPortraitRules {

    public static final int MAX_TOKENS = 1_500;
    public static final int CHARS_PER_TOKEN = 4;
    public static final int MAX_CHARS = MAX_TOKENS * CHARS_PER_TOKEN;
    public static final int MAX_SOUVENIRS = 30;
    public static final int MAX_SOUVENIR_CHARS = 200;
    public static final int STALE_MONTHS = 12;

    /** D2 — ce que Jarvis répond au 31ᵉ souvenir (et le message du 409). */
    public static final String MEMORY_FULL = "Ma mémoire est pleine, faites le tri dans les réglages.";

    private JarvisPortraitRules() {
    }

    /** Les jetons estimés d'un nombre de caractères (arrondi au supérieur). */
    public static int tokens(int chars) {
        return (Math.max(0, chars) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** Vrai si le total dépasse la borne D4. */
    public static boolean tooLarge(int chars) {
        return chars > MAX_CHARS;
    }

    /** Le message du 400 : ce qui dépasse, et quoi raccourcir. */
    public static String tooLargeMessage(int chars) {
        return "Le portrait dépasse " + MAX_TOKENS + " jetons (≈ " + tokens(chars) + ") : raccourcissez une section "
                + "ou effacez des souvenirs (" + chars + " caractères pour " + MAX_CHARS + " au plus).";
    }

    /** D3 — vrai si l'élément daté a plus de {@value #STALE_MONTHS} mois au jour {@code today}. */
    public static boolean stale(LocalDate date, LocalDate today) {
        return date != null && today != null && date.plusMonths(STALE_MONTHS).isBefore(today);
    }

    /** Un texte de souvenir normalisé (bords retirés, espaces repliés) ; {@code null} ⇒ vide. */
    public static String normalizeSouvenir(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    /** Un texte de section normalisé (bords retirés) ; {@code null} ⇒ vide. */
    public static String normalizeSection(String text) {
        return text == null ? "" : text.strip();
    }
}
