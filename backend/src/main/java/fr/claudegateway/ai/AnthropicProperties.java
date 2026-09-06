package fr.claudegateway.ai;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du fournisseur Anthropic (mode Hosted). Toutes les valeurs sont externalisées ;
 * la clé plateforme provient exclusivement de l'environnement et n'est jamais journalisée.
 *
 * @param apiKey       clé API plateforme (env {@code ANTHROPIC_API_KEY}) — vide => fournisseur dormant (503)
 * @param baseUrl      base de l'API Anthropic
 * @param version      valeur de l'en-tête {@code anthropic-version}
 * @param defaultModel modèle utilisé lorsque la requête n'en précise pas
 * @param models       liste blanche des modèles sélectionnables
 * @param maxTokens      plafond de tokens de sortie par appel de <b>chat</b> (F-02)
 * @param agentMaxTokens plafond de tokens de sortie par appel de la <b>boucle d'agent</b> (F-28).
 *                       Distinct de {@code maxTokens} : l'agent transporte le contenu entier d'un
 *                       fichier dans sa sortie ({@code write_file}), le chat non. Les relier ferait
 *                       porter au chat, livré et stable, un besoin qui n'est pas le sien.
 * @param timeout        délai maximal d'un appel amont de <b>chat</b> (F-02)
 * @param agentTimeout   délai maximal d'un appel de la <b>boucle d'agent</b> (F-39 / SF-39-11),
 *                       défaut {@code PT5M}. Distinct de {@code timeout} : la boucle appelle en
 *                       non-streamé, en raisonnement adaptatif, avec jusqu'à
 *                       {@code agentMaxTokens} de sortie — 120 s couperait des appels légitimes,
 *                       et un appel coupé côté client reste facturé côté fournisseur. Jusqu'à
 *                       cette subfeature, <b>aucun</b> délai n'était posé : un appel sans réponse
 *                       bloquait le thread de la boucle indéfiniment
 * @param agentMaxAttempts nombre total de tentatives d'un appel de la boucle d'agent
 *                       (F-39 / SF-39-11), défaut {@code 3} — soit 2 réessais. Ne s'applique
 *                       qu'aux refus temporaires du fournisseur ({@code 429}, {@code 529})
 */
@ConfigurationProperties(prefix = "app.ai.anthropic")
public record AnthropicProperties(
        String apiKey,
        String baseUrl,
        String version,
        String defaultModel,
        List<String> models,
        Integer maxTokens,
        Integer agentMaxTokens,
        Duration timeout,
        Duration agentTimeout,
        Integer agentMaxAttempts) {

    /** Délai d'un appel de la boucle d'agent à défaut de configuration (F-39 / SF-39-11). */
    public static final Duration DEFAULT_AGENT_TIMEOUT = Duration.ofMinutes(5);
    /** Tentatives d'un appel de la boucle d'agent à défaut de configuration (F-39 / SF-39-11). */
    public static final int DEFAULT_AGENT_MAX_ATTEMPTS = 3;
    /**
     * Plafond de tentatives. Au-delà, l'attente cumulée maximale ({@code MAX_TOTAL_WAIT_MS} de
     * {@code AgentRetryPolicy}) aurait tranché de toute façon : mieux vaut une borne lisible qu'un
     * réglage qui n'a jamais l'occasion de s'appliquer.
     */
    private static final int MAX_AGENT_ATTEMPTS = 5;

    public AnthropicProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com";
        }
        if (version == null || version.isBlank()) {
            version = "2023-06-01";
        }
        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = "claude-opus-4-8";
        }
        if (models == null || models.isEmpty()) {
            models = List.of("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5");
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 4096;
        }
        // 16 384 et non 64 000 : au-delà, l'appel doit être streamé vers le fournisseur pour ne pas
        // heurter les délais HTTP, or la boucle d'agent appelle en non-streamé (SF-28-18, décision D2).
        if (agentMaxTokens == null || agentMaxTokens <= 0) {
            agentMaxTokens = 16_384;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(120);
        }
        if (agentTimeout == null || agentTimeout.isZero() || agentTimeout.isNegative()) {
            agentTimeout = DEFAULT_AGENT_TIMEOUT;
        }
        // Une valeur aberrante en configuration ne doit pas empêcher le démarrage (même règle que
        // `effort` en SF-39-10) : elle est ramenée dans les bornes, elle n'arrête rien.
        if (agentMaxAttempts == null || agentMaxAttempts < 1) {
            agentMaxAttempts = DEFAULT_AGENT_MAX_ATTEMPTS;
        }
        if (agentMaxAttempts > MAX_AGENT_ATTEMPTS) {
            agentMaxAttempts = MAX_AGENT_ATTEMPTS;
        }
    }

    /** Vrai si une clé plateforme est configurée (fournisseur réellement appelable). */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
