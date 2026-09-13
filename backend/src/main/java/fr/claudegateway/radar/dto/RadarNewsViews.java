package fr.claudegateway.radar.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.radar.RadarEvidenceSource;

/** Corps et réponses de <i>Donner la nouvelle</i> (F-104 / SF-104-02). */
public final class RadarNewsViews {

    private RadarNewsViews() {
    }

    /** Une nouvelle : du texte libre, ou un courriel collé. */
    public record NewsRequest(String text) {
    }

    /** Ce qu'une nouvelle a écrit — dit par la gateway, jamais par le modèle. */
    public record NewsChangeView(String kind, UUID subjectId, String subjectName, UUID correctionId, String sentence) {
    }

    /** L'en-tête d'un courriel collé, tel qu'il a été lu. */
    public record PastedMailView(String sender, OffsetDateTime sentAt, String subject, boolean datedFromMail) {
    }

    /**
     * La réponse du Radar à une nouvelle.
     *
     * @param understanding ce que le Radar a compris (« Je note : … »)
     * @param changes       ce qui a été écrit, annulable
     * @param evidenceId    la preuve rangée, s'il y a eu écriture ; c'est elle qu'on annule
     * @param source        note ou courriel collé
     * @param mail          l'en-tête lu, pour un courriel collé
     * @param stoppedEarly  le tour s'est arrêté avant sa fin (bornes, fournisseur en échec)
     */
    public record NewsView(String understanding, List<NewsChangeView> changes, UUID evidenceId,
            RadarEvidenceSource source, PastedMailView mail, boolean stoppedEarly) {
    }

    /** Une nouvelle annulée : le nombre de corrections défaites. */
    public record NewsUndoView(UUID evidenceId, int undone) {
    }
}
