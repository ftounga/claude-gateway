package fr.claudegateway.radar;

import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.AliasRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.CommitmentCorrectionRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.MergeRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.PurgeRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SplitRequest;
import fr.claudegateway.radar.dto.RadarCorrectionRequests.SubjectCorrectionRequest;
import fr.claudegateway.radar.dto.RadarViews.AliasView;
import fr.claudegateway.radar.dto.RadarViews.ClosureView;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.CorrectionView;
import fr.claudegateway.radar.dto.RadarViews.EvidenceView;
import fr.claudegateway.radar.dto.RadarViews.PersonView;
import fr.claudegateway.radar.dto.RadarViews.PurgeView;
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
    private final RadarStructureService structureService;
    private final RadarClosureService closureService;
    private final RadarPurgeService purgeService;
    private final RadarExportService exportService;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;

    public RadarController(RadarReadService readService, RadarCorrectionService correctionService,
            RadarStructureService structureService, RadarClosureService closureService,
            RadarPurgeService purgeService, RadarExportService exportService, RadarScopeResolver scopeResolver, TeamsAccessService teamsAccess, CurrentUser currentUser) {
        this.readService = readService;
        this.correctionService = correctionService;
        this.structureService = structureService;
        this.closureService = closureService;
        this.purgeService = purgeService;
        this.exportService = exportService;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/subjects")
    public List<SubjectSummary> subjects(@PathVariable UUID hostId,
            @RequestParam(required = false) RadarSubjectState state,
            @RequestParam(defaultValue = "false") boolean includeClosed,
            @RequestParam(required = false) String q) {
        return readService.subjects(scope(hostId), state, includeClosed, q);
    }

    @GetMapping("/subjects/{subjectId}")
    public SubjectDetail subject(@PathVariable UUID hostId, @PathVariable UUID subjectId) {
        return readService.subject(scope(hostId), subjectId);
    }

    @GetMapping("/commitments")
    public List<CommitmentView> commitments(@PathVariable UUID hostId,
            @RequestParam(required = false) RadarCommitmentDirection direction,
            @RequestParam(required = false) RadarCommitmentStatus status,
            @RequestParam(defaultValue = "false") boolean includeDisowned,
            @RequestParam(defaultValue = "false") boolean followUpDue) {
        return readService.commitments(scope(hostId), direction, status, includeDisowned, followUpDue);
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

    // ---------------------------------------------------------- fusion, séparation, alias (SF-99-03)

    @PostMapping("/subjects/{subjectId}/merge")
    public CorrectionView merge(@PathVariable UUID hostId, @PathVariable UUID subjectId,
            @RequestBody MergeRequest request) {
        return structureService.merge(scope(hostId), subjectId, request == null ? null : request.intoSubjectId());
    }

    @PostMapping("/subjects/{subjectId}/split")
    public CorrectionView split(@PathVariable UUID hostId, @PathVariable UUID subjectId,
            @RequestBody SplitRequest request) {
        return structureService.split(scope(hostId), subjectId, request.name(), request.evidenceIds(),
                request.commitmentIds());
    }

    @PostMapping("/subjects/{subjectId}/aliases")
    public AliasView addAlias(@PathVariable UUID hostId, @PathVariable UUID subjectId,
            @RequestBody AliasRequest request) {
        return structureService.addUserAlias(scope(hostId), subjectId, request.alias());
    }

    @DeleteMapping("/subjects/{subjectId}/aliases/{aliasId}")
    public ResponseEntity<Void> removeAlias(@PathVariable UUID hostId, @PathVariable UUID subjectId,
            @PathVariable UUID aliasId) {
        structureService.removeAlias(scope(hostId), subjectId, aliasId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ clôture d'un sujet (SF-99-04)

    @PostMapping("/subjects/{subjectId}/close")
    public ClosureView close(@PathVariable UUID hostId, @PathVariable UUID subjectId) {
        return closureService.close(scope(hostId), subjectId);
    }

    @PostMapping("/subjects/{subjectId}/close-proposal/confirm")
    public ClosureView confirmClosure(@PathVariable UUID hostId, @PathVariable UUID subjectId) {
        return closureService.confirmProposal(scope(hostId), subjectId);
    }

    @PostMapping("/subjects/{subjectId}/close-proposal/reject")
    public CorrectionView rejectClosure(@PathVariable UUID hostId, @PathVariable UUID subjectId) {
        return closureService.rejectProposal(scope(hostId), subjectId);
    }

    @PostMapping("/subjects/{subjectId}/wake/dismiss")
    public CorrectionView dismissWake(@PathVariable UUID hostId, @PathVariable UUID subjectId) {
        return closureService.dismissWake(scope(hostId), subjectId);
    }

    // ---------------------------------------------------------------- purge et export (SF-99-05)
    // Sans droit d'option : récupérer et effacer ses données ne dépend pas d'un abonnement en cours.

    @GetMapping("/export")
    public ResponseEntity<String> export(@PathVariable UUID hostId) {
        RadarExportService.Export export = exportService.export(ownedScope(hostId), LocalDate.now());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName() + "\"")
                .body(export.markdown());
    }

    @PostMapping("/purge")
    public PurgeView purge(@PathVariable UUID hostId, @RequestBody PurgeRequest request) {
        return purgeService.requestPurge(ownedScope(hostId), request == null ? null : request.reason(),
                request == null ? null : request.confirm());
    }

    @GetMapping("/purges")
    public List<PurgeView> purges(@PathVariable UUID hostId) {
        return purgeService.traces(ownedScope(hostId));
    }

    /** Possession seule, sans droit d'option (export, purge). */
    private RadarScope ownedScope(UUID hostId) {
        return scopeResolver.require(currentUser.requireId(), hostId);
    }

    /** Droit d'abord, possession ensuite : sans le droit, on ne dit rien des postes. */
    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        return scopeResolver.require(currentUser.requireId(), hostId);
    }
}
