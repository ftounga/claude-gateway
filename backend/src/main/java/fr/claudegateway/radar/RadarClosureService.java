package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.dto.RadarViews.ClosureView;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;

/**
 * <b>Quand un sujet se termine</b> (F-99 / SF-99-04, cadrage §6) — les gestes de l'utilisateur.
 *
 * <ul>
 *   <li>Il <b>dit</b> que le sujet est clos : clos immédiatement, sans confirmation — parole souveraine.</li>
 *   <li>Il <b>confirme</b> ou <b>refuse</b> la proposition née d'un signal explicite.</li>
 *   <li>Il <b>laisse clos</b> un sujet qui s'est réveillé (rouvrir, c'est redire l'état : SF-99-02).</li>
 * </ul>
 *
 * <p>Chaque geste est une correction journalisée, donc annulable. Clôturer rend les engagements
 * encore ouverts du sujet : c'est à l'écran de demander « le fermer aussi ? », pas au registre de
 * décider à la place de l'utilisateur.</p>
 */
@Service
@Transactional
public class RadarClosureService {

    private final RadarRegistry registry;
    private final RadarSubjectRepository subjects;
    private final RadarReadService readService;
    private final RadarCorrectionJournal journal;

    public RadarClosureService(RadarRegistry registry, RadarSubjectRepository subjects,
            RadarReadService readService, RadarCorrectionJournal journal) {
        this.registry = registry;
        this.subjects = subjects;
        this.readService = readService;
        this.journal = journal;
    }

    /** L'utilisateur clôt le sujet. */
    public ClosureView close(RadarScope scope, UUID subjectId) {
        RadarSubject subject = requireUnmerged(scope, subjectId);
        if (subject.getState() == RadarSubjectState.CLOSED) {
            throw new RadarStateConflictException("Ce sujet est déjà clos.");
        }
        return closeNow(scope, subject, RadarCorrectionAction.CLOSE);
    }

    /** L'utilisateur confirme la proposition de clôture. */
    public ClosureView confirmProposal(RadarScope scope, UUID subjectId) {
        RadarSubject subject = requireProposed(scope, subjectId);
        return closeNow(scope, subject, RadarCorrectionAction.CONFIRM_CLOSE);
    }

    /** L'utilisateur refuse la proposition : retour à l'état d'avant ; le même signal ne repropose pas. */
    public CorrectionView rejectProposal(RadarScope scope, UUID subjectId) {
        RadarSubject subject = requireProposed(scope, subjectId);
        Map<String, Object> before = lifecycle(subject);
        before.put("closeRejectedAt", text(subject.getCloseRejectedAt()));
        RadarSubjectState previous = subject.getPreviousState();
        subject.setState(previous != null && previous.isOpenWork() ? previous : RadarSubjectState.ADVANCING);
        subject.setPreviousState(null);
        subject.setCloseProposedAt(null);
        subject.setCloseRejectedAt(OffsetDateTime.now());
        subjects.save(subject);
        Map<String, Object> after = lifecycle(subject);
        after.put("closeRejectedAt", text(subject.getCloseRejectedAt()));
        return journal.view(journal.record(scope, subject.getId(), RadarCorrectionAction.Target.SUBJECT,
                subject.getId(), RadarCorrectionAction.REJECT_CLOSE, before, after));
    }

    /** L'utilisateur laisse clos un sujet qui s'est réveillé. */
    public CorrectionView dismissWake(RadarScope scope, UUID subjectId) {
        RadarSubject subject = requireUnmerged(scope, subjectId);
        if (subject.getState() != RadarSubjectState.CLOSED || subject.getWokeAt() == null) {
            throw new RadarStateConflictException("Ce sujet n'est pas un sujet clos qui se réveille.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("wokeAt", text(subject.getWokeAt()));
        before.put("wakeDismissedAt", text(subject.getWakeDismissedAt()));
        subject.setWokeAt(null);
        subject.setWakeDismissedAt(OffsetDateTime.now());
        subjects.save(subject);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("wokeAt", null);
        after.put("wakeDismissedAt", text(subject.getWakeDismissedAt()));
        return journal.view(journal.record(scope, subject.getId(), RadarCorrectionAction.Target.SUBJECT,
                subject.getId(), RadarCorrectionAction.DISMISS_WAKE, before, after));
    }

    // ------------------------------------------------------------------------------------ aides

    private ClosureView closeNow(RadarScope scope, RadarSubject subject, RadarCorrectionAction action) {
        Map<String, Object> before = lifecycle(subject);
        before.put("stateSovereign", subject.isStateSovereign());
        clearLifecycle(subject);
        subject.setState(RadarSubjectState.CLOSED);
        subject.setStateSovereign(true);
        subject.setClosedAt(OffsetDateTime.now());
        subjects.save(subject);
        Map<String, Object> after = lifecycle(subject);
        after.put("stateSovereign", true);
        CorrectionView correction = journal.view(journal.record(scope, subject.getId(),
                RadarCorrectionAction.Target.SUBJECT, subject.getId(), action, before, after));
        List<CommitmentView> open = readService.subject(scope, subject.getId()).commitments().stream()
                .filter(c -> c.status().isPending() && !c.disowned())
                .toList();
        return new ClosureView(correction, open);
    }

    private RadarSubject requireProposed(RadarScope scope, UUID subjectId) {
        RadarSubject subject = requireUnmerged(scope, subjectId);
        if (subject.getState() != RadarSubjectState.CLOSE_PROPOSED) {
            throw new RadarStateConflictException("Aucune clôture n'est proposée pour ce sujet.");
        }
        return subject;
    }

    private RadarSubject requireUnmerged(RadarScope scope, UUID subjectId) {
        RadarSubject subject = registry.requireSubject(scope, subjectId);
        if (subject.getMergedIntoId() != null) {
            throw new RadarSubjectMergedException("Ce sujet a été fusionné dans un autre.");
        }
        return subject;
    }

    /** Les champs de fin de vie d'un sujet, pour le journal (valeurs textuelles). */
    static Map<String, Object> lifecycle(RadarSubject subject) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("state", subject.getState().name());
        values.put("previousState", subject.getPreviousState() == null ? null : subject.getPreviousState().name());
        values.put("closeProposedAt", text(subject.getCloseProposedAt()));
        values.put("closedAt", text(subject.getClosedAt()));
        values.put("dormantSince", text(subject.getDormantSince()));
        values.put("wokeAt", text(subject.getWokeAt()));
        values.put("wakeDismissedAt", text(subject.getWakeDismissedAt()));
        return values;
    }

    /** Efface clôture, proposition, sommeil et réveil (le souvenir d'un refus est gardé). */
    static void clearLifecycle(RadarSubject subject) {
        subject.setPreviousState(null);
        subject.setCloseProposedAt(null);
        subject.setClosedAt(null);
        subject.setDormantSince(null);
        subject.setWokeAt(null);
        subject.setWakeDismissedAt(null);
    }

    private static String text(OffsetDateTime instant) {
        return instant == null ? null : instant.toString();
    }
}
