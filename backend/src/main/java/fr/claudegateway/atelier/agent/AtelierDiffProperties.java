package fr.claudegateway.atelier.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bornes du <b>diff des modifications</b> d'un tour d'exécution (F-37 / SF-37-01). Valeurs
 * d'affichage réversibles, ajustables par environnement sans changement de code — jamais un secret.
 *
 * @param maxLines borne du diff <b>par fichier</b>, en lignes (défaut {@code 400}, décision D3 du
 *                 cadrage F-37). Au-delà, le diff est tronqué sur une frontière de ligne et le volume
 *                 omis est mentionné : un fichier généré entièrement réécrit produirait des milliers
 *                 de lignes que personne ne lit
 * @param maxFiles nombre maximal de fichiers portant un diff dans un tour (défaut {@code 50}). Les
 *                 fichiers au-delà restent réécrits et listés dans les fichiers modifiés, sans diff :
 *                 le cadrage ne borne que le par-fichier, mais un tour qui en réécrit trois cents
 *                 ferait gonfler sans limite le document du tour, qui porte déjà la transcription
 */
@ConfigurationProperties(prefix = "app.atelier.agent.diff")
public record AtelierDiffProperties(Integer maxLines, Integer maxFiles) {

    public AtelierDiffProperties {
        if (maxLines == null || maxLines <= 0) {
            maxLines = 400;
        }
        if (maxFiles == null || maxFiles <= 0) {
            maxFiles = 50;
        }
    }
}
