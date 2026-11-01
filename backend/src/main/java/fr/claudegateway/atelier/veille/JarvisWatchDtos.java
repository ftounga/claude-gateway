package fr.claudegateway.atelier.veille;

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
            List<String> runTimes, Integer chantierNumber) {
    }

    /** Une veille, telle que l'écran la lit. */
    public record JarvisWatchView(UUID id, UUID workspaceId, Integer chantierNumber, String name, String platform,
            String scope, String command, String scriptPath, List<String> runTimes, String timeZone, String days,
            boolean active, boolean proposed, String origin, OffsetDateTime testedAt, Integer testItemCount,
            String testSummary, OffsetDateTime nextRunAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            OffsetDateTime lastRunAt, String lastRunStatus, boolean broken, int consecutiveFailures) {

        static JarvisWatchView of(JarvisWatch watch, OffsetDateTime now) {
            return new JarvisWatchView(watch.getId(), watch.getWorkspaceId(), watch.getChantierNumber(),
                    watch.getName(), watch.getPlatform(), watch.getScope(), watch.getProbeCommand(),
                    watch.getProbeScriptPath(), JarvisWatchSlots.times(watch.getRunTimes()), watch.getTimeZone(), DAYS,
                    watch.isActive(), watch.isProposed(), watch.getOrigin(), watch.getTestedAt(),
                    watch.getTestItemCount(), watch.getTestSummary(),
                    watch.isScheduled() ? JarvisWatchSlots.nextSlot(watch, now).orElse(null) : null,
                    watch.getCreatedAt(), watch.getUpdatedAt(), watch.getLastRunAt(), watch.getLastRunStatus(),
                    watch.isBroken(), watch.getConsecutiveFailures());
        }
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

    /** Le résultat d'un test de sonde devant le PO (SF-230-01). */
    public record JarvisWatchTestView(boolean ok, Integer itemCount, String summary, List<String> sample,
            String errorMessage) {
    }
}
