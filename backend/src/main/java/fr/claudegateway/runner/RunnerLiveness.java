package fr.claudegateway.runner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>Le battement fait foi</b> (F-97 / SF-97-01) : seule définition, dans toute la gateway, de
 * « ce poste est vivant ».
 *
 * <p>Une socket enregistrée sert à <b>acheminer</b> ; elle ne prouve pas que le runner est vivant. Un
 * poste qui meurt sans fermer sa connexion — veille, Wi-Fi coupé, VPN tombé — laisse une socket à
 * moitié ouverte que personne ne retire, alors que son battement, lui, s'arrête bien. Le statut, le
 * routage des appels, le balayage des sockets et la ré-annonce de présence lisent donc tous la même
 * chose : un {@code last_seen_at} plus récent que {@code app.runner.heartbeat.stale-after}.</p>
 *
 * <p>La lecture se fait dans la base <b>partagée</b>, même pour une socket locale : le battement est
 * écrit par le pod qui porte la socket, et un pod pair n'a que la base. Une seule source, une seule
 * vérité.</p>
 */
@Component
public class RunnerLiveness {

    private final RunnerTokenRepository tokenRepository;
    private final Duration staleAfter;

    public RunnerLiveness(RunnerTokenRepository tokenRepository,
            @Value("${app.runner.heartbeat.stale-after:PT90S}") Duration staleAfter) {
        this.tokenRepository = tokenRepository;
        this.staleAfter = staleAfter;
    }

    /** Vrai si ce dernier battement est plus récent que {@code stale-after}. Nul = jamais vu = périmé. */
    public boolean isFresh(OffsetDateTime lastSeenAt) {
        return lastSeenAt != null && lastSeenAt.isAfter(OffsetDateTime.now().minus(staleAfter));
    }

    /** Le poste de cet utilisateur a-t-il battu récemment ? Lecture filtrée {@code user_id} + {@code host_id}. */
    public boolean isAlive(UUID userId, UUID hostId) {
        return isFresh(lastSeenAt(userId, hostId));
    }

    /**
     * Le <b>dernier battement</b> de ce poste, ou {@code null} s'il n'a jamais été vu. Même lecture
     * filtrée que {@link #isAlive} — exposée pour que l'appelant qui doit <b>dire</b> l'ancienneté
     * (la porte de F-161) n'ait pas à interroger deux fois, au risque de deux vérités.
     */
    public OffsetDateTime lastSeenAt(UUID userId, UUID hostId) {
        return tokenRepository.findLastSeenAt(userId, hostId);
    }

    /**
     * Même question pour le <b>routage</b>, dont la cible ne porte que le poste (déjà vérifié possédé
     * en amont). Voir {@link RunnerTokenRepository#findLastSeenAtForRouting}.
     */
    public boolean isAliveForRouting(UUID hostId) {
        return isFresh(tokenRepository.findLastSeenAtForRouting(hostId));
    }
}
