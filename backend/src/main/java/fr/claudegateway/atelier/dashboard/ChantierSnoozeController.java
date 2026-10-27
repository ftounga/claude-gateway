package fr.claudegateway.atelier.dashboard;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Reporter un chantier</b> (F-220 / SF-220-05). L'identité vient <b>exclusivement</b> du {@link CurrentUser} ;
 * l'isolation est appliquée par le service. Aucune logique ici.
 */
@RestController
public class ChantierSnoozeController {

    private final ChantierSnoozeService service;
    private final CurrentUser currentUser;

    public ChantierSnoozeController(ChantierSnoozeService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Le report demandé : de 1 à 30 jours. */
    public record SnoozeRequest(Integer days) {
    }

    /** [Reporter] le chantier N. */
    @PostMapping("/workspaces/{workspaceId}/journey/chantiers/{number}/snooze")
    public ChantierSnoozeService.SnoozeView snooze(@PathVariable UUID workspaceId, @PathVariable int number,
            @RequestBody(required = false) SnoozeRequest request) {
        return service.snooze(currentUser.requireId(), workspaceId, number, request == null ? null : request.days());
    }

    /** [Ne plus reporter] le chantier N. */
    @DeleteMapping("/workspaces/{workspaceId}/journey/chantiers/{number}/snooze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsnooze(@PathVariable UUID workspaceId, @PathVariable int number) {
        service.unsnooze(currentUser.requireId(), workspaceId, number);
    }
}
