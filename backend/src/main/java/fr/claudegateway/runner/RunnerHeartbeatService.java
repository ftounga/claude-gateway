package fr.claudegateway.runner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p><b>Échéance glissante</b> (F-194 / SF-194-01) : un jeton encore valide est repoussé de
 * {@code app.runner.token-ttl} à chaque signe de vie, dans la même écriture que
 * {@code last_seen_at}. Sans cela, tout poste appairé expirait à date fixe, même utilisé la veille
 * (les trois postes du PO le 2026-10-12). Un jeton révoqué ou déjà expiré n'est jamais prolongé.</p>
 */
@Service
public class RunnerHeartbeatService {

    private final RunnerTokenRepository tokenRepository;
    private final Duration tokenTtl;

    public RunnerHeartbeatService(RunnerTokenRepository tokenRepository,
            @Value("${app.runner.token-ttl:P30D}") Duration tokenTtl) {
        this.tokenRepository = tokenRepository;
        this.tokenTtl = tokenTtl;
    }

    /**
     * Met {@code last_seen_at} à {@code now()} pour ce jeton et, s'il est encore valide, repousse son
     * échéance à {@code now() + token-ttl}. No-op si le jeton n'existe plus.
     */
    @Transactional
    public void touch(UUID tokenId) {
        tokenRepository.findById(tokenId).ifPresent(token -> {
            OffsetDateTime now = OffsetDateTime.now();
            token.setLastSeenAt(now);
            if (token.isValidAt(now)) {
                token.setExpiresAt(now.plus(tokenTtl));
            }
        });
    }
}
