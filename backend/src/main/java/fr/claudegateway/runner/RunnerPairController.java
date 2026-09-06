package fr.claudegateway.runner;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.runner.RunnerPairingService.PairedRunner;
import fr.claudegateway.runner.dto.PairRequest;
import fr.claudegateway.runner.dto.PairResponse;
import jakarta.validation.Valid;

/**
 * Point d'échange d'appairage du runner (F-38 / SF-38-01) : {@code POST /runner/pair}. Authentifié
 * par le <b>code d'appairage</b> lui-même (pas de JWT) — servi par la chaîne de sécurité dédiée
 * {@code /runner/**} ({@link RunnerSecurityConfig}), isolée de la chaîne utilisateur.
 */
@RestController
@RequestMapping("/runner")
public class RunnerPairController {

    private final RunnerPairingService pairingService;

    public RunnerPairController(RunnerPairingService pairingService) {
        this.pairingService = pairingService;
    }

    @PostMapping("/pair")
    public PairResponse pair(@Valid @RequestBody PairRequest request) {
        PairedRunner paired = pairingService.redeem(request.code(), request.label(), request.rootName(),
                Boolean.TRUE.equals(request.elevated()));
        return new PairResponse(paired.token(), paired.workspaceId(), paired.expiresAt());
    }
}
