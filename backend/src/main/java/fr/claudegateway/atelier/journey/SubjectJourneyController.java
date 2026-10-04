package fr.claudegateway.atelier.journey;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** Le mode et la phase du sujet ; Libre s'il n'a jamais été décidé. */
    @GetMapping
    public SubjectJourneyResponse get(@PathVariable UUID workspaceId) {
        return SubjectJourneyResponse.from(service.get(currentUser.requireId(), workspaceId));
    }

    /** Le menu du terminal : Libre ou Guidé (SF-176-01). */
    @PutMapping("/mode")
    public SubjectJourneyResponse setMode(@PathVariable UUID workspaceId, @RequestBody ModeRequest request) {
        return SubjectJourneyResponse.from(
                service.setMode(currentUser.requireId(), workspaceId, request == null ? null : request.mode()));
    }

    /** Le mode voulu : {@code LIBRE} ou {@code GUIDE}. */
    public record ModeRequest(String mode) {
    }
}
