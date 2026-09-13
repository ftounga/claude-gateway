package fr.claudegateway.radar.dto;

import java.time.LocalDate;

import fr.claudegateway.radar.RadarCorrectionAction;
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

    /** Correction d'un engagement : {@code dueDate} n'est lue que pour {@code POSTPONE}. */
    public record CommitmentCorrectionRequest(@NotNull RadarCorrectionAction action, LocalDate dueDate) {
    }
}
