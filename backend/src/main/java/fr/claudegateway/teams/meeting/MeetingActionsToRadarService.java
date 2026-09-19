package fr.claudegateway.teams.meeting;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.radar.RadarCommitment;
import fr.claudegateway.radar.RadarCommitmentDirection;
import fr.claudegateway.radar.RadarCommitmentRepository;
import fr.claudegateway.radar.RadarEvidence;
import fr.claudegateway.radar.RadarEvidenceSource;
import fr.claudegateway.radar.RadarNotFoundException;
import fr.claudegateway.radar.RadarRegistry;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSubject;
import fr.claudegateway.radar.RadarText;
import fr.claudegateway.radar.RadarToolExecutor;
import fr.claudegateway.teams.meeting.dto.MeetingActionsToRadar;
import fr.claudegateway.teams.meeting.dto.MeetingActionsToRadar.PushedAction;

/**
 * <b>Pousser les actions d'une réunion dans le Radar</b> (F-128 / SF-128-06) — le <b>pont</b> entre
 * l'exploitation d'une réunion (SF-128-05, qui n'affiche que les actions) et le Radar (F-99/F-102/F-104,
 * qui les suit).
 *
 * <h2>On ne réinvente rien : on se branche sur le Radar existant</h2>
 * <ul>
 *   <li><b>Engagement</b> : {@link RadarToolExecutor#recordSovereignEngagement} — le <b>même</b> cœur que
 *       l'outil « Donner la nouvelle » (F-104). Chaque action devient un engagement
 *       {@link RadarCommitmentDirection#ME_TO_OTHER} (« À faire par moi »), <b>souverain</b>, <b>journalisé</b>
 *       (annulable depuis la chronologie du sujet), qui remontera dans le <b>résumé du matin</b> (F-102).</li>
 *   <li><b>Preuve</b> : la réunion, via {@link RadarRegistry#recordEvidence} en
 *       {@link RadarEvidenceSource#TEAMS_MEETING} — source déjà existante, idempotente par
 *       {@code sourceRef}.</li>
 *   <li><b>Rattachement au sujet</b> : le sujet désigné par la requête, sinon celui auquel la réunion est
 *       <b>déjà rattachée</b> ({@code meeting.subjectId}, posé à la capture). Sans sujet évident, on ne
 *       devine pas : on rend {@code needsSubject} et le consultant en désigne un — « le PO reste maître ».</li>
 * </ul>
 *
 * <h2>Gateway-First / rien de gratuit</h2>
 * <p><b>Aucun appel modèle</b> : les actions ont déjà été extraites (SF-128-05) et <b>validées par le
 * consultant</b> ; ce pont ne fait qu'écrire dans le registre. Les textes d'actions sont des
 * <b>données</b> (bornés, jamais des consignes) ; rien n'est poussé sans matière réelle.</p>
 *
 * <h2>Isolation</h2>
 * <p>Réunion résolue par le triplet {@code (id, user_id, host_id)} ; sujet et écritures sous le même
 * {@link RadarScope}. Un identifiant d'un autre poste est introuvable (404).</p>
 */
@Service
public class MeetingActionsToRadarService {

    /** Garde-fou de volume : au-delà, on ne pousse pas une thèse en engagements. */
    static final int MAX_ACTIONS = 30;

    static final String NO_SUBJECT_NOTE =
            "Aucun sujet cible : cette réunion n'est rattachée à aucun sujet du Radar. Désignez le sujet "
                    + "à suivre, puis poussez de nouveau.";
    static final String NO_ACTION_NOTE =
            "Aucune action à pousser : sélectionnez au moins une action de la réunion.";
    static final String ALREADY_PUSHED_NOTE =
            "Ces actions étaient déjà dans le suivi « À faire par moi » : rien de nouveau.";

    private final MeetingRepository repository;
    private final RadarRegistry registry;
    private final RadarToolExecutor executor;
    private final RadarCommitmentRepository commitments;
    private final TransactionTemplate tx;

