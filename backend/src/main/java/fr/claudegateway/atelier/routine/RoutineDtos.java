package fr.claudegateway.atelier.routine;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Les formes échangées par l'API des routines (F-183). Requête et réponse distinctes. */
public final class RoutineDtos {

    private RoutineDtos() {
    }

    /**
     * Déclarer ou modifier une routine (F-183 / SF-183-01).
     *
     * @param name        nom court, 1..80
     * @param instruction la consigne, 1..4000 — envoyée comme demande du tour
     * @param cadence     {@code DAILY} | {@code WEEKDAYS} | {@code WEEKLY}
     * @param dayOfWeek   1 = lundi … 7 = dimanche, requis pour {@code WEEKLY}
     * @param runTime     {@code HH:mm}
     * @param timeZone    fuseau IANA du poste ({@code Europe/Paris} si absent)
     * @param maxRunUsd   budget par exécution, 0,10 … 50,00 $ (5,00 $ si absent)
     * @param active      programmée ou en pause ({@code true} si absent)
     */
    public record RoutineRequest(String name, String instruction, String cadence, Integer dayOfWeek,
            String runTime, String timeZone, BigDecimal maxRunUsd, Boolean active) {
    }

    /**
     * Une routine, telle que l'écran la montre.
     *
     * @param nextRunAt prochain créneau ({@code null} si en pause ou en proposition)
     */
    public record RoutineView(UUID id, UUID workspaceId, String name, String instruction, String cadence,
            Integer dayOfWeek, String runTime, String timeZone, BigDecimal maxRunUsd, boolean active,
            boolean proposed, String origin, OffsetDateTime nextRunAt, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static RoutineView of(SubjectRoutine routine, OffsetDateTime now) {
            OffsetDateTime next = routine.isScheduled()
                    ? RoutineSlots.nextSlot(routine, now).orElse(null)
                    : null;
            return new RoutineView(routine.getId(), routine.getWorkspaceId(), routine.getName(),
                    routine.getInstruction(), routine.getCadence().name(), routine.getDayOfWeek(),
                    routine.getRunTime(), routine.getTimeZone(), routine.getMaxRunUsd(), routine.isActive(),
                    routine.isProposed(), routine.getOrigin().name(), next, routine.getCreatedAt(),
                    routine.getUpdatedAt());
        }
    }
}
