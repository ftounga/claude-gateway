package fr.claudegateway.atelier.recall;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de la <b>recherche sémantique du {@code recall}</b> (F-162 / SF-162-06), sur le modèle
 * STT/RAG/image : base-url + modèle + dimension par configuration, <b>clé par secret d'environnement</b>
 * ({@code APP_EMBEDDING_API_KEY}). Elle est <b>distincte</b> de la config RAG documentaire
 * ({@code app.rag.embedding.*}) pour que le rappel de conversation s'active indépendamment.
 *
 * <p><b>Coupe-circuit + auto-désactivation.</b> Le sémantique n'est réellement appelable que si
 * {@link #enabled} est vrai <b>et</b> qu'une {@link #apiKey} est fournie ({@link #isConfigured()}).
 * Sans clé, {@code recall} retombe sur la recherche mot-clé de SF-162-01 — jamais d'échec.</p>
 *
 * <p><b>Piège des deux constructeurs évité</b> (mémoire projet) : ce record n'a qu'un <b>constructeur
 * compact unique</b> qui pose les valeurs par défaut. Aucun second constructeur — le contexte Spring
 * démarre sainement, comme {@code ImageGenerationProperties} et {@code EmbeddingProperties}.</p>
 */
@ConfigurationProperties(prefix = "app.atelier.recall.semantic")
public record RecallSemanticProperties(
        Boolean enabled,
        String apiKey,
        String baseUrl,
        String model,
        Integer dimension,
        Integer topN,
        Duration timeout,
        Backfill backfill) {

    /** Dimension par défaut : {@code text-embedding-3-small} = 1536, alignée sur {@code vector(1536)}. */
    public static final int DEFAULT_DIMENSION = 1536;

    /** Longueur max du contenu envoyé à l'embedding : au-delà, on tronque (borne d'entrée du modèle). */
    public static final int MAX_EMBED_CHARS = 8_000;

    public RecallSemanticProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE; // Master ON ; l'activation réelle exige quand même la clé.
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (model == null || model.isBlank()) {
            model = "text-embedding-3-small";
        }
        if (dimension == null || dimension <= 0) {
            dimension = DEFAULT_DIMENSION;
        }
        if (topN == null || topN <= 0) {
            topN = 5;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(60);
        }
        if (backfill == null) {
            backfill = new Backfill(null, null, null, null);
        }
    }

    /**
     * Vrai si le sémantique est réellement appelable : coupe-circuit armé <b>et</b> clé fournie. Faux par
     * défaut (pas de clé) ⇒ recherche mot-clé, aucune donnée ne sort.
     */
    public boolean isConfigured() {
        return Boolean.TRUE.equals(enabled) && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Réglages du backfill des messages existants (F-162 / SF-162-06). Record imbriqué à constructeur
     * compact unique lui aussi (même prudence).
     *
     * @param enabled   worker planifié actif (défaut vrai ; les tests le coupent explicitement)
     * @param batchSize taille d'un lot embeddé en un appel groupé
     * @param maxPerRun plafond dur de messages embeddés par passe planifiée
     * @param interval  délai fixe entre deux passes
     */
    public record Backfill(Boolean enabled, Integer batchSize, Integer maxPerRun, Duration interval) {
        public Backfill {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (batchSize == null || batchSize <= 0) {
                batchSize = 50;
            }
            if (maxPerRun == null || maxPerRun <= 0) {
                maxPerRun = 500;
            }
            if (interval == null || interval.isZero() || interval.isNegative()) {
                interval = Duration.ofSeconds(30);
            }
        }
    }
}
