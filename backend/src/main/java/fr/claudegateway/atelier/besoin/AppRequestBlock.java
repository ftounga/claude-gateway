package fr.claudegateway.atelier.besoin;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * <b>La carte « Enregistrer ce besoin ? »</b> posée dans le fil (F-205 / SF-205-01) : relayée au fil
 * de l'eau (événement {@code besoin}) et rangée dans la transcription du tour, elle survit au
 * rechargement. Son <b>statut</b> se relit à part ({@code GET …/app-requests/{id}}).
 *
 * @param requestId       le besoin à enregistrer ou écarter
 * @param kind            {@code BUG} | {@code EVOLUTION}
 * @param title           titre court
 * @param description     les mots de l'utilisateur
 * @param reformulation   la reformulation de l'agent
 * @param observedContext ce qui a été observé
 * @param attachments     les pièces jointes (métadonnées)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppRequestBlock(UUID requestId, String kind, String title, String description,
        String reformulation, String observedContext, List<Piece> attachments) {

    /**
     * Une pièce telle que la carte la montre.
     *
     * @param position    rang, à partir de 1
     * @param name        nom du fichier
     * @param contentType type déduit de l'extension
     * @param size        taille en octets
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Piece(int position, String name, String contentType, long size) {
    }
}
