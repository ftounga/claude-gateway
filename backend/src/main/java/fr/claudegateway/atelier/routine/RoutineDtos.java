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
     * @param lastRun   dernière exécution (SF-183-02), ou {@code null}
     */
    public record RoutineView(UUID id, UUID workspaceId, String name, String instruction, String cadence,
            Integer dayOfWeek, String runTime, String timeZone, BigDecimal maxRunUsd, boolean active,
            boolean proposed, String origin, OffsetDateTime nextRunAt, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, RoutineRunView lastRun) {

        static RoutineView of(SubjectRoutine routine, OffsetDateTime now) {
            return of(routine, now, null);
        }

        static RoutineView of(SubjectRoutine routine, OffsetDateTime now, RoutineRunView lastRun) {
            OffsetDateTime next = routine.isScheduled()
                    ? RoutineSlots.nextSlot(routine, now).orElse(null)
                    : null;
            return new RoutineView(routine.getId(), routine.getWorkspaceId(), routine.getName(),
                    routine.getInstruction(), routine.getCadence().name(), routine.getDayOfWeek(),
                    routine.getRunTime(), routine.getTimeZone(), routine.getMaxRunUsd(), routine.isActive(),
                    routine.isProposed(), routine.getOrigin().name(), next, routine.getCreatedAt(),
                    routine.getUpdatedAt(), lastRun);
        }
    }

    /**
     * Une exécution de routine (F-183 / SF-183-02) : des instants, un statut, un coût — aucun contenu.
     *
     * @param trigger {@code SCHEDULED} | {@code CATCH_UP} | {@code MANUAL}
     * @param status  {@code RUNNING} | {@code DONE} | {@code INCOMPLETE} | {@code FAILED} |
     *                {@code INTERRUPTED} | {@code MISSED_OFFLINE} | {@code SKIPPED_BUDGET}
     */
    public record RoutineRunView(UUID id, UUID routineId, OffsetDateTime slotAt, String trigger, String status,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, BigDecimal costUsd, BigDecimal capUsd,
            String errorCode, String summary) {

        static RoutineRunView of(RoutineRun run) {
            return new RoutineRunView(run.getId(), run.getRoutineId(), run.getSlotAt(), run.getTrigger().name(),
                    run.getStatus().name(), run.getStartedAt(), run.getFinishedAt(), run.getCostUsd(),
                    run.getCapUsd(), run.getErrorCode(), run.getSummary());
        }
    }
}
