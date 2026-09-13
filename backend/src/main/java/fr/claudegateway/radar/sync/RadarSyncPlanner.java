package fr.claudegateway.radar.sync;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.radar.RadarRunnerUnavailableException;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarStateConflictException;
import fr.claudegateway.radar.RadarSyncRunningException;
import fr.claudegateway.radar.RadarSyncTrigger;
import fr.claudegateway.radar.RadarTeamsDisabledException;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>Le planificateur de la synchro du soir</b> (F-100 / SF-100-02) : tous les soirs à l'heure du poste,
 * et à la prochaine connexion quand le portable était fermé.
 *
 * <p>Rien ne dépend d'un pod : le créneau traité et le verrou sont en base, et un démarrage concurrent sur
 * deux pods n'en produit qu'un.</p>
 */
@Service
public class RadarSyncPlanner {

    /** Postes examinés par page. */
    static final int PAGE = 200;

    private static final Logger log = LoggerFactory.getLogger(RadarSyncPlanner.class);

    private final RadarHostSettingsRepository settings;
    private final RadarSyncLauncher launcher;
    private final RunnerLiveness liveness;
    private final TeamsAccessService teamsAccess;
    /** Les espaces d'un client (F-106 / SF-106-01) : un client hors de la Vigie ne se synchronise pas. */
    private final HostSpaceService hostSpaces;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RadarSyncPlanner(RadarHostSettingsRepository settings, RadarSyncLauncher launcher, RunnerLiveness liveness,
            TeamsAccessService teamsAccess, HostSpaceService hostSpaces,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.hostSpaces = hostSpaces;
        this.settings = settings;
        this.launcher = launcher;
        this.liveness = liveness;
        this.teamsAccess = teamsAccess;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Un passage, maintenant. */
    public int runOnce() {
        return runOnce(OffsetDateTime.now(clock));
    }

    /**
     * Un passage : synchros abandonnées closes, créneaux dus lancés ou notés manqués.
     *
     * @return nombre de synchros lancées
     */
    public int runOnce(OffsetDateTime now) {
        int started = 0;
        for (int page = 0;; page++) {
            List<RadarHostSettings> rows = settings.findByEnabledTrueOrderByIdAsc(PageRequest.of(page, PAGE));
            for (RadarHostSettings row : rows) {
                try {
                    if (plan(row, now)) {
                        started++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Planification Radar en échec (poste={}) : {}", row.getHostId(), e.getClass().getSimpleName());
                }
            }
            if (rows.size() < PAGE) {
                return started;
            }
        }
    }

    private boolean plan(RadarHostSettings row, OffsetDateTime now) {
        RadarScope scope = new RadarScope(row.getUserId(), row.getHostId());
        if (row.getRunningSyncId() != null) {
            transactions.executeWithoutResult(status -> launcher.closeIfStale(scope, row.getRunningSyncId(), now));
        }
        LocalTime time = RadarSlots.parseTime(row.getSyncTime()).orElse(LocalTime.of(22, 0));
        ZoneId zone = RadarSlots.parseZone(row.getTimeZone()).orElse(ZoneId.of("Europe/Paris"));
        LocalDate due = RadarSlots.dueSlotDate(now, time, zone);
        if (row.getLastSlotDate() != null && !row.getLastSlotDate().isBefore(due)) {
            return false; // créneau déjà traité
        }
        OffsetDateTime slot = RadarSlots.slotInstant(due, time, zone);
        if (!teamsAccess.hasAccess(row.getUserId())) {
            return false; // plus de droit : rien ne part, le créneau sera réexaminé
        }
        if (!hostSpaces.isActiveForOwner(row.getUserId(), row.getHostId(), ClientSpace.VIGIE)) {
            return false; // retiré de la Vigie (F-106) : rien ne part, le créneau sera réexaminé
        }
        if (!liveness.isAlive(row.getUserId(), row.getHostId())) {
            if (row.getMissedSlotAt() == null || !row.getMissedSlotAt().isEqual(slot)) {
                transactions.executeWithoutResult(status -> settings.findById(row.getId()).ifPresent(fresh -> {
                    fresh.setMissedSlotAt(slot);
                    settings.save(fresh);
                }));
            }
            return false; // portable fermé : la synchro partira à la prochaine connexion
        }
        RadarSyncTrigger trigger = now.isAfter(slot.plus(RadarSyncProperties.CATCH_UP_GRACE))
                ? RadarSyncTrigger.CATCH_UP : RadarSyncTrigger.SCHEDULED;
        boolean started = false;
        try {
            launcher.start(scope, trigger, slot);
            started = true;
        } catch (RadarSyncRunningException e) {
            // une synchro tient déjà le poste (manuelle, ou lancée par un autre pod) : elle couvre ce créneau
        } catch (RadarRunnerUnavailableException | RadarTeamsDisabledException e) {
            // le refus est écrit dans la synchro close FAILED : visible, pas réessayé ce soir
        } catch (RadarStateConflictException e) {
            return false; // désactivé entre-temps
        }
        transactions.executeWithoutResult(status -> settings.findById(row.getId()).ifPresent(fresh -> {
            if (fresh.getLastSlotDate() == null || fresh.getLastSlotDate().isBefore(due)) {
                fresh.setLastSlotDate(due);
            }
            fresh.setMissedSlotAt(null);
            settings.save(fresh);
        }));
        return started;
    }
}