    public MeetingActionsToRadarService(MeetingRepository repository, RadarRegistry registry,
            RadarToolExecutor executor, RadarCommitmentRepository commitments,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.registry = registry;
        this.executor = executor;
        this.commitments = commitments;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Pousse les actions retenues d'une réunion en engagements « À faire par moi » sur un sujet.
     *
     * @param requestedSubjectId le sujet désigné par le consultant, ou {@code null} pour reprendre celui
     *                           de la réunion
     * @param rawActions         les textes d'actions retenus (bornés et dédoublonnés ici)
     */
    public MeetingActionsToRadar push(RadarScope scope, UUID meetingId, UUID requestedSubjectId,
            List<String> rawActions) {
        Meeting meeting = require(scope, meetingId);
        UUID target = requestedSubjectId != null ? requestedSubjectId : meeting.getSubjectId();
        if (target == null) {
            return MeetingActionsToRadar.needsSubject(NO_SUBJECT_NOTE);
        }
        List<String> actions = clean(rawActions);
        return tx.execute(status -> pushAll(scope, meeting, target, actions));
    }

    // ------------------------------------------------------------------ écriture (une transaction)

    private MeetingActionsToRadar pushAll(RadarScope scope, Meeting meeting, UUID target, List<String> actions) {
        RadarSubject subject = requireSubject(scope, target);
        if (actions.isEmpty()) {
            return MeetingActionsToRadar.nothing(subject.getId(), subject.getName(), NO_ACTION_NOTE);
        }
        // Une seule preuve « réunion » pour tous les engagements : idempotente par sourceRef.
        RadarEvidence proof = registry.recordEvidence(scope, new RadarRegistry.EvidenceInput(
                RadarEvidenceSource.TEAMS_MEETING, meetingSourceRef(meeting.getId()),
                meeting.getStartedAt() != null ? meeting.getStartedAt() : OffsetDateTime.now(),
                meetingQuote(meeting), null, null));

        List<PushedAction> results = new ArrayList<>(actions.size());
        int added = 0;
        for (String action : actions) {
            String key = extractionKey(meeting.getId(), action);
            // Déjà poussée (même clé) : on ne journalise pas un doublon.
            if (commitments.findByUserIdAndHostIdAndExtractionKey(scope.userId(), scope.hostId(), key).isPresent()) {
                results.add(new PushedAction(action, PushedAction.SKIPPED));
                continue;
            }
            executor.recordSovereignEngagement(scope, subject, RadarCommitmentDirection.ME_TO_OTHER,
                    action, null, null, null, null, key, proof);
            results.add(new PushedAction(action, PushedAction.ADDED));
            added++;
        }
        String note = added == 0 ? ALREADY_PUSHED_NOTE : null;
        return new MeetingActionsToRadar(subject.getId(), subject.getName(), added, results, proof.getId(),
                false, note);
    }

    // ------------------------------------------------------------------ aides

    /** Le sujet vivant du périmètre ; un sujet d'un autre poste (ou mort) est introuvable → 404. */
    private RadarSubject requireSubject(RadarScope scope, UUID subjectId) {
        try {
            return registry.requireLiveSubject(scope, subjectId);
        } catch (RadarNotFoundException e) {
            // Traduit dans le vocabulaire des API Réunions (le paquet radar n'attrape pas hors de lui).
            throw new MeetingNotFoundException("Sujet du Radar introuvable sur ce poste : " + subjectId);
        }
    }

    /** Rogne, borne, retire les blancs et les doublons, plafonne le nombre d'actions. */
    static List<String> clean(List<String> rawActions) {
        if (rawActions == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : rawActions) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > RadarCommitment.MAX_DESCRIPTION_LENGTH) {
                trimmed = trimmed.substring(0, RadarCommitment.MAX_DESCRIPTION_LENGTH);
            }
            if (seen.add(RadarText.key(trimmed))) {
                out.add(trimmed);
            }
            if (out.size() >= MAX_ACTIONS) {
                break;
            }
        }
        return out;
    }

    static String meetingSourceRef(UUID meetingId) {
        return "teams-meeting:" + meetingId;
    }

    /** Clé d'extraction stable (réunion + texte normalisé), bornée pour l'idempotence anti-doublon. */
    static String extractionKey(UUID meetingId, String action) {
        String key = "meeting:" + meetingId + ":" + RadarText.key(action);
        return key.length() <= RadarCommitment.MAX_EXTRACTION_KEY_LENGTH
                ? key : key.substring(0, RadarCommitment.MAX_EXTRACTION_KEY_LENGTH);
    }

    private static String meetingQuote(Meeting meeting) {
        String title = meeting.getTitle() == null || meeting.getTitle().isBlank()
                ? "Réunion sans titre" : meeting.getTitle();
        return "Réunion Teams « " + title + " »";
    }

    private Meeting require(RadarScope scope, UUID meetingId) {
        return repository.findByIdAndUserIdAndHostId(meetingId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new MeetingNotFoundException("Réunion introuvable : " + meetingId));
    }
}
