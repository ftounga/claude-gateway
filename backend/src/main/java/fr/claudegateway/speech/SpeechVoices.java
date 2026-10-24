package fr.claudegateway.speech;

import java.util.List;
import java.util.Map;

/**
 * Les réglages de voix <b>par défaut</b> (F-215, arbitrage PO D2) et les libellés lisibles des voix.
 *
 * <p>Texte fixe : rien ici ne dépend de l'utilisateur ni du tour (préfixe stable).</p>
 */
public final class SpeechVoices {

    /** D2 : la voix par défaut. */
    public static final String DEFAULT_VOICE = "onyx";

    /** D2 : les consignes de ton par défaut. */
    public static final String DEFAULT_INSTRUCTIONS =
            "Formateur technique français, voix posée, claire et chaleureuse, rythme pédagogique.";

    /** Les voix des auditions quand l'agent n'en précise pas (deux graves, deux claires). */
    public static final List<String> DEFAULT_AUDITIONS = List.of("onyx", "ash", "nova", "coral");

    /** L'échantillon fixe des Paramètres : la même phrase pour toutes les voix. */
    public static final String SAMPLE_TEXT = "Bonjour ! Dans ce cours, nous allons voir pas à pas comment "
            + "les éléments de votre architecture communiquent entre eux.";

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("alloy", "Alloy — neutre, équilibrée"),
            Map.entry("ash", "Ash — grave, assurée"),
            Map.entry("ballad", "Ballad — douce, expressive"),
            Map.entry("coral", "Coral — claire, chaleureuse"),
            Map.entry("echo", "Echo — posée, sobre"),
            Map.entry("fable", "Fable — narrative, vive"),
            Map.entry("nova", "Nova — claire, dynamique"),
            Map.entry("onyx", "Onyx — grave, posée (par défaut)"),
            Map.entry("sage", "Sage — calme, réfléchie"),
            Map.entry("shimmer", "Shimmer — légère, lumineuse"),
            Map.entry("verse", "Verse — expressive, rythmée"));

    private SpeechVoices() {
    }

    /** Le libellé lisible d'une voix ; le nom brut si elle n'est pas connue ici. */
    public static String label(String voice) {
        return LABELS.getOrDefault(voice, voice);
    }
}
