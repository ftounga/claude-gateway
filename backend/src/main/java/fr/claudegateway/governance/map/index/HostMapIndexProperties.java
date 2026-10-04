package fr.claudegateway.governance.map.index;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de l'index de la carte (F-174, D4 / D6).
 *
 * <p>Un seul constructeur (le canonique) : un second casserait la liaison Spring.</p>
 *
 * @param enabled          coupe-circuit de TOUT l'index (extraction, recherche, outil) —
 *                         {@code APP_MAP_INDEX_ENABLED}, défaut {@code true}. Éteint : comportement
 *                         d'avant F-174 à l'identique (recherche lexicale F-137).
 * @param model            modèle de l'extraction sémantique — {@code APP_MAP_INDEX_MODEL}, défaut
 *                         {@code claude-sonnet-5-5}
 * @param filesPerRun      fichiers ré-indexés (couche déterministe) par passe du travailleur
 * @param sectionsPerRun   sections soumises au modèle par passe
 * @param maxAttempts      essais avant de marquer une section {@code FAILED}
 * @param maxSectionChars  borne du texte d'une section envoyé au modèle
 * @param maxOutputTokens  plafond de sortie d'une extraction
 * @param maxFacts         faits joints au tour, au plus (D6)
 * @param maxChars         caractères du bloc de faits, au plus (D6)
 * @param semanticMaxDistance distance cosine au-delà de laquelle un voisin pgvector n'est pas joint (D5)
 * @param semanticTopN     voisins sémantiques demandés
 * @param embeddingsPerRun faits embeddés par passe du travailleur (si la clé d'embedding est là)
 */
@ConfigurationProperties(prefix = "app.map-index")
public record HostMapIndexProperties(
        Boolean enabled,
        String model,
        Integer filesPerRun,
        Integer sectionsPerRun,
        Integer maxAttempts,
        Integer maxSectionChars,
        Integer maxOutputTokens,
        Integer maxFacts,
        Integer maxChars,
        Double semanticMaxDistance,
        Integer semanticTopN,
        Integer embeddingsPerRun) {

    public static final String DEFAULT_MODEL = "claude-sonnet-5-5";

    public HostMapIndexProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        filesPerRun = positive(filesPerRun, 10);
        sectionsPerRun = positive(sectionsPerRun, 6);
        maxAttempts = positive(maxAttempts, 3);
        maxSectionChars = positive(maxSectionChars, 24_000);
        maxOutputTokens = positive(maxOutputTokens, 8_000);
        maxFacts = positive(maxFacts, 20);
        maxChars = positive(maxChars, 6_000);
        if (semanticMaxDistance == null || semanticMaxDistance <= 0) {
            semanticMaxDistance = 0.50;
        }
        semanticTopN = positive(semanticTopN, 8);
        embeddingsPerRun = positive(embeddingsPerRun, 200);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    private static Integer positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
