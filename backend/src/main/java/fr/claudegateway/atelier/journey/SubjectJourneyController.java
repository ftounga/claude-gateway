package fr.claudegateway.atelier.journey;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le parcours du sujet d'un terminal</b> (F-176).
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; aucun identifiant de compte ne
 * transite par la requête. L'isolation est appliquée par le service ({@code requireOwned} d'abord :
 * 404 sur un terminal d'autrui).</p>
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/journey")
public class SubjectJourneyController {

    private final SubjectJourneyService service;
    private final CurrentUser currentUser;

    public SubjectJourneyController(SubjectJourneyService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Le mode, la phase et le plan du sujet ; Libre s'il n'a jamais été décidé. */
    @GetMapping
    public SubjectJourneyResponse get(@PathVariable UUID workspaceId) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.get(userId, workspaceId));
    }

    /** Le menu du terminal : Libre ou Guidé (SF-176-01). */
    @PutMapping("/mode")
    public SubjectJourneyResponse setMode(@PathVariable UUID workspaceId, @RequestBody ModeRequest request) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.setMode(userId, workspaceId,
                request == null ? null : request.mode(), request == null ? null : request.title()));
    }

    /** Les chantiers clos du sujet, le plus récent d'abord (SF-176-11). */
    @GetMapping("/chantiers")
    public java.util.List<SubjectJourneyResponse.ClosedChantier> chantiers(@PathVariable UUID workspaceId) {
        UUID userId = currentUser.requireId();
        return service.closedChantiers(userId, workspaceId).stream()
                .map(SubjectJourneyResponse.ClosedChantier::from)
                .toList();
    }

    /** Les versions du plan du chantier N, avec ce qui a changé de l'une à l'autre (F-208 / SF-208-02). */
    @GetMapping("/chantiers/{number}/versions")
    public java.util.List<SubjectJourneyResponse.PlanVersion> versions(@PathVariable UUID workspaceId,
                                                                      @PathVariable int number) {
        UUID userId = currentUser.requireId();
        return service.planVersions(userId, workspaceId, number).stream()
                .map(SubjectJourneyResponse.PlanVersion::from)
                .toList();
    }

    /** [Passer en guidé] — la proposition de l'agent est acceptée (SF-176-02). */
    @PostMapping("/guided-proposal/accept")
    public SubjectJourneyResponse acceptGuided(@PathVariable UUID workspaceId) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.acceptGuidedProposal(userId, workspaceId));
    }

    /** [Rester libre] — la proposition est écartée et ne revient pas sur ce sujet (SF-176-02). */
    @PostMapping("/guided-proposal/decline")
    public SubjectJourneyResponse declineGuided(@PathVariable UUID workspaceId) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.declineGuidedProposal(userId, workspaceId));
    }

    /** [Valider le plan] — un clic valide le plan entier, à la version vue (SF-176-03). */
    @PostMapping("/plan/validate")
    public SubjectJourneyResponse validatePlan(@PathVariable UUID workspaceId,
                                               @RequestBody(required = false) ValidateRequest request) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId,
                service.validatePlan(userId, workspaceId, request == null ? null : request.version()));
    }

    /** [Planifier] — le diagnostic est accepté, le sujet passe en Plan (SF-176-05). */
    @PostMapping("/diagnosis/confirm")
    public SubjectJourneyResponse confirmDiagnosis(@PathVariable UUID workspaceId) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.confirmDiagnosis(userId, workspaceId));
    }

    /** [Continuer l'investigation] (SF-176-05). */
    @PostMapping("/diagnosis/dismiss")
    public SubjectJourneyResponse dismissDiagnosis(@PathVariable UUID workspaceId) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.dismissDiagnosis(userId, workspaceId));
    }

    /** [Clore le sujet] (SF-176-05). */
    @PostMapping("/close")
    public SubjectJourneyResponse close(@PathVariable UUID workspaceId) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.close(userId, workspaceId));
    }

    /** [Pas encore] — la proposition de clôture est écartée (SF-176-05). */
    @PostMapping("/close/dismiss")
    public SubjectJourneyResponse dismissClose(@PathVariable UUID workspaceId) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.dismissClose(userId, workspaceId));
    }

    // ------------------------------------------------------------------ F-206 : chantiers ouverts

    /** Ouvre un chantier de plus, à côté des autres ; il prend le focus (F-206). */
    @PostMapping("/chantiers")
    public SubjectJourneyResponse openChantier(@PathVariable UUID workspaceId,
                                               @RequestBody(required = false) OpenRequest request) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId,
                service.openAlongside(userId, workspaceId, request == null ? null : request.title()));
    }

    /** [Basculer sur le chantier N] (F-206). */
    @PostMapping("/chantiers/{number}/focus")
    public SubjectJourneyResponse focus(@PathVariable UUID workspaceId, @PathVariable int number) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.focus(userId, workspaceId, number));
    }

    /** [Valider le plan] du chantier N, à la version vue (F-206). */
    @PostMapping("/chantiers/{number}/plan/validate")
    public SubjectJourneyResponse validateChantierPlan(@PathVariable UUID workspaceId, @PathVariable int number,
                                                       @RequestBody(required = false) ValidateRequest request) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.validatePlan(userId, workspaceId, number,
                request == null ? null : request.version()));
    }

    /** [Planifier] sur le chantier N (F-206). */
    @PostMapping("/chantiers/{number}/diagnosis/confirm")
    public SubjectJourneyResponse confirmChantierDiagnosis(@PathVariable UUID workspaceId, @PathVariable int number) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.confirmDiagnosis(userId, workspaceId, number));
    }

    /** [Continuer l'investigation] sur le chantier N (F-206). */
    @PostMapping("/chantiers/{number}/diagnosis/dismiss")
    public SubjectJourneyResponse dismissChantierDiagnosis(@PathVariable UUID workspaceId, @PathVariable int number) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.dismissDiagnosis(userId, workspaceId, number));
    }

    /** [Clore] le chantier N (F-206). */
    @PostMapping("/chantiers/{number}/close")
    public SubjectJourneyResponse closeChantier(@PathVariable UUID workspaceId, @PathVariable int number) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.close(userId, workspaceId, number));
    }

    /** [Pas encore] sur le chantier N (F-206). */
    @PostMapping("/chantiers/{number}/close/dismiss")
    public SubjectJourneyResponse dismissChantierClose(@PathVariable UUID workspaceId, @PathVariable int number) {
        UUID userId = currentUser.requireId();
        return respond(userId, workspaceId, service.dismissClose(userId, workspaceId, number));
    }

    private SubjectJourneyResponse respond(UUID userId, UUID workspaceId, SubjectJourney journey) {
        SubjectJourney decorated = service.withOpenChantiers(journey);
        return SubjectJourneyResponse.from(decorated, service.waitsOn(userId, workspaceId, decorated),
                service.closedChantierCount(userId, workspaceId));
    }

    /** Le titre du chantier qui s'ouvre (facultatif, F-206). */
    public record OpenRequest(String title) {
    }

    /**
     * Le mode voulu : {@code LIBRE} ou {@code GUIDE} ; {@code title} titre le chantier qui s'ouvre
     * (facultatif, SF-176-11).
     */
    public record ModeRequest(String mode, String title) {
    }

    /** La version du plan que l'utilisateur a sous les yeux ; {@code null} = la courante. */
    public record ValidateRequest(Integer version) {
    }
}
