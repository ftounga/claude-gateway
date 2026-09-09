package fr.claudegateway.runner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Appairage d'un runner (F-38 / SF-38-01, réécrit par F-48 / SF-48-01). L'utilisateur génère un code
 * court à usage unique pour un de ses <b>postes</b> ({@link #createPairingCode}) ; le runner l'échange
 * contre un jeton ({@link #redeem}). Le code est stocké haché, à durée de vie courte, consommé à
 * l'échange.
 *
 * <p><b>Un seul appairage par machine</b> : c'est tout l'objet de F-48. Ouvrir un projet de plus sous
 * la racine du poste ne demande ni code, ni runner, ni connexion supplémentaires.</p>
 */
@Service
public class RunnerPairingService {

    private final RunnerPairingCodeRepository codeRepository;
    private final RunnerTokenService tokenService;
    private final TokenHasher tokenHasher;
    private final RunnerPairingCodeGenerator codeGenerator;
    private final RunnerHostService hostService;
    private final Duration codeTtl;

    public RunnerPairingService(
            RunnerPairingCodeRepository codeRepository,
            RunnerTokenService tokenService,
            TokenHasher tokenHasher,
            RunnerPairingCodeGenerator codeGenerator,
            RunnerHostService hostService,
            @Value("${app.runner.pairing-code-ttl:PT5M}") Duration codeTtl) {
        this.codeRepository = codeRepository;
        this.tokenService = tokenService;
        this.tokenHasher = tokenHasher;
        this.codeGenerator = codeGenerator;
        this.hostService = hostService;
        this.codeTtl = codeTtl;
    }

    /**
     * Génère un code d'appairage pour un poste de l'utilisateur. Vérifie l'appartenance (404 sinon).
     * Le clair renvoyé n'est jamais réexposé ensuite.
     */
    @Transactional
    public PairingCode createPairingCode(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);
        String clear = codeGenerator.generate();
        RunnerPairingCode code = codeRepository.save(RunnerPairingCode.builder()
                .userId(userId)
                .hostId(hostId)
                .codeHash(tokenHasher.sha256Hex(clear))
                .expiresAt(OffsetDateTime.now().plus(codeTtl))
                .build());
        return new PairingCode(clear, code.getExpiresAt());
    }

    /**
     * Échange un code d'appairage contre un jeton runner. Consomme le code (usage unique) et
     * enregistre <b>sur le poste</b> ce que le runner déclare de sa machine : le dernier segment de
     * sa racine (F-38 / SF-38-15), son système, et les droits sous lesquels il tourne (SF-38-18).
     *
     * @throws PairingInvalidException si le code est inconnu, expiré ou déjà consommé
     */
    @Transactional
    public PairedRunner redeem(String rawCode, String label, String rootName, String os,
            boolean elevated) {
        String hash = tokenHasher.sha256Hex(normalizeCode(rawCode));
        RunnerPairingCode code = codeRepository.findByCodeHash(hash)
                .filter(c -> c.isUsableAt(OffsetDateTime.now()))
                .orElseThrow(PairingInvalidException::new);
        code.setConsumedAt(OffsetDateTime.now());

        RunnerTokenService.IssuedToken issued =
                tokenService.issue(code.getUserId(), code.getHostId(), label);
        hostService.recordDeclaration(code.getHostId(), rootName, os, elevated);
        return new PairedRunner(issued.clearToken(), code.getHostId(), issued.token().getExpiresAt());
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    /** Code d'appairage généré : clair (éphémère) et expiration. */
    public record PairingCode(String code, OffsetDateTime expiresAt) {
    }

    /** Résultat d'appairage : jeton en clair (éphémère), poste et expiration du jeton. */
    public record PairedRunner(String token, UUID hostId, OffsetDateTime expiresAt) {
    }
}
