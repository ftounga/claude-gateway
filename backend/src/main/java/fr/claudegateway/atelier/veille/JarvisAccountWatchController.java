package fr.claudegateway.atelier.veille;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisWatchSummaryView;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les veilles du compte</b> (F-230 / SF-230-04) — pour le tableau du client. L'identité vient
 * <b>exclusivement</b> du {@link CurrentUser} ; seules les veilles de ce compte sont rendues.
 */
@RestController
@RequestMapping("/jarvis/watches")
public class JarvisAccountWatchController {

    private final JarvisWatchService service;
    private final CurrentUser currentUser;

    public JarvisAccountWatchController(JarvisWatchService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Toutes les veilles du compte, ou celles des sujets d'un poste ({@code hostId}). */
    @GetMapping
    public List<JarvisWatchSummaryView> list(@RequestParam(name = "hostId", required = false) UUID hostId) {
        return service.listForAccount(currentUser.requireId(), hostId);
    }
}
