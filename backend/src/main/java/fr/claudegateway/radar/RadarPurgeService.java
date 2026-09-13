package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.analysis.RadarAnalysisBatchRepository;
import fr.claudegateway.radar.analysis.RadarAnalysisLeaseRepository;
import fr.claudegateway.radar.sync.RadarHostSettingsRepository;
import fr.claudegateway.radar.dto.RadarViews.PurgeView;
import fr.claudegateway.runner.host.HostMissionStatus;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>La purge du Radar</b> (F-99 / SF-99-05, cadrage §4.6 et §14).
 *
 * <p>Des extraits, pas des archives — et même les extraits ne survivent pas à la mission : clôturer
 * la mission, retirer le poste de la Vigie, supprimer le poste ou le compte efface le Radar. Chaque
 * suppression est <b>filtrée sur son périmètre</b> ; une purge d'un poste ne touche jamais un autre
 * poste du même utilisateur.</p>
 *
 * <p>Une trace sans contenu est gardée ({@code radar_purges}) : la preuve qu'on a effacé.</p>
 */
@Service
@Transactional
public class RadarPurgeService {

    private final RadarSubjectRepository subjects;
    private final RadarSubjectAliasRepository aliases;
    private final RadarSubjectFactRepository facts;
    private final RadarPersonRepository people;
    private final RadarSubjectRoleRepository roles;
    private final RadarCommitmentRepository commitments;
    private final RadarEvidenceRepository evidence;
    private final RadarEvidenceLinkRepository links;
    private final RadarSyncRepository syncs;
    private final RadarCorrectionRepository corrections;
    private final RadarPurgeRepository purges;
    private final RunnerHostService hostService;
    private final RadarAnalysisBatchRepository analysisBatches;
    private final RadarAnalysisLeaseRepository analysisLeases;
    private final RadarHostSettingsRepository hostSettings;

    public RadarPurgeService(RadarSubjectRepository subjects, RadarSubjectAliasRepository aliases,
            RadarSubjectFactRepository facts, RadarPersonRepository people, RadarSubjectRoleRepository roles,
            RadarCommitmentRepository commitments, RadarEvidenceRepository evidence,
            RadarEvidenceLinkRepository links, RadarSyncRepository syncs, RadarCorrectionRepository corrections,
            RadarPurgeRepository purges, RunnerHostService hostService,
            RadarAnalysisBatchRepository analysisBatches, RadarAnalysisLeaseRepository analysisLeases,
            RadarHostSettingsRepository hostSettings) {
        this.subjects = subjects;
        this.aliases = aliases;
        this.facts = facts;
        this.people = people;
        this.roles = roles;
        this.commitments = commitments;
        this.evidence = evidence;
        this.links = links;
        this.syncs = syncs;
        this.corrections = corrections;
        this.purges = purges;
        this.hostService = hostService;
        this.analysisBatches = analysisBatches;
        this.analysisLeases = analysisLeases;
        this.hostSettings = hostSettings;
    }

    /**
     * La purge demandée par l'utilisateur (l'écran a proposé l'export avant).
     *
     * @throws InvalidRadarInputException  sans confirmation, sans raison, ou avec la raison réservée
     * @throws RadarStateConflictException « mission clôturée » alors que la mission ne l'est pas
     */
    public PurgeView requestPurge(RadarScope scope, RadarPurgeReason reason, Boolean confirm) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw new InvalidRadarInputException("La purge est irréversible : confirmez-la explicitement.");
        }
        if (reason == null || reason == RadarPurgeReason.HOST_DELETED) {
            throw new InvalidRadarInputException(
                    "Raison attendue : MISSION_CLOSED, VIGIE_REMOVED ou USER_REQUEST.");
        }
        if (reason == RadarPurgeReason.MISSION_CLOSED
                && hostService.requireOwned(scope.userId(), scope.hostId()).getMissionStatus()
                        != HostMissionStatus.CLOSED) {
            throw new RadarStateConflictException("La mission de ce poste n'est pas clôturée.");
        }
        return view(purge(scope, reason));
    }

    /** Efface tout le Radar d'un poste et en garde la trace. */
    public RadarPurge purge(RadarScope scope, RadarPurgeReason reason) {
        UUID userId = scope.userId();
        UUID hostId = scope.hostId();
        corrections.purgeScope(userId, hostId);
        analysisBatches.purgeScope(userId, hostId);
        analysisLeases.purgeScope(userId, hostId);
        hostSettings.purgeScope(userId, hostId);
        links.purgeScope(userId, hostId);
        int evidenceCount = evidence.purgeScope(userId, hostId);
        commitments.purgeScope(userId, hostId);
        roles.purgeScope(userId, hostId);
        facts.purgeScope(userId, hostId);
        aliases.purgeScope(userId, hostId);
        people.purgeScope(userId, hostId);
        syncs.purgeScope(userId, hostId);
        int subjectsCount = subjects.purgeScope(userId, hostId);
        return purges.save(RadarPurge.builder()
                .userId(userId).hostId(hostId).reason(reason).purgedAt(OffsetDateTime.now())
                .subjectsCount(subjectsCount).evidenceCount(evidenceCount).build());
    }

    /** Efface tous les Radars d'un compte, traces comprises (suppression du compte). */
    public void purgeUser(UUID userId) {
        corrections.purgeUser(userId);
        analysisBatches.purgeUser(userId);
        analysisLeases.purgeUser(userId);
        hostSettings.purgeUser(userId);
        links.purgeUser(userId);
        evidence.purgeUser(userId);
        commitments.purgeUser(userId);
        roles.purgeUser(userId);
        facts.purgeUser(userId);
        aliases.purgeUser(userId);
        people.purgeUser(userId);
        syncs.purgeUser(userId);
        subjects.purgeUser(userId);
        purges.purgeUser(userId);
    }

    /** Les traces de purge d'un poste, plus récentes d'abord. */
    @Transactional(readOnly = true)
    public List<PurgeView> traces(RadarScope scope) {
        return purges.findByUserIdAndHostIdOrderByPurgedAtDesc(scope.userId(), scope.hostId()).stream()
                .map(RadarPurgeService::view).toList();
    }

    private static PurgeView view(RadarPurge purge) {
        return new PurgeView(purge.getId(), purge.getReason(), purge.getPurgedAt(), purge.getSubjectsCount(),
                purge.getEvidenceCount());
    }
}
