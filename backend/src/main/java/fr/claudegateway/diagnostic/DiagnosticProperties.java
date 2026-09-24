package fr.claudegateway.diagnostic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les réglages de la lecture raisonnée (F-157 / SF-157-04).
 *
 * <p>Les plafonds sont là pour être <b>baissés</b> si la lecture coûte trop : c'est la seule partie
 * du diagnostic qui consomme des jetons.</p>
 *
 * @param model        le modèle qui lit le code (défaut {@code claude-opus-5})
 * @param maxCodeChars plafond de code envoyé, en caractères (défaut {@code 60000}) — au-delà, le
 *                     code est tronqué <b>et le message le dit</b>
 * @param maxAnswerTokens plafond de la réponse (défaut {@code 1200}) : on demande une piste, pas un
 *                        rapport
 */
@ConfigurationProperties(prefix = "app.diagnostic")
public record DiagnosticProperties(String model, Integer maxCodeChars, Integer maxAnswerTokens) {

    static final String DEFAULT_MODEL = "claude-opus-5";
    static final int DEFAULT_MAX_CODE_CHARS = 60_000;
    static final int DEFAULT_MAX_ANSWER_TOKENS = 1_200;

    public DiagnosticProperties {
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        maxCodeChars = maxCodeChars == null || maxCodeChars <= 0
                ? DEFAULT_MAX_CODE_CHARS : maxCodeChars;
        maxAnswerTokens = maxAnswerTokens == null || maxAnswerTokens <= 0
                ? DEFAULT_MAX_ANSWER_TOKENS : maxAnswerTokens;
    }

    /** Les valeurs par défaut. */
    public static DiagnosticProperties defaults() {
        return new DiagnosticProperties(null, null, null);
    }
}
