package fr.claudegateway.diagrams;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages du rendu de diagrammes (F-142 / SF-142-06). Sans {@code base-url}, l'outil n'est pas
 * proposé : mieux vaut ne rien promettre que promettre et échouer.
 */
@ConfigurationProperties(prefix = "app.diagrams")
public class DiagramProperties {

    /** L'adresse du service de rendu, dans le cluster. Vide ⇒ fonctionnalité éteinte. */
    private String baseUrl = "";
    /** Borne de l'appel : un rendu dépasse rarement quelques secondes. */
    private Duration timeout = Duration.ofSeconds(30);
    /** Borne du code accepté — la même que celle du service, pour un refus cohérent des deux côtés. */
    private int maxCodeChars = 20_000;
    /** Borne de l'image rendue (8 Mio, aligné sur les images et les pages). */
    private int maxImageBytes = 8 * 1024 * 1024;
    /** Largeur de rendu par défaut. */
    private int defaultWidth = 1600;
    /** Nombre maximal de diagrammes rendus par tour. */
    private int maxPerTurn = 6;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxCodeChars() {
        return maxCodeChars;
    }

    public void setMaxCodeChars(int maxCodeChars) {
        this.maxCodeChars = maxCodeChars;
    }

    public int getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(int maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public int getDefaultWidth() {
        return defaultWidth;
    }

    public void setDefaultWidth(int defaultWidth) {
        this.defaultWidth = defaultWidth;
    }

    public int getMaxPerTurn() {
        return maxPerTurn;
    }

    public void setMaxPerTurn(int maxPerTurn) {
        this.maxPerTurn = maxPerTurn;
    }
}
