package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enregistre l'activité observée d'un runner (F-38 / SF-38-02) en rafraîchissant
 * {@code runner_tokens.last_seen_at} : à l'établissement du canal WebSocket puis à chaque heartbeat.
 * Écrit dans la base <b>partagée</b> par les deux replicas — c'est ce qui rend le statut
 * « runner connecté » correct même quand la socket vit sur l'autre pod.
 *
 * <p>Aucune vérification d'appartenance ici : l'identité vient d'un jeton déjà authentifié par le
 * handshake ({@link RunnerTokenAuthenticator}), et un jeton inconnu est simplement ignoré.</p>
 */
@Service
public class RunnerHeartbeatService {

    private final RunnerTokenRepository tokenRepository;

    public RunnerHeartbeatService(RunnerTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    /** Met {@code last_seen_at} à {@code now()} pour ce jeton. No-op si le jeton n'existe plus. */
    @Transactional
    public void touch(UUID tokenId) {
        tokenRepository.findById(tokenId)
                .ifPresent(token -> token.setLastSeenAt(OffsetDateTime.now()));
    }
}
