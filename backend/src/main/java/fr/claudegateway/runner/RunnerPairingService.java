package fr.claudegateway.runner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.WorkspaceService;

/**
 * Appairage d'un runner (F-38 / SF-38-01). L'utilisateur génère un code court à usage unique pour un
 * de ses workspaces ({@link #createPairingCode}) ; le runner l'échange contre un jeton
 * ({@link #redeem}). Le code est stocké haché, à durée de vie courte, consommé à l'échange.
 */
@Service
public class RunnerPairingService {

    private final RunnerPairingCodeRepository codeRepository;
    private final RunnerTokenService tokenService;
    private final TokenHasher tokenHasher;
    private final RunnerPairingCodeGenerator codeGenerator;
    private final WorkspaceService workspaceService;
    private final Duration codeTtl;

    public RunnerPairingService(
            RunnerPairingCodeRepository codeRepository,
            RunnerTokenService tokenService,
            TokenHasher tokenHasher,
            RunnerPairingCodeGenerator codeGenerator,
            WorkspaceService workspaceService,
            @Value("${app.runner.pairing-code-ttl:PT5M}") Duration codeTtl) {
        this.codeRepository = codeRepository;
        this.tokenService = tokenService;
        this.tokenHasher = tokenHasher;
        this.codeGenerator = codeGenerator;
        this.workspaceService = workspaceService;
        this.codeTtl = codeTtl;
    }

    /**
     * Génère un code d'appairage pour un workspace de l'utilisateur. Vérifie l'appartenance
     * (404 sinon). Le clair renvoyé n'est jamais réexposé ensuite.
     */
    @Transactional
    public PairingCode createPairingCode(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        String clear = codeGenerator.generate();
        RunnerPairingCode code = codeRepository.save(RunnerPairingCode.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .codeHash(tokenHasher.sha256Hex(clear))
                .expiresAt(OffsetDateTime.now().plus(codeTtl))
                .build());
        return new PairingCode(clear, code.getExpiresAt());
    }

    /**
     * Échange un code d'appairage contre un jeton runner. Consomme le code (usage unique).
     *
     * @throws PairingInvalidException si le code est inconnu, expiré ou déjà consommé
     */
    @Transactional
    public PairedRunner redeem(String rawCode, String label) {
        return redeem(rawCode, label, null);
    }

    /**
     * Variante qui enregistre en plus le <b>nom du dossier</b> déclaré par le runner
     * (F-38 / SF-38-15). Le chemin absolu n'est jamais transmis ni stocké : le runner n'envoie que
     * le dernier segment, et la gateway le réduit de nouveau par précaution.
     */
    @Transactional
    public PairedRunner redeem(String rawCode, String label, String rootName) {
        String hash = tokenHasher.sha256Hex(normalizeCode(rawCode));
        RunnerPairingCode code = codeRepository.findByCodeHash(hash)
                .filter(c -> c.isUsableAt(OffsetDateTime.now()))
                .orElseThrow(PairingInvalidException::new);
        code.setConsumedAt(OffsetDateTime.now());

        RunnerTokenService.IssuedToken issued =
                tokenService.issue(code.getUserId(), code.getWorkspaceId(), label);
        // Libellé d'affichage : un échec ici ne doit pas faire échouer l'appairage lui-même.
        workspaceService.recordRunnerRootName(code.getWorkspaceId(), rootName);
        return new PairedRunner(issued.clearToken(), code.getWorkspaceId(), issued.token().getExpiresAt());
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    /** Code d'appairage généré : clair (éphémère) et expiration. */
    public record PairingCode(String code, OffsetDateTime expiresAt) {
    }

    /** Résultat d'appairage : jeton en clair (éphémère), workspace et expiration du jeton. */
    public record PairedRunner(String token, UUID workspaceId, OffsetDateTime expiresAt) {
    }
}
