package fr.claudegateway.runner.relay;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Résout les <b>pods pairs</b> du backend (F-38 / SF-38-13).
 *
 * <p><b>Pourquoi une diffusion et pas l'adresse du registre.</b> Le pod à atteindre pour une
 * confirmation ou une interruption n'est pas celui qui héberge la socket du runner : la porte
 * ({@code RunnerConfirmationGate.pending}) et la marque d'interruption
 * ({@code AtelierChatService.interruptedTurns}) vivent sur le pod qui exécute la boucle et tient le
 * {@code SseEmitter}. Le navigateur et le runner sont deux clients équilibrés séparément — aucun
 * annuaire ne dit où tourne la boucle. On diffuse donc à tous les pods, et celui qui est concerné
 * agit ; les autres répondent « rien à faire », ce qui n'est pas une erreur.</p>
 *
 * <p>La résolution passe par le Service headless {@code claude-gateway-backend-internal}
 * ({@code clusterIP: None}, {@code publishNotReadyAddresses: true}) : le DNS rend directement les IP
 * des pods. Sa propre {@code POD_IP} est retirée — un pod n'a rien à se dire à lui-même, il a déjà
 * agi localement avant de diffuser.</p>
 */
@Component
public class RelayPeerResolver {

    private static final Logger log = LoggerFactory.getLogger(RelayPeerResolver.class);
    private static final long DNS_WARN_INTERVAL_MS = 60_000L;

    private final RunnerRelayProperties properties;
    private final AtomicLong lastDnsWarnAt = new AtomicLong(0L);

    public RelayPeerResolver(RunnerRelayProperties properties) {
        this.properties = properties;
    }

    /**
     * Adresses de base ({@code http://{ip}:{port}}) des <b>autres</b> pods backend, ou liste vide :
     * relais éteint, hôte de pairs non renseigné, DNS en échec, ou pod seul. Une liste vide n'est
     * jamais une erreur — elle décrit exactement le cas mono-pod, qui est le cas courant.
     */
    public List<String> peerBaseUrls() {
        if (!properties.isEnabled() || properties.getPeersHost() == null
                || properties.getPeersHost().isBlank()) {
            return List.of();
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(properties.getPeersHost().trim());
        } catch (UnknownHostException ex) {
            warnDnsFailure();
            return List.of();
        }
        String self = properties.getSelfAddress().trim();
        List<String> peers = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            String host = address.getHostAddress();
            if (host == null || host.isBlank() || host.equals(self)) {
                continue;
            }
            peers.add("http://" + host + ":" + properties.getPort());
        }
        return List.copyOf(peers);
    }

    /**
     * Un Service headless sans endpoint prêt ne résout pas : c'est normal au démarrage du tout
     * premier pod. On trace au plus une fois par minute pour ne pas noyer le journal.
     */
    private void warnDnsFailure() {
        long now = System.currentTimeMillis();
        long previous = lastDnsWarnAt.get();
        if (now - previous >= DNS_WARN_INTERVAL_MS && lastDnsWarnAt.compareAndSet(previous, now)) {
            log.warn("Aucun pair backend résolu (hôte={}) : la diffusion interne reste locale",
                    properties.getPeersHost());
        }
    }
}
