package fr.claudegateway.runner.rupture;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * <b>Le journal des ruptures</b> (F-161 / SF-161-03) : l'unique point d'écriture.
 *
 * <p><b>Best-effort, sans exception.</b> Un journal perdu vaut mieux qu'un canal bloqué : si
 * l'écriture échoue, la fermeture doit se poursuivre comme si de rien n'était. C'est la raison
 * d'être de ce point unique — sans lui, la même garde devrait être recopiée en cinq endroits, et
 * la première copie oubliée transformerait une panne de base en canal qui ne se ferme plus.</p>
 */
@Service
public class RunnerDisconnectJournal {

    private static final Logger log = LoggerFactory.getLogger(RunnerDisconnectJournal.class);

    /** Au-delà, on tronque : la base n'a pas à porter le roman d'un pair bavard. */
    private static final int MAX_STATUS = 120;

    private final RunnerDisconnectRepository repository;
    private final boolean enabled;

    public RunnerDisconnectJournal(RunnerDisconnectRepository repository,
            @Value("${app.runner.journal-disconnects:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    /**
     * Consigne une rupture. <b>Ne lève jamais.</b>
     *
     * @param connectedAt premier contact du canal ; {@code null} si inconnu
     * @param lastSeenAt  dernier signe du poste ; {@code null} si inconnu
     * @param closeStatus le {@code CloseStatus} du WebSocket, ou {@code null}
     * @param callsInFlight appels en cours au moment de la rupture — le cas qui coûte
     */
    public void record(UUID userId, UUID hostId, RunnerDisconnectCause cause,
                       RunnerTransport transport, Instant connectedAt, Instant lastSeenAt,
                       String closeStatus, int callsInFlight) {
        if (!enabled || userId == null || hostId == null || cause == null) {
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            repository.save(RunnerDisconnect.builder()
                    .userId(userId)
                    .hostId(hostId)
                    .cause(cause)
                    .transport(transport == null ? RunnerTransport.POLLING : transport)
                    .livedMs(millisSince(connectedAt))
                    .silentMs(millisSince(lastSeenAt))
                    .closeStatus(trim(closeStatus))
                    .callsInFlight(Math.max(0, callsInFlight))
                    .createdAt(now)
                    .build());
        } catch (RuntimeException e) {
            // On perd une ligne de journal ; on ne bloque pas une fermeture de canal.
            log.warn("Rupture non consignée (poste={}, cause={})", hostId, cause, e);
        }
    }

    private static Long millisSince(Instant from) {
        return from == null ? null : Math.max(0L, Duration.between(from, Instant.now()).toMillis());
    }

    private static String trim(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.length() <= MAX_STATUS ? status : status.substring(0, MAX_STATUS);
    }
}
