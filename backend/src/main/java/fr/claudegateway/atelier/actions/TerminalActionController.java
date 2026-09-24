package fr.claudegateway.atelier.actions;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * Les actions à faire d'un terminal (F-154 / SF-154-01).
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; aucun identifiant de compte ne
 * transite par la requête. L'isolation est appliquée par le service ({@code requireOwned} d'abord :
 * 404 sur un projet d'autrui).</p>
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/actions")
public class TerminalActionController {

    private final TerminalActionService service;
    private final CurrentUser currentUser;

    public TerminalActionController(TerminalActionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Le menu du terminal. {@code openOnly=false} montre aussi ce qui a été fermé. */
    @GetMapping
    public List<TerminalActionResponse> list(@PathVariable UUID workspaceId,
                                             @RequestParam(defaultValue = "true") boolean openOnly) {
        return service.list(currentUser.requireId(), workspaceId, openOnly).stream()
                .map(TerminalActionResponse::from)
                .toList();
    }

    /** Ce que l'utilisateur ajoute lui-même — l'agent passe par son outil (SF-154-02). */
    @PostMapping
    public TerminalActionResponse create(@PathVariable UUID workspaceId,
                                         @RequestBody CreateRequest request) {
        return TerminalActionResponse.from(service.create(
                currentUser.requireId(),
                workspaceId,
                request.subjectId(),
                request.description(),
                request.blocks(),
                request.person(),
                request.kind()));
    }

    /** « C'est fait. » */
    @PostMapping("/{actionId}/close")
    public TerminalActionResponse close(@PathVariable UUID workspaceId,
                                        @PathVariable UUID actionId,
                                        @RequestBody(required = false) SettleRequest request) {
        return TerminalActionResponse.from(service.close(
                currentUser.requireId(), workspaceId, actionId, reasonOf(request)));
    }

    /** « Ça n'avait pas lieu d'être. » */
    @PostMapping("/{actionId}/cancel")
    public TerminalActionResponse cancel(@PathVariable UUID workspaceId,
                                         @PathVariable UUID actionId,
                                         @RequestBody(required = false) SettleRequest request) {
        return TerminalActionResponse.from(service.cancel(
                currentUser.requireId(), workspaceId, actionId, reasonOf(request)));
    }

    /** « Rétablir » — la fermeture s'était trompée. */
    @PostMapping("/{actionId}/reopen")
    public TerminalActionResponse reopen(@PathVariable UUID workspaceId,
                                         @PathVariable UUID actionId) {
        return TerminalActionResponse.from(
                service.reopen(currentUser.requireId(), workspaceId, actionId));
    }

    private static String reasonOf(SettleRequest request) {
        return request == null ? null : request.reason();
    }

    /** Une action ajoutée à la main. */
    public record CreateRequest(UUID subjectId, String description, String blocks,
                                String person, TerminalActionKind kind) {
    }

    /** La phrase qui ferme ou annule. Facultative : l'utilisateur n'a rien à justifier. */
    public record SettleRequest(String reason) {
    }
}
