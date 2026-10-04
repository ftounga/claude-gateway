package fr.claudegateway.atelier.actions;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * Les attentes d'un terminal (F-154 / SF-154-01, F-175 / SF-175-01).
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; aucun identifiant de compte ne
 * transite par la requête. L'isolation est appliquée par les services ({@code requireOwned} ou
 * lecture sous {@code user_id} d'abord : 404 sur un projet d'autrui).</p>
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/actions")
public class TerminalActionController {

    private final TerminalActionService service;
    private final TerminalActionQueryService queries;
    private final CurrentUser currentUser;

    public TerminalActionController(TerminalActionService service,
                                    TerminalActionQueryService queries,
                                    CurrentUser currentUser) {
        this.service = service;
        this.queries = queries;
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

    /** Le tableau : ce terminal, puis le reste du poste, et les compteurs (F-175 / SF-175-01). */
    @GetMapping("/board")
    public TerminalActionBoardResponse board(@PathVariable UUID workspaceId) {
        return queries.board(currentUser.requireId(), workspaceId);
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

    /** Édition d'une attente ouverte : seuls les champs donnés changent (F-175 / SF-175-01). */
    @PatchMapping("/{actionId}")
    public TerminalActionResponse edit(@PathVariable UUID workspaceId,
                                       @PathVariable UUID actionId,
                                       @RequestBody EditRequest request) {
        return TerminalActionResponse.from(service.edit(
                currentUser.requireId(), workspaceId, actionId,
                request.description(), request.blocks(), request.person(), request.kind()));
    }

    /** Changement d'état : À faire ↔ Demandé → Fait / Annulé (F-175 / SF-175-01). */
    @PostMapping("/{actionId}/status")
    public TerminalActionResponse status(@PathVariable UUID workspaceId,
                                         @PathVariable UUID actionId,
                                         @RequestBody StatusRequest request) {
        return TerminalActionResponse.from(service.changeStatus(
                currentUser.requireId(), workspaceId, actionId,
                request.status(), request.note(), request.requestedTo(), request.channel()));
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

    /** « Rétablir » — la fermeture s'était trompée. L'attente revient dans son état d'avant. */
    @PostMapping("/{actionId}/reopen")
    public TerminalActionResponse reopen(@PathVariable UUID workspaceId,
                                         @PathVariable UUID actionId) {
        return TerminalActionResponse.from(
                service.reopen(currentUser.requireId(), workspaceId, actionId));
    }

    /** [Confirmer] — la fermeture proposée par l'agent est validée (F-175 / SF-175-02). */
    @PostMapping("/{actionId}/proposal/confirm")
    public TerminalActionResponse confirmProposal(@PathVariable UUID workspaceId,
                                                  @PathVariable UUID actionId) {
        return TerminalActionResponse.from(
                service.confirmProposal(currentUser.requireId(), workspaceId, actionId));
    }

    /** [Pas encore] — la proposition est écartée, l'attente reste ouverte (F-175 / SF-175-02). */
    @PostMapping("/{actionId}/proposal/dismiss")
    public TerminalActionResponse dismissProposal(@PathVariable UUID workspaceId,
                                                  @PathVariable UUID actionId) {
        return TerminalActionResponse.from(
                service.dismissProposal(currentUser.requireId(), workspaceId, actionId));
    }

    private static String reasonOf(SettleRequest request) {
        return request == null ? null : request.reason();
    }

    /** Une action ajoutée à la main. */
    public record CreateRequest(UUID subjectId, String description, String blocks,
                                String person, TerminalActionKind kind) {
    }

    /** Une édition : {@code null} = inchangé ; chaîne vide = effacé (sauf la description). */
    public record EditRequest(String description, String blocks, String person,
                              TerminalActionKind kind) {
    }

    /** Un changement d'état ; {@code note} est la raison d'une fermeture. */
    public record StatusRequest(TerminalActionStatus status, String note,
                                String requestedTo, String channel) {
    }

    /** La phrase qui ferme ou annule. Facultative : l'utilisateur n'a rien à justifier. */
    public record SettleRequest(String reason) {
    }
}
