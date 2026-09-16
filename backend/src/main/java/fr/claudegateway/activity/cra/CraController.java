package fr.claudegateway.activity.cra;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.activity.dto.CraRecapResponse;
import fr.claudegateway.activity.dto.CraRequest;
import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * Suivi d'activité et de revenu (F-124) — <b>CRA par message</b> (SF-124-03) : l'utilisateur déclare
 * ses jours en langage naturel, le modèle extrait, la Gateway rapproche/valide/persiste et récapitule.
 *
 * <p>Endpoint <b>JWT</b> gardé par le droit Forge. L'identité vient du {@link CurrentUser} ; le
 * rapprochement et la persistance sont isolés par {@code user_id} (+ {@code host_id}).</p>
 */
@RestController
@RequestMapping("/activity")
public class CraController {

    private final CraService craService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public CraController(CraService craService, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.craService = craService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Interprète un message de CRA et rend le récapitulatif de ce qui a été compris et écrit. */
    @PostMapping("/cra")
    public CraRecapResponse submit(@Valid @RequestBody CraRequest request) {
        atelierAccess.requireRunnerAccess();
        UUID userId = currentUser.requireId();
        return CraRecapResponse.from(craService.submit(userId, request.message()));
    }
}
