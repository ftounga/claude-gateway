package fr.claudegateway.radar.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.radar.RadarCorrectionAction;
import fr.claudegateway.radar.RadarPurgeReason;
import fr.claudegateway.radar.RadarSubjectState;
import jakarta.validation.constraints.NotNull;

/** Corps des corrections de l'utilisateur (F-99 / SF-99-02). */
public final class RadarCorrectionRequests {

    private RadarCorrectionRequests() {
    }

    /** Correction d'un sujet : seul le champ de l'action est lu. */
    public record SubjectCorrectionRequest(@NotNull RadarCorrectionAction action, String name,
            RadarSubjectState state, String nextStep, LocalDate dueDate) {
    }

    /** Fusion : la cible (SF-99-03). */
    public record MergeRequest(UUID intoSubjectId) {
    }

    /** Séparation : le nom du nouveau sujet, les preuves et engagements qui partent (SF-99-03). */
    public record SplitRequest(String name, List<UUID> evidenceIds, List<UUID> commitmentIds) {
    }

    /** Un alias dit par l'utilisateur (SF-99-03). */
    public record AliasRequest(String alias) {
    }

    /** Purge du Radar d'un poste : la raison et une confirmation explicite (SF-99-05). */
    public record PurgeRequest(RadarPurgeReason reason, Boolean confirm) {
    }

    /** Correction d'un engagement : {@code dueDate} n'est lue que pour {@code POSTPONE}. */
    public record CommitmentCorrectionRequest(@NotNull RadarCorrectionAction action, LocalDate dueDate) {
    }
}
