package fr.claudegateway.runner.channel;

import java.util.Optional;
import java.util.UUID;

/**
 * Registre des connexions runner (F-38 / SF-38-02, décision D8 / ADR-016). Le domaine ne dépend que
 * de cette interface, jamais d'un mécanisme concret (Provider Independence) — à la manière de
 * {@code WorkspaceStorage} : impl {@code in-memory} en dev/tests (un seul pod),
 * {@code pg-notify} en production (présence diffusée entre les 2 replicas par Postgres
 * {@code LISTEN}/{@code NOTIFY}).
 *
 * <p>Un workspace n'a qu'une connexion runner à la fois (un runner par workspace ; plusieurs runners
 * simultanés sont hors périmètre). {@link #register} remplace toute connexion précédente.</p>
 */
public interface RunnerRegistry {

    /** Enregistre (ou remplace) la connexion runner d'un workspace. */
    void register(RunnerConnection connection);

    /**
     * Retire la connexion d'un workspace <b>si</b> celle enregistrée a été ouverte par ce jeton
     * (garde anti-course : une reconnexion plus récente sous un autre jeton n'est pas effacée par la
     * fermeture tardive de l'ancienne).
     */
    void unregister(UUID workspaceId, UUID tokenId);

    /**
     * Connexion <b>locale</b> vivante d'un workspace (sur ce nœud), pour le routage des messages
     * d'outil (SF-38-05). Vide si aucune socket ne vit sur ce nœud, même si un runner est présent
     * sur un autre replica (voir {@link #isConnected}).
     */
    Optional<RunnerConnection> findLocal(UUID workspaceId);

    /**
     * Nœud <b>distant</b> hébergeant la socket de ce workspace, s'il est connu (F-38 / SF-38-12).
     * Alimente le relais inter-pods : sans adresse, aucun relais n'est possible et l'appel dégrade
     * vers le comportement d'origine.
     *
     * <p>Vide quand la socket est locale, quand aucun runner n'est connecté, ou quand la présence
     * distante n'a pas encore convergé. Vide <b>toujours</b> pour un registre mono-pod.</p>
     */
    Optional<RemoteRunnerNode> findRemote(UUID workspaceId);

    /**
     * Vrai si un runner est connecté pour ce workspace, <b>tous replicas confondus</b> (présence
     * locale ou distante). Alimente le statut exposé à l'utilisateur.
     */
    boolean isConnected(UUID workspaceId);
}
