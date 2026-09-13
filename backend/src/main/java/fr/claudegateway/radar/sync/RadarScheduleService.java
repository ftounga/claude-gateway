package fr.claudegateway.radar.sync;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;
import fr.claudegateway.radar.RadarSyncStatus;
import fr.claudegateway.radar.RadarSyncTrigger;

/**
 * <b>L'heure du soir d'un poste</b> (F-100 / SF-100-02) : activer le Radar, choisir l'heure et le fuseau,
 * dire quand part la prochaine synchro.
 */
@Service
public class RadarScheduleService {

    private static final com.fasterxml.jackson.databind.ObjectMapper PROGRESS_READER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final RadarHostSettingsRepository settings;
    private final RadarSyncRepository syncs;
    private final Clock clock;

    public RadarScheduleService(RadarHostSettingsRepository settings, RadarSyncRepository syncs, Clock clock) {
        this.settings = settings;
        this.syncs = syncs;
        this.clock = clock;
    }

    /** Ce que l'écran demande : activer, l'heure, le fuseau, l'autorisation du client. */
    public record ScheduleRequest(Boolean enabled, String syncTime, String timeZone,
            Boolean clientAuthorizationConfirmed) {
    }

    /** La synchro qui tient le poste. */
    public record RunningView(UUID syncId, RadarSyncTrigger trigger, OffsetDateTime startedAt,
            OffsetDateTime heartbeatAt, String phase, int done, int total) {
    }

    /** Ce que l'écran rend. */
    public record ScheduleView(boolean enabled, OffsetDateTime clientAuthorizedAt, String syncTime, String timeZone,
            OffsetDateTime nextSyncAt, OffsetDateTime missedSlotAt, RunningView running) {
    }

    @Transactional(readOnly = true)
    public ScheduleView view(RadarScope scope) {
        return settings.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .map(row -> view(scope, row))
                .orElseGet(() -> new ScheduleView(false, null, "22:00", "Europe/Paris", null, null, null));
    }

    /**
     * Règle l'heure du soir d'un poste. Le dernier créneau passé est marqué traité : régler ne déclenche
     * jamais de synchro surprise.
     *
     * @throws InvalidRadarInputException première activation sans autorisation du client, heure ou fuseau
     *                                    illisibles
     */
    @Transactional
    public ScheduleView update(RadarScope scope, ScheduleRequest request) {
        if (request == null || request.enabled() == null) {
            throw new InvalidRadarInputException("Dites si le Radar est activé sur ce poste.");
        }
        RadarHostSettings row = settings.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .orElseGet(() -> RadarHostSettings.builder().userId(scope.userId()).hostId(scope.hostId()).build());
        String time = request.syncTime() == null ? row.getSyncTime() : request.syncTime().strip();
        String zone = request.timeZone() == null ? row.getTimeZone() : request.timeZone().strip();
        LocalTime localTime = RadarSlots.parseTime(time)
                .orElseThrow(() -> new InvalidRadarInputException("Heure attendue au format HH:mm (00:00 à 23:59)."));
        ZoneId zoneId = RadarSlots.parseZone(zone)
                .orElseThrow(() -> new InvalidRadarInputException("Fuseau horaire inconnu : " + zone + "."));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (request.enabled() && row.getClientAuthorizedAt() == null) {
            if (!Boolean.TRUE.equals(request.clientAuthorizationConfirmed())) {
                throw new InvalidRadarInputException("Avant d'activer le Radar, confirmez que votre client autorise "
                        + "la conservation d'extraits de ses échanges (citations courtes et liens).");
            }
            row.setClientAuthorizedAt(now);
        }
        row.setEnabled(request.enabled());
        row.setSyncTime(time);
        row.setTimeZone(zoneId.getId());
        row.setLastSlotDate(RadarSlots.dueSlotDate(now, localTime, zoneId));
        row.setMissedSlotAt(null);
        return view(scope, settings.save(row));
    }

    private ScheduleView view(RadarScope scope, RadarHostSettings row) {
        OffsetDateTime next = null;
        if (row.isEnabled()) {
            LocalTime time = RadarSlots.parseTime(row.getSyncTime()).orElse(LocalTime.of(22, 0));
            ZoneId zone = RadarSlots.parseZone(row.getTimeZone()).orElse(ZoneId.of("Europe/Paris"));
            next = RadarSlots.nextSlot(OffsetDateTime.now(clock), time, zone);
        }
        RunningView running = null;
        if (row.getRunningSyncId() != null) {
            RadarSync sync = syncs.findByIdAndUserIdAndHostId(row.getRunningSyncId(), scope.userId(), scope.hostId())
                    .filter(s -> s.getStatus() == RadarSyncStatus.RUNNING).orElse(null);
            if (sync != null) {
                String phase = "";
                int done = 0;
                int total = 0;
                try {
                    // F-100 / SF-100-04 : la progression du dernier battement.
                    com.fasterxml.jackson.databind.JsonNode progress = sync.getProgress() == null ? null
                            : PROGRESS_READER.readTree(sync.getProgress());
                    if (progress != null) {
                        phase = progress.path("phase").asText("");
                        done = progress.path("done").asInt(0);
                        total = progress.path("total").asInt(0);
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // progression illisible : la synchro reste « en cours », sans chiffres
                }
                running = new RunningView(sync.getId(), sync.getTriggerKind(), sync.getStartedAt(), sync.getHeartbeatAt(),
                        phase, done, total);
            }
        }
        return new ScheduleView(row.isEnabled(), row.getClientAuthorizedAt(), row.getSyncTime(), row.getTimeZone(),
                next, row.getMissedSlotAt(), running);
    }
}
