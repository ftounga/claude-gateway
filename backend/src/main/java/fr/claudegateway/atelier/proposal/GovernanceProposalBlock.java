package fr.claudegateway.atelier.proposal;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * <b>La carte de proposition</b> posée dans le fil (F-177 / SF-177-02) : ce que l'agent propose
 * d'écrire, où, pourquoi, et le diff. Relayée au fil de l'eau (événement {@code proposal}) et rangée
 * dans la transcription du tour : la carte [Appliquer] [Modifier] [Refuser] survit au rechargement.
 * Son <b>statut</b> se relit à part ({@code GET …/governance-proposals/{id}}) : il change après le tour.
 *
 * @param proposalId   la proposition à appliquer ou refuser
 * @param type         {@code REGLE} | {@code SKILL} | {@code GABARIT}
 * @param scope        {@code POSTE} | {@code SUJET}
 * @param name         nom de la règle / du skill / du gabarit
 * @param path         fichier visé, relatif à la racine du poste ou du sujet
 * @param reason       pourquoi l'agent le propose
 * @param creates      vrai si le fichier n'existe pas encore
 * @param diff         lignes du diff (bornées)
 * @param omittedLines lignes de diff non montrées au-delà de la borne
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GovernanceProposalBlock(UUID proposalId, String type, String scope, String name, String path,
        String reason, boolean creates, List<DiffLine> diff, int omittedLines) {

    /**
     * Une ligne de diff.
     *
     * @param kind {@code ADD} | {@code DEL} | {@code CTX}
     * @param text la ligne, sans marqueur
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiffLine(String kind, String text) {
    }
}
