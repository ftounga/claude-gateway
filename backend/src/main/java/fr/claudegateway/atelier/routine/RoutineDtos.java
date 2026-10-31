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
     * @param monthlyBudgetUsd budget mensuel, 1 … 2 000 $ (100 $ si absent) — SF-183-03
     * @param maxRunEur   budget par exécution SAISI EN EUROS, 0,10 … 50,00 € (F-224 / SF-224-06) ; prime sur
     *                    {@code maxRunUsd}, converti en dollars au taux unique de la passerelle
     * @param monthlyBudgetEur budget mensuel saisi en euros, 1 … 2 000 € (F-224 / SF-224-06) ; prime sur
     *                    {@code monthlyBudgetUsd}
     */
    public record RoutineRequest(String name, String instruction, String cadence, Integer dayOfWeek,
            String runTime, String timeZone, BigDecimal maxRunUsd, Boolean active, BigDecimal monthlyBudgetUsd,
            BigDecimal maxRunEur, BigDecimal monthlyBudgetEur) {

        /** Forme en dollars (SF-183-03), conservée pour les appelants. */
        public RoutineRequest(String name, String instruction, String cadence, Integer dayOfWeek,
                String runTime, String timeZone, BigDecimal maxRunUsd, Boolean active, BigDecimal monthlyBudgetUsd) {
            this(name, instruction, cadence, dayOfWeek, runTime, timeZone, maxRunUsd, active, monthlyBudgetUsd,
                    null, null);
        }

        /** Forme sans budget mensuel (SF-183-01), conservée pour les appelants. */
        public RoutineRequest(String name, String instruction, String cadence, Integer dayOfWeek,
                String runTime, String timeZone, BigDecimal maxRunUsd, Boolean active) {
            this(name, instruction, cadence, dayOfWeek, runTime, timeZone, maxRunUsd, active, null, null, null);
        }
    }

    /**
     * Une routine, telle que l'écran la montre.
     *
     * @param nextRunAt prochain créneau ({@code null} si en pause ou en proposition)
     * @param lastRun   dernière exécution (SF-183-02), ou {@code null}
     * @param monthlyBudgetUsd budget du mois (SF-183-03)
     * @param monthSpentUsd    dépensé depuis le 1er du mois, fuseau de la routine (SF-183-03)
     * @param maxRunEur        le budget par exécution en euros, au taux unique (F-224 / SF-224-06)
     * @param monthlyBudgetEur le budget du mois en euros
     * @param monthSpentEur    le dépensé du mois en euros
     */
    public record RoutineView(UUID id, UUID workspaceId, String name, String instruction, String cadence,
            Integer dayOfWeek, String runTime, String timeZone, BigDecimal maxRunUsd, boolean active,
            boolean proposed, String origin, OffsetDateTime nextRunAt, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, RoutineRunView lastRun, BigDecimal monthlyBudgetUsd,
            BigDecimal monthSpentUsd, BigDecimal maxRunEur, BigDecimal monthlyBudgetEur, BigDecimal monthSpentEur) {

        /** Forme sans euros (SF-183-03), conservée pour les appelants. */
        public RoutineView(UUID id, UUID workspaceId, String name, String instruction, String cadence,
                Integer dayOfWeek, String runTime, String timeZone, BigDecimal maxRunUsd, boolean active,
                boolean proposed, String origin, OffsetDateTime nextRunAt, OffsetDateTime createdAt,
                OffsetDateTime updatedAt, RoutineRunView lastRun, BigDecimal monthlyBudgetUsd,
                BigDecimal monthSpentUsd) {
            this(id, workspaceId, name, instruction, cadence, dayOfWeek, runTime, timeZone, maxRunUsd, active,
                    proposed, origin, nextRunAt, createdAt, updatedAt, lastRun, monthlyBudgetUsd, monthSpentUsd,
                    null, null, null);
        }

        /** Forme sans budget mensuel (SF-183-02), conservée pour les appelants. */
        public RoutineView(UUID id, UUID workspaceId, String name, String instruction, String cadence,
                Integer dayOfWeek, String runTime, String timeZone, BigDecimal maxRunUsd, boolean active,
                boolean proposed, String origin, OffsetDateTime nextRunAt, OffsetDateTime createdAt,
                OffsetDateTime updatedAt, RoutineRunView lastRun) {
            this(id, workspaceId, name, instruction, cadence, dayOfWeek, runTime, timeZone, maxRunUsd, active,
                    proposed, origin, nextRunAt, createdAt, updatedAt, lastRun, null, null, null, null, null);
        }

        static RoutineView of(SubjectRoutine routine, OffsetDateTime now, BigDecimal usdToEur) {
            return of(routine, now, null, BigDecimal.ZERO, usdToEur);
        }

        static RoutineView of(SubjectRoutine routine, OffsetDateTime now, RoutineRunView lastRun,
                BigDecimal monthSpentUsd, BigDecimal usdToEur) {
            BigDecimal spent = monthSpentUsd == null ? BigDecimal.ZERO : monthSpentUsd;
            OffsetDateTime next = routine.isScheduled()
                    ? RoutineSlots.nextSlot(routine, now).orElse(null)
                    : null;
            return new RoutineView(routine.getId(), routine.getWorkspaceId(), routine.getName(),
                    routine.getInstruction(), routine.getCadence().name(), routine.getDayOfWeek(),
                    routine.getRunTime(), routine.getTimeZone(), routine.getMaxRunUsd(), routine.isActive(),
                    routine.isProposed(), routine.getOrigin().name(), next, routine.getCreatedAt(),
                    routine.getUpdatedAt(), lastRun, routine.getMonthlyBudgetUsd(), spent,
                    eur(routine.getMaxRunUsd(), usdToEur), eur(routine.getMonthlyBudgetUsd(), usdToEur),
                    eur(spent, usdToEur));
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
            String errorCode, String summary, Integer attentesUpdated, String routineName,
            OffsetDateTime acknowledgedAt, BigDecimal costEur, BigDecimal capEur) {

        static RoutineRunView of(RoutineRun run, BigDecimal usdToEur) {
            return of(run, null, usdToEur);
        }

        /** Avec le nom de la routine (SF-183-04 : la bande en tête du fil) ; coût et plafond aussi en euros (SF-224-06). */
        static RoutineRunView of(RoutineRun run, String routineName, BigDecimal usdToEur) {
            return new RoutineRunView(run.getId(), run.getRoutineId(), run.getSlotAt(), run.getTrigger().name(),
                    run.getStatus().name(), run.getStartedAt(), run.getFinishedAt(), run.getCostUsd(),
                    run.getCapUsd(), run.getErrorCode(), run.getSummary(), run.getAttentesUpdated(), routineName,
                    run.getAcknowledgedAt(), eur(run.getCostUsd(), usdToEur), eur(run.getCapUsd(), usdToEur));
        }
    }

    /** Taux par défaut quand la grille n'est pas branchée (formes historiques, tests) : celui de la configuration. */
    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");

    /**
     * Un montant en dollars, en euros au centime ({@code HALF_UP}) — la même règle que {@code
     * ProviderPricingProperties#eur} ; {@code null} reste {@code null}.
     */
    static BigDecimal eur(BigDecimal usd, BigDecimal usdToEur) {
        if (usd == null) {
            return null;
        }
        BigDecimal rate = usdToEur == null || usdToEur.signum() <= 0 ? DEFAULT_USD_TO_EUR : usdToEur;
        return usd.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
