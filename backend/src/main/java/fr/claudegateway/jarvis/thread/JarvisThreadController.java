package fr.claudegateway.jarvis.thread;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le fil Jarvis</b> (F-245 / SF-245-01) : {@code GET /api/jarvis/thread} le crée s'il le faut et rend son
 * identifiant. L'identité vient <b>exclusivement</b> du {@link CurrentUser} : aucun paramètre ne désigne un compte.
 * Le fil se lit ensuite par les routes de chat existantes ({@code /api/workspaces/{id}/chat}), filtrées sur le
 * {@code user_id} du principal : le fil d'autrui y rend 404.
 */
@RestController
public class JarvisThreadController {

    private final JarvisThreadService threads;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisThreadController(JarvisThreadService threads, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.threads = threads;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** La réponse : l'identifiant et le nom du fil. */
    public record JarvisThreadResponse(UUID id, String name) {
    }

    @GetMapping("/jarvis/thread")
    public JarvisThreadResponse thread() {
        atelierAccess.requireAccess();
        Workspace thread = threads.open(currentUser.requireId());
        return new JarvisThreadResponse(thread.getId(), thread.getName());
    }
}
