package fr.claudegateway.jarvis;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>La voix de Jarvis</b> (F-231 / SF-231-01, D2) : voix, ton, bornes et plafond mensuel en euros.
 *
 * <p>Le fournisseur, la clé et les tarifs sont ceux de la voix off ({@code app.speech.*}, F-215) : <b>aucun nouveau
 * secret</b>. Ici, seulement ce qui distingue Jarvis : une voix masculine posée, des phrases courtes, un plafond.</p>
 *
 * @param voice            la voix (liste blanche de {@code app.speech.voices}) ; défaut {@code onyx}, grave et posée
 * @param instructions     les consignes de ton envoyées au fournisseur
 * @param maxChars         au-delà, le texte lu est coupé à la dernière fin de phrase (défaut 600)
 * @param maxRequestChars  au-delà, la requête est refusée (défaut 4 000)
 * @param monthlyBudgetEur plafond du mois, en euros (défaut 15 ; 0 = voix coupée)
 */
@ConfigurationProperties(prefix = "app.jarvis.voice")
public record JarvisVoiceProperties(
        String voice,
        String instructions,
        Integer maxChars,
        Integer maxRequestChars,
        BigDecimal monthlyBudgetEur) {

    /** D2 : la voix par défaut — grave, posée. */
    public static final String DEFAULT_VOICE = "onyx";

    /** D2 : le ton par défaut. Texte fixe, identique pour tous. */
    public static final String DEFAULT_INSTRUCTIONS = "Assistant personnel français, voix masculine posée, calme et "
            + "assurée, débit naturel, phrases nettes, sans emphase.";

    public JarvisVoiceProperties {
        voice = voice == null || voice.isBlank() ? DEFAULT_VOICE : voice.strip().toLowerCase(Locale.ROOT);
        instructions = instructions == null || instructions.isBlank() ? DEFAULT_INSTRUCTIONS : instructions.strip();
        if (maxChars == null || maxChars <= 0) {
            maxChars = 600;
        }
        if (maxRequestChars == null || maxRequestChars <= 0) {
            maxRequestChars = 4_000;
        }
        if (monthlyBudgetEur == null || monthlyBudgetEur.signum() < 0) {
            monthlyBudgetEur = new BigDecimal("15");
        }
    }
}
