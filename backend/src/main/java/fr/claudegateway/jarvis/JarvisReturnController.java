package fr.claudegateway.jarvis;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>De retour au terminal</b> (F-231 / SF-231-06) : l'encart « Pendant ton absence, via Jarvis » d'un terminal, et
 * le compteur « À envoyer (n) » du tableau du client. L'identité vient <b>exclusivement</b> du {@link CurrentUser} ;
 * le terminal d'autrui est introuvable (404).
 */
@RestController
public class JarvisReturnController {

    private final JarvisReturnService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisReturnController(JarvisReturnService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Ce que Jarvis a fait sur ce terminal depuis {@code since} (dernière visite de l'appareil, ISO-8601). */
    @GetMapping("/workspaces/{id}/jarvis/absence")
    public JarvisReturnService.Absence absence(@PathVariable UUID id,
            @RequestParam(name = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since) {
        atelierAccess.requireTerminalAccess(id);
        return service.absence(currentUser.requireId(), id, since);
    }

    /** Les encarts mail ou message pas encore marqués envoyés, pour les sujets d'un poste ({@code hostId}). */
    @GetMapping("/jarvis/to-send")
    public JarvisReturnService.ToSendList toSend(@RequestParam(name = "hostId", required = false) UUID hostId) {
        atelierAccess.requireRunnerAccess();
        return service.toSend(currentUser.requireId(), hostId);
    }
}
