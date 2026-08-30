package fr.claudegateway.runner.channel;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import fr.claudegateway.runner.RunnerIdentity;

/**
 * Cycle de vie des « connexions » de <b>repli long-polling</b> (F-38 / SF-38-09).
 *
 * <p>Un runner dont le WebSocket est tué par un proxy d'entreprise se rabat sur
 * {@code POST /runner/poll}. Vu du reste de la plateforme, cela doit être une connexion comme une
 * autre : elle s'enregistre dans le {@link RunnerRegistry} avec le <b>même</b> record
 * {@link RunnerConnection} (donc {@code GET /workspaces/{id}/runner/status} et
 * {@link RunnerRegistry#findLocal} restent exacts) et se branche sur le
 * {@link RunnerCallDispatcher} comme une socket. Aucun type de message nouveau n'est introduit :
 * seules les enveloppes du contrat circulent, dans un corps HTTP au lieu d'une trame WS.</p>
 *
 * <p><b>Preuve de vie</b> : le poll <i>est</i> le heartbeat. Un canal qui n'est plus interrogé
 * pendant {@code app.runner.poll.idle-timeout-ms} est fermé par le balayage périodique, exactement
 * comme une socket coupée — appels en vol terminés en {@code runner_unavailable}, présence retirée.</p>
 */
@Component
public class RunnerPollingSessions {

    private static final Logger log = LoggerFactory.getLogger(RunnerPollingSessions.class);

    private final RunnerRegistry registry;
    private final RunnerCallDispatcher dispatcher;
    private final long idleTimeoutMs;
    /** Nœud hébergeant ces canaux ; informatif (le routage repose sur la carte locale du registre). */
    private final String nodeId = UUID.randomUUID().toString();

    private final Map<UUID, LongPollingRunnerOutbound> channels = new ConcurrentHashMap<>();

    public RunnerPollingSessions(RunnerRegistry registry, RunnerCallDispatcher dispatcher,
            @Value("${app.runner.poll.idle-timeout-ms:90000}") long idleTimeoutMs) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.idleTimeoutMs = idleTimeoutMs > 0 ? idleTimeoutMs : 90_000L;
    }

    /**
     * Canal du runner qui se présente, créé au besoin. Un canal déjà ouvert pour le <b>même jeton</b>
     * est réutilisé tel quel ; sinon l'ancien est fermé <b>après</b> que le nouveau a pris sa place
     * dans la carte, pour que le nettoyage de l'ancien ne puisse pas effacer le nouveau.
     */
    public LongPollingRunnerOutbound open(RunnerIdentity identity) {
        LongPollingRunnerOutbound current = channels.get(identity.workspaceId());
        if (current != null && current.isOpen() && current.tokenId().equals(identity.tokenId())) {
            refreshPresence(current);
            return current;
        }
        LongPollingRunnerOutbound channel = new LongPollingRunnerOutbound(
                identity.workspaceId(), identity.userId(), identity.tokenId(), this::cleanup);
        LongPollingRunnerOutbound previous = channels.put(identity.workspaceId(), channel);
        if (previous != null) {
            // La carte porte déjà le nouveau canal : le nettoyage de l'ancien ne peut plus l'effacer.
            previous.close();
        }
        registry.register(connectionOf(channel));
        dispatcher.attachChannel(identity, channel);
        log.debug("Canal runner long-polling ouvert (workspace={})", identity.workspaceId());
        return channel;
    }

    /** Canal ouvert de ce runner, s'il y en a un — sans en créer. */
    public Optional<LongPollingRunnerOutbound> find(RunnerIdentity identity) {
        LongPollingRunnerOutbound channel = channels.get(identity.workspaceId());
        return channel != null && channel.isOpen() && channel.tokenId().equals(identity.tokenId())
                ? Optional.of(channel)
                : Optional.empty();
    }

    /**
     * Ferme le canal de ce runner (arrêt propre côté runner, {@code POST /runner/disconnect}). Ne
     * ferme que <b>son</b> canal : un jeton ne peut pas raccrocher la connexion d'un autre.
     *
     * @return vrai si un canal a effectivement été fermé
     */
    public boolean close(RunnerIdentity identity) {
        return find(identity).map(channel -> {
            channel.close();
            return true;
        }).orElse(false);
    }

    /**
     * Ferme les canaux qui ne sont plus interrogés. Sans ce balayage, un runner disparu (poste
     * éteint, proxy qui coupe aussi les POST) resterait « connecté » indéfiniment et la boucle
     * tool-use attendrait un runner qui ne viendra plus.
     */
    @Scheduled(fixedDelayString = "${app.runner.poll.sweep-ms:15000}")
    void sweepIdleChannels() {
        Instant limit = Instant.now().minusMillis(idleTimeoutMs);
        for (LongPollingRunnerOutbound channel : List.copyOf(channels.values())) {
            if (channel.lastPollAt().isBefore(limit)) {
                log.info("Canal runner long-polling inactif fermé (workspace={})", channel.workspaceId());
                channel.close();
            }
        }
    }

    /**
     * Nettoyage d'un canal fermé : retrait de la carte, terminaison des appels en vol, puis retrait
     * de la présence — dans cet ordre, comme à la fermeture d'une socket (aucun appel ne doit
     * attendre un canal mort une fois la présence disparue). Idempotent.
     */
    private void cleanup(LongPollingRunnerOutbound channel) {
        channels.remove(channel.workspaceId(), channel);
        dispatcher.detachChannel(channel.workspaceId(), channel);
        // Garde anti-course : on ne retire du registre que si la présence enregistrée est encore
        // exactement la nôtre. Sans elle, la fin d'un polling effacerait la connexion WebSocket d'un
        // runner qui vient de se reconnecter avec le même jeton (la garde par tokenId du registre ne
        // distingue pas deux connexions du même jeton).
        RunnerConnection mine = connectionOf(channel);
        if (registry.findLocal(channel.workspaceId()).filter(mine::equals).isPresent()) {
            registry.unregister(channel.workspaceId(), channel.tokenId());
        }
    }

    /**
     * Repose la présence de ce canal si plus personne ne l'occupe. Symétrique de la garde du
     * nettoyage : la fermeture <b>tardive</b> d'un WebSocket portant le même jeton appelle
     * {@code unregister(workspaceId, tokenId)} et efface la présence du polling qui vient de prendre
     * le relais. Le poll suivant la remet, donc au pire le statut clignote le temps d'un cycle. On ne
     * touche à rien si une autre présence est enregistrée : elle est plus récente que nous.
     */
    private void refreshPresence(LongPollingRunnerOutbound channel) {
        if (registry.findLocal(channel.workspaceId()).isEmpty()) {
            registry.register(connectionOf(channel));
        }
    }

    /** Présence correspondant à ce canal — valeur <b>déterministe</b> (base de la garde anti-course). */
    private RunnerConnection connectionOf(LongPollingRunnerOutbound channel) {
        return new RunnerConnection(channel.workspaceId(), channel.userId(), channel.tokenId(), nodeId,
                OffsetDateTime.ofInstant(channel.connectedAt(), ZoneOffset.UTC));
    }
}
