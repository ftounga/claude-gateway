package fr.claudegateway.atelier.veille;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Le contrat des veilles (F-230) — additif d'une SF à l'autre. */
public final class JarvisWatchDtos {

    /** Les jours d'une veille : imposés (D1). */
    public static final String DAYS = "MONDAY_TO_FRIDAY";

    private JarvisWatchDtos() {
    }

    /** Ce que l'agent demande avec {@code veille_poser} (SF-230-01). */
    public record ProposeRequest(String name, String platform, String scope, String command, String scriptPath,
            List<String> runTimes, Integer chantierNumber, String usage) {

        /** Une veille d'alerte (F-230), sans usage précisé. */
        public ProposeRequest(String name, String platform, String scope, String command, String scriptPath,
                List<String> runTimes, Integer chantierNumber) {
            this(name, platform, scope, command, scriptPath, runTimes, chantierNumber, null);
        }

        /** F-247 / SF-247-08 — {@code usage: etat} : un relevé d'état (sans alerte ni IA). */
        public boolean state() {
            return usage != null && "etat".equalsIgnoreCase(usage.strip().replace("é", "e"));
        }
    }

    /** Une veille, telle que l'écran la lit. */
    public record JarvisWatchView(UUID id, UUID workspaceId, Integer chantierNumber, String name, String platform,
            String scope, String command, String scriptPath, List<String> runTimes, String timeZone, String days,
            boolean active, boolean proposed, String origin, OffsetDateTime testedAt, Integer testItemCount,
            String testSummary, OffsetDateTime nextRunAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            OffsetDateTime lastRunAt, String lastRunStatus, boolean broken, int consecutiveFailures,
            BigDecimal monthlyBudgetEur, BigDecimal monthSpentEur, String purpose) {

        static JarvisWatchView of(JarvisWatch watch, OffsetDateTime now) {
            return of(watch, now, DEFAULT_USD_TO_EUR, null);
        }

        /** SF-230-03 : budget et dépense du mois, en euros (F-224) — {@code spentUsd} nul si inconnu. */
        static JarvisWatchView of(JarvisWatch watch, OffsetDateTime now, BigDecimal usdToEur, BigDecimal spentUsd) {
            BigDecimal rate = usdToEur == null ? DEFAULT_USD_TO_EUR : usdToEur;
            BigDecimal budget = watch.getMonthlyBudgetUsd() == null ? JarvisWatchService.DEFAULT_MONTHLY_BUDGET_USD
                    : watch.getMonthlyBudgetUsd();
            return new JarvisWatchView(watch.getId(), watch.getWorkspaceId(), watch.getChantierNumber(),
                    watch.getName(), watch.getPlatform(), watch.getScope(), watch.getProbeCommand(),
                    watch.getProbeScriptPath(), JarvisWatchSlots.times(watch.getRunTimes()), watch.getTimeZone(), DAYS,
                    watch.isActive(), watch.isProposed(), watch.getOrigin(), watch.getTestedAt(),
                    watch.getTestItemCount(), watch.getTestSummary(),
                    watch.isScheduled() ? JarvisWatchSlots.nextSlot(watch, now).orElse(null) : null,
                    watch.getCreatedAt(), watch.getUpdatedAt(), watch.getLastRunAt(), watch.getLastRunStatus(),
                    watch.isBroken(), watch.getConsecutiveFailures(),
                    budget.multiply(rate).setScale(2, RoundingMode.HALF_UP),
                    spentUsd == null ? null : spentUsd.multiply(rate).setScale(2, RoundingMode.HALF_UP),
                    watch.getPurpose() == null ? JarvisWatch.PURPOSE_ALERT : watch.getPurpose());
        }
    }

    /** Tenir une veille (SF-230-04) : un champ absent garde sa valeur. */
    public record UpdateRequest(Boolean active, List<String> runTimes, BigDecimal monthlyBudgetEur) {
    }

    /** Une veille du compte, avec le nom de son sujet (SF-230-04, tableau du client). */
    public record JarvisWatchSummaryView(JarvisWatchView watch, String workspaceName) {
    }

    /** Le taux de lecture en euros quand la grille n'est pas branchée (F-224). */
    public static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");

    /** Une alerte de veille (SF-230-03) — le contrat que lit Jarvis (F-231 / SF-231-05). */
    public record JarvisAlertView(UUID id, UUID watchId, String watchName, String platform, UUID workspaceId,
            Integer chantierNumber, String sentence, List<JarvisWatchDiff.Change> changes, boolean aiWritten,
            BigDecimal costEur, OffsetDateTime createdAt, OffsetDateTime openedAt, String link) {
    }

    /** Un passage (SF-230-02) : sans contenu. */
    public record JarvisWatchRunView(UUID id, UUID watchId, OffsetDateTime slotAt, String trigger, String status,
            int changeCount, String errorCode, Long durationMs, OffsetDateTime startedAt, OffsetDateTime finishedAt) {

        static JarvisWatchRunView of(JarvisWatchRun run) {
            return new JarvisWatchRunView(run.getId(), run.getWatchId(), run.getSlotAt(), run.getTrigger(),
                    run.getStatus(), run.getChangeCount(), run.getErrorCode(), run.getDurationMs(), run.getStartedAt(),
                    run.getFinishedAt());
        }
    }

    /** F-247 / SF-247-08 — le résultat de « Tout valider » pour une sonde de relevé. */
    public record ValidateResult(UUID watchId, String name, boolean activated, String error) {
    }

    /** Le résultat d'un test de sonde devant le PO (SF-230-01). */
    public record JarvisWatchTestView(boolean ok, Integer itemCount, String summary, List<String> sample,
            String errorMessage) {
    }
}
