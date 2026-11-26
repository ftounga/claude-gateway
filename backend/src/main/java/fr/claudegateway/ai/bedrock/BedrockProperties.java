package fr.claudegateway.ai.bedrock;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Le second canal d'accès à Claude, par AWS Bedrock (F-277, ADR-022).
 *
 * <p>Un seul constructeur (le canonique) : un second constructeur sur un record lié casse le démarrage
 * de Spring. Les tests passent par {@link #defaults()}.</p>
 *
 * @param mode          {@code off} (Anthropic seul), {@code auto} (Anthropic d'abord, bascule sur Bedrock) ou
 *                      {@code bedrock} (forcé) — lu par l'aiguillage (SF-277-02)
 * @param region        région AWS de l'appel ({@code eu-west-3})
 * @param baseUrl       point d'entrée ; vide ⇒ {@code https://bedrock-runtime.<region>.amazonaws.com}
 * @param modelPrefix   préfixe du profil d'inférence ({@code global.anthropic.} : prix de l'API Anthropic)
 * @param modelOverrides profils dont le nom ne suit pas la règle (ex. {@code claude-haiku-4-5})
 * @param allowedBetas  bêtas Anthropic acceptées par Bedrock ; les autres ne sont pas transmises
 * @param openMinutes   durée du disjoncteur après une panne de crédit ou de clé (SF-277-02)
 */
@ConfigurationProperties(prefix = "app.ai.bedrock")
public record BedrockProperties(
        String mode,
        String region,
        String baseUrl,
        String modelPrefix,
        Map<String, String> modelOverrides,
        List<String> allowedBetas,
        Integer openMinutes) {

    public static final String DEFAULT_REGION = "eu-west-3";
    public static final String DEFAULT_PREFIX = "global.anthropic.";
    /** Vérifiées par un vrai appel le 2026-11-26 (cadrage F-277 §2). */
    public static final List<String> DEFAULT_BETAS = List.of("context-management-2025-06-27",
            "mid-conversation-output-config-2026-07-01", "thinking-display-updates-2026-08-18");
    /** Profils dont le nom n'est pas {@code prefixe + id} (liste {@code list-inference-profiles} du 2026-11-26). */
    public static final Map<String, String> DEFAULT_OVERRIDES = Map.of(
            "claude-haiku-4-5", "global.anthropic.claude-haiku-4-5-20251001-v1:0",
            "claude-sonnet-4-5", "global.anthropic.claude-sonnet-4-5-20250929-v1:0",
            "claude-opus-4-5", "global.anthropic.claude-opus-4-5-20251101-v1:0",
            "claude-opus-4-6", "global.anthropic.claude-opus-4-6-v1");

    /** Mode de l'aiguillage (SF-277-02). */
    public enum Mode { OFF, AUTO, BEDROCK }

    public BedrockProperties {
        if (mode == null || mode.isBlank()) {
            mode = "off";
        }
        if (region == null || region.isBlank()) {
            region = DEFAULT_REGION;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://bedrock-runtime." + region + ".amazonaws.com";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (modelPrefix == null || modelPrefix.isBlank()) {
            modelPrefix = DEFAULT_PREFIX;
        }
        modelOverrides = modelOverrides == null || modelOverrides.isEmpty() ? DEFAULT_OVERRIDES
                : Map.copyOf(modelOverrides);
        allowedBetas = allowedBetas == null ? DEFAULT_BETAS
                : allowedBetas.stream().map(String::strip).filter(b -> !b.isEmpty()).toList();
        if (openMinutes == null || openMinutes < 1) {
            openMinutes = 15;
        }
    }

    /** Réglages par défaut (mode {@code off}) — tests et repli. */
    public static BedrockProperties defaults() {
        return new BedrockProperties(null, null, null, null, null, null, null);
    }

    /** Même réglage, autre mode — tests. */
    public BedrockProperties withMode(String newMode) {
        return new BedrockProperties(newMode, region, baseUrl, modelPrefix, modelOverrides, allowedBetas,
                openMinutes);
    }

    /** Le mode lu ; une valeur inconnue vaut {@code off} (jamais de bascule involontaire). */
    public Mode failoverMode() {
        try {
            return Mode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Mode.OFF;
        }
    }

    /** Profil d'inférence Bedrock d'un identifiant de modèle Anthropic. */
    public String profileFor(String model) {
        String id = model == null ? "" : model.strip();
        String override = modelOverrides.get(id);
        if (override != null && !override.isBlank()) {
            return override;
        }
        // Un identifiant déjà qualifié (profil ou ARN) passe tel quel.
        if (id.startsWith(modelPrefix) || id.startsWith("arn:") || id.contains(".anthropic.")) {
            return id;
        }
        return modelPrefix + id;
    }

    /** Durée d'ouverture du disjoncteur. */
    public Duration openDuration() {
        return Duration.ofMinutes(openMinutes);
    }
}
