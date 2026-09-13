package fr.claudegateway.radar.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les réglages de la <b>lecture</b> des échanges (F-101) : modèles et plafonds des deux passes.
 *
 * <p>Le domaine exprime des besoins — un tri rapide et économique, une extraction plus capable — et
 * non des noms de fournisseur : un modèle vide ou inconnu du catalogue retombe sur celui du catalogue.
 * Un réglage aberrant retombe sur le défaut, sans empêcher le démarrage.</p>
 *
 * @param triageModel      modèle du tri ; vide ou inconnu → le modèle <b>rapide</b> du catalogue
 * @param triageMaxTokens  plafond de sortie d'un appel de tri
 * @param triageChunkChars matière au plus par appel de tri ; un échange n'est jamais coupé
 */
@ConfigurationProperties(prefix = "app.radar.reading")
public record RadarReadingProperties(String triageModel, Integer triageMaxTokens, Integer triageChunkChars) {

    public static final int DEFAULT_TRIAGE_MAX_TOKENS = 600;
    public static final int DEFAULT_TRIAGE_CHUNK_CHARS = 60_000;

    public RadarReadingProperties {
        triageModel = triageModel == null || triageModel.isBlank() ? null : triageModel.trim();
        triageMaxTokens = triageMaxTokens == null || triageMaxTokens < 100 || triageMaxTokens > 4_000
                ? DEFAULT_TRIAGE_MAX_TOKENS : triageMaxTokens;
        triageChunkChars = triageChunkChars == null || triageChunkChars < 5_000 || triageChunkChars > 200_000
                ? DEFAULT_TRIAGE_CHUNK_CHARS : triageChunkChars;
    }

    /** Les réglages d'une installation qui n'a rien configuré. */
    public static RadarReadingProperties defaults() {
        return new RadarReadingProperties(null, null, null);
    }
}
