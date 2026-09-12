package fr.claudegateway.terminals;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.terminals.dto.LiveTerminalClaimRequest;
import fr.claudegateway.terminals.dto.LiveTerminalsResponse;
import fr.claudegateway.terminals.dto.TerminalPreview;
import jakarta.validation.Valid;

/**
 * Les <b>terminaux vivants</b> d'un utilisateur (F-70 / SF-70-01).
 *
 * <p>Trois gestes, et rien d'autre : prendre (ou tenir) une place, la libérer, lire le registre.
 * L'identité vient toujours du {@link CurrentUser} — le corps de la requête ne porte que
 * l'identifiant d'<b>onglet</b>, qui n'a de sens qu'associé à cet utilisateur.</p>
 *
 * <p><b>Aucun gating d'Atelier ici</b>, volontairement : le registre ne donne accès à rien, il
 * compte. Un utilisateur sans le droit Forge ne peut de toute façon ouvrir aucun projet, donc
 * {@code requireOwned} le renvoie déjà en 404 ; ajouter un 403 sur le compteur n'ajouterait qu'un
 * chemin d'erreur de plus à un écran qui, à ce stade, affiche déjà l'appel à souscrire.</p>
 */
@RestController
public class LiveTerminalController {

    private final LiveTerminalService liveTerminals;
    private final CurrentUser currentUser;

    public LiveTerminalController(LiveTerminalService liveTerminals, CurrentUser currentUser) {
        this.liveTerminals = liveTerminals;
        this.currentUser = currentUser;
    }

    /**
     * Prend une place pour cet onglet, ou renouvelle la sienne (battement de cœur). Un <b>seul</b>
     * appel pour les deux : l'écran n'a pas à savoir s'il est le premier.
     *
     * @throws LiveTerminalLimitReachedException 409 si quatre terminaux vivent déjà
     */
    @PostMapping("/workspaces/{id}/terminal/live")
    public LiveTerminalsResponse claim(@PathVariable UUID id,
            @Valid @RequestBody LiveTerminalClaimRequest request) {
        // L'APERÇU VOYAGE AVEC LE BATTEMENT DE CŒUR (F-76 / SF-76-01) : pas d'endpoint de plus,
        // pas de canal de plus. Un appel qui n'en porte pas laisse en place celui de la fiche —
        // ne rien dire n'est pas dire qu'il ne se passe rien.
        TerminalPreview preview = request.hasPreview()
                ? new TerminalPreview(TerminalActivity.parse(request.activity()),
                        request.activityDetail(), request.previewLines(), null)
                : null;
        return liveTerminals.claim(currentUser.requireId(), id, request.sessionId(), preview);
    }

    /**
     * Libère la place de cet onglet. Idempotent : le geste part aussi à la fermeture de l'onglet,
     * où personne ne pourra lire la réponse.
     */
    @DeleteMapping("/workspaces/{id}/terminal/live")
    public ResponseEntity<Void> release(@PathVariable UUID id,
            @RequestParam("sessionId") String sessionId) {
        liveTerminals.release(currentUser.requireId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    /** Le registre, sans rien prendre : de quoi nommer les terminaux à fermer. */
    @GetMapping("/terminals/live")
    public LiveTerminalsResponse live() {
        return liveTerminals.snapshot(currentUser.requireId());
    }
}
