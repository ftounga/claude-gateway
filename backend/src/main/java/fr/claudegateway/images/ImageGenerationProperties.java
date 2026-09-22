package fr.claudegateway.images;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du <b>fournisseur d'images</b> relayé (F-142 / SF-142-04), sur le modèle STT/RAG :
 * base-url + model par configuration, <b>clé par secret d'environnement</b>.
 *
 * <p><b>Éteint par défaut (DRAPEAU FORT).</b> Tant que {@link #baseUrl} <b>et</b> {@link #apiKey} ne
 * sont pas fournis par l'environnement (jamais commités, jamais journalisés), {@link #isConfigured()}
 * est faux et <b>aucun octet ne part</b> : l'outil répond « génération d'images non configurée ». La clé
 * vient <b>exclusivement</b> de l'environnement ({@code APP_IMAGE_API_KEY}, secret backend), jamais du
 * repo ni des logs. C'est une décision explicite du PO d'ouvrir le tuyau (opt-in par configuration).</p>
 */
@ConfigurationProperties(prefix = "app.image")
public record ImageGenerationProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        Long maxImageBytes,
        Integer maxPromptChars,
        Integer maxPerTurn,
        Integer maxAccountImages,
        BigDecimal costEurPerImage) {

    /** Taille maximale d'une image rangée (8 Mo — aligné sur la borne d'une page F-109). */
    public static final long DEFAULT_MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    public ImageGenerationProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (model == null || model.isBlank()) {
            model = "gpt-image-1";
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(60);
        }
        if (maxImageBytes == null || maxImageBytes <= 0) {
            maxImageBytes = DEFAULT_MAX_IMAGE_BYTES;
        }
        if (maxPromptChars == null || maxPromptChars <= 0) {
            maxPromptChars = 1000;
        }
        if (maxPerTurn == null || maxPerTurn <= 0) {
            maxPerTurn = 3;
        }
        if (maxAccountImages == null || maxAccountImages <= 0) {
            maxAccountImages = 500;
        }
        if (costEurPerImage == null || costEurPerImage.signum() < 0) {
            costEurPerImage = new BigDecimal("0.04");
        }
    }

    /**
     * Vrai si le fournisseur est réellement appelable : une base URL <b>et</b> une clé sont fournies.
     * Faux par défaut ⇒ génération éteinte, aucune donnée ne sort.
     */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
