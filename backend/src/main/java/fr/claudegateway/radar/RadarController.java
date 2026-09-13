package fr.claudegateway.radar;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.CommitmentCorrectionRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;
import fr.claudegateway.radar.dto.RadarViews.EvidenceView;
import fr.claudegateway.radar.dto.RadarViews.PersonView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;
import fr.claudegateway.radar.dto.RadarViews.SubjectSummary;
import fr.claudegateway.radar.dto.RadarViews.SyncView;
import fr.claudegateway.teams.TeamsAccessService;
import jakarta.validation.Valid;

/**
 * Le Radar <b>d'un poste</b> : lecture (F-99 / SF-99-01) et corrections souveraines (SF-99-02).
 *
 * <p><b>Droit.</b> Le Radar suppose l'option Teams (cadrage §11) ; le droit Vigie qui le portera
 * (F-107 / SF-107-03) n'existe pas encore : chaque route exige donc le droit Teams, bypass
 * administrateur compris. <b>Isolation.</b> Le poste est vérifié comme possédé avant toute lecture ;
 * un poste d'autrui rend « introuvable ».</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}")
public class RadarController {

    private final RadarReadService readService;
    private final RadarCorrectionService correctionService;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarController(RadarReadService readService, RadarCorrectionService correctionService,
            RadarScopeResolver scopeResolver, TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.readService = readService;
        this.correctionService = correctionService;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/subjects")
    public List<SubjectSummary> subjects(@PathVariable UUID hostId,
            @RequestParam(required = false) RadarSubjectState state,
            @RequestParam(defaultValue = "false") boolean includeClosed) {
        return readService.subjects(scope(hostId), state, includeClosed);
    }

    @GetMapping("/subjects/{subjectId}")
    public SubjectDetail subject(@PathVariable UUID hostId, @PathVariable UUID subjectId) {
        return readService.subject(scope(hostId), subjectId);
    }

    @GetMapping("/commitments")
    public List<CommitmentView> commitments(@PathVariable UUID hostId,
            @RequestParam(required = false) RadarCommitmentDirection direction,
            @RequestParam(required = false) RadarCommitmentStatus status,
            @RequestParam(defaultValue = "false") boolean includeDisowned) {
        return readService.commitments(scope(hostId), direction, status, includeDisowned);
    }

    @GetMapping("/people")
    public List<PersonView> people(@PathVariable UUID hostId) {
        return readService.people(scope(hostId));
    }

    @GetMapping("/evidence/{evidenceId}")
    public EvidenceView evidence(@PathVariable UUID hostId, @PathVariable UUID evidenceId) {
        return readService.evidence(scope(hostId), evidenceId);
    }

    @GetMapping("/syncs")
    public List<SyncView> syncs(@PathVariable UUID hostId) {
        return readService.syncs(scope(hostId));
    }

    // ------------------------------------------------------------ corrections souveraines (SF-99-02)

    @PostMapping("/subjects/{subjectId}/corrections")
    public CorrectionView correctSubject(@PathVariable UUID hostId, @PathVariable UUID subjectId,
            @Valid @RequestBody SubjectCorrectionRequest request) {
        return correctionService.correctSubject(scope(hostId), subjectId, request);
    }

    @PostMapping("/commitments/{commitmentId}/corrections")
    public CorrectionView correctCommitment(@PathVariable UUID hostId, @PathVariable UUID commitmentId,
            @Valid @RequestBody CommitmentCorrectionRequest request) {
        return correctionService.correctCommitment(scope(hostId), commitmentId, request);
    }

    @GetMapping("/corrections")
    public List<CorrectionView> corrections(@PathVariable UUID hostId,
            @RequestParam(required = false) UUID subjectId) {
        return correctionService.journal(scope(hostId), subjectId);
    }

    @PostMapping("/corrections/{correctionId}/undo")
    public CorrectionView undo(@PathVariable UUID hostId, @PathVariable UUID correctionId) {
        return correctionService.undo(scope(hostId), correctionId);
    }

    /** Droit d'abord, possession ensuite : sans le droit, on ne dit rien des postes. */
    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.require(currentUser.requireId(), hostId);
    }
}
