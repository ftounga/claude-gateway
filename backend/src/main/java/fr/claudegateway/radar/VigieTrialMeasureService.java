package fr.claudegateway.radar;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.access.AccessCode;
import fr.claudegateway.access.AccessCodeRepository;
import fr.claudegateway.admin.AdminService;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.radar.analysis.RadarAnalysisReport;
import fr.claudegateway.radar.analysis.RadarSyncAnalysisView;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;

/**
 * <b>Le relevé des essais Vigie</b> pour le PO (F-107 / SF-107-04, cadrage §9) : ce que chaque synchro d'essai
 * a réellement coûté. C'est la mesure qui fixera la réserve définitive (consommation mesurée × 1,5).
 *
 * <p>Réservé à l'ADMIN ({@link AdminService#assertAdmin()}, garde unique du produit). Il lit, pour chaque code
 * Vigie consommé, les synchros du compte qui l'a consommé pendant la fenêtre de l'essai — <b>jamais le
 * texte</b> : des dates, des statuts, des jetons et un coût estimé aux tarifs configurés (même calcul que
 * {@code GET /syncs}).</p>
 */
@Service
@Transactional(readOnly = true)
public class VigieTrialMeasureService {

    private final AdminService adminService;
    private final AccessCodeRepository codes;
    private final UserRepository users;
    private final RadarSyncRepository syncs;
    private final RadarAnalysisReport report;
    private final Clock clock;

    public VigieTrialMeasureService(AdminService adminService, AccessCodeRepository codes, UserRepository users,
            RadarSyncRepository syncs, RadarAnalysisReport report, Clock clock) {
        this.adminService = adminService;
        this.codes = codes;
        this.users = users;
        this.syncs = syncs;
        this.report = report;
        this.clock = clock;
    }

    /**
     * Les essais Vigie consommés, du plus récent au plus ancien, avec leurs synchros.
     *
     * @return la liste, éventuellement vide
     */
    public List<TrialMeasure> trials() {
        adminService.assertAdmin();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<TrialMeasure> trials = new ArrayList<>();
        for (AccessCode code : codes.findByGrantedSpaceAndRedeemedAtIsNotNullOrderByRedeemedAtDesc(EntitlementSpace.VIGIE)) {
            UUID userId = code.getRedeemedByUserId();
            OffsetDateTime end = code.getGrantedUntil() == null || code.getGrantedUntil().isAfter(now)
                    ? now : code.getGrantedUntil();
            List<RadarSync> window = syncs.findByUserIdAndStartedAtBetweenOrderByStartedAtAsc(
                    userId, code.getRedeemedAt(), end);
            List<SyncMeasure> measures = measure(userId, window);
            long tokens = measures.stream().mapToLong(SyncMeasure::consumedTokens).sum();
            BigDecimal cost = measures.stream().map(SyncMeasure::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
            trials.add(new TrialMeasure(code.getId(), code.getLabel(),
                    users.findById(userId).map(User::getEmail).orElse(null),
                    code.getRedeemedAt(), code.getGrantedUntil(),
                    code.getGrantedUntil() != null && now.isBefore(code.getGrantedUntil()),
                    measures.size(), tokens, cost, measures));
        }
        return trials;
    }

    /** Coût estimé de chaque synchro, lu poste par poste pour garder {@code user_id} + {@code host_id}. */
    private List<SyncMeasure> measure(UUID userId, List<RadarSync> window) {
        Map<UUID, List<RadarSync>> byHost = new LinkedHashMap<>();
        window.forEach(sync -> byHost.computeIfAbsent(sync.getHostId(), k -> new ArrayList<>()).add(sync));
        Map<UUID, RadarSyncAnalysisView> analyses = new LinkedHashMap<>();
        byHost.forEach((hostId, hostSyncs) -> analyses.putAll(report.bySync(new RadarScope(userId, hostId), hostSyncs)));
        return window.stream().map(sync -> {
            RadarSyncAnalysisView analysis = analyses.get(sync.getId());
            return new SyncMeasure(sync.getId(), sync.getHostId(), sync.getStartedAt(), sync.getStatus().name(),
                    sync.isReserveExempt(), sync.getConsumedTokens(),
                    analysis == null ? BigDecimal.ZERO : analysis.costUsd(),
                    analysis != null && analysis.stoppedOnReserve());
        }).toList();
    }

    /**
     * Un essai Vigie.
     *
     * @param codeId        code consommé
     * @param label         libellé donné à l'émission
     * @param email         compte qui l'a consommé, ou {@code null} s'il n'existe plus
     * @param startedAt     consommation du code (début de l'essai)
     * @param endsAt        terme de l'essai
     * @param active        l'essai court encore
     * @param syncCount     synchros pendant l'essai
     * @param consumedTokens jetons consommés, première synchro comprise
     * @param costUsd       coût estimé total (USD, tarifs configurés)
     * @param syncs         détail synchro par synchro
     */
    public record TrialMeasure(UUID codeId, String label, String email, OffsetDateTime startedAt,
            OffsetDateTime endsAt, boolean active, int syncCount, long consumedTokens, BigDecimal costUsd,
            List<SyncMeasure> syncs) {
    }

    /**
     * Une synchro d'essai.
     *
     * @param syncId           synchro
     * @param hostId           poste
     * @param startedAt        début
     * @param status           statut
     * @param firstSync        hors réserve (première synchro du client)
     * @param consumedTokens   jetons consommés
     * @param costUsd          coût estimé (USD)
     * @param stoppedOnReserve arrêtée sur réserve épuisée
     */
    public record SyncMeasure(UUID syncId, UUID hostId, OffsetDateTime startedAt, String status, boolean firstSync,
            long consumedTokens, BigDecimal costUsd, boolean stoppedOnReserve) {
    }
}
