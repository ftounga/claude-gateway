package fr.claudegateway.runner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.RunnerShell;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.channel.RunnerRegistry;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Calcule l'état « runner connecté » d'un <b>poste</b> (F-38 / SF-38-02, redéfini par
 * F-48 / SF-48-01), et le rend aussi pour un <b>projet</b> — qui hérite alors de l'état du poste
 * auquel il est rattaché. L'appartenance est toujours vérifiée en amont (isolation {@code user_id}),
 * jamais déduite d'un paramètre client.
 *
 * <p>{@code connected} combine deux signaux : la présence dans le {@link RunnerRegistry} (immédiate,
 * locale ou cross-replica via PgNotify) <b>et</b> la fraîcheur de {@code last_seen_at} (base
 * partagée). Ce second critère rend le statut correct même si la socket vit sur l'autre pod ou si un
 * pod vient de démarrer sans avoir encore reçu les événements de présence existants.</p>
 */
@Service
public class RunnerStatusService {

    private final RunnerTokenRepository tokenRepository;
    private final RunnerRegistry registry;
    private final WorkspaceService workspaceService;
    private final RunnerHostService hostService;
    private final Duration staleAfter;

    public RunnerStatusService(
            RunnerTokenRepository tokenRepository,
            RunnerRegistry registry,
            WorkspaceService workspaceService,
            RunnerHostService hostService,
            @Value("${app.runner.heartbeat.stale-after:PT90S}") Duration staleAfter) {
        this.tokenRepository = tokenRepository;
        this.registry = registry;
        this.workspaceService = workspaceService;
        this.hostService = hostService;
        this.staleAfter = staleAfter;
    }

    /**
     * État runner d'un <b>projet</b>, pour son propriétaire : celui du poste auquel il est rattaché.
     *
     * <p>Un projet sans poste n'est pas une erreur — c'est l'état d'un projet qu'on vient de créer,
     * et l'écran doit pouvoir le dire. Il rend donc « déconnecté », pas un 404.</p>
     */
    @Transactional(readOnly = true)
    public RunnerStatus status(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        if (workspace.getHostId() == null) {
            return new RunnerStatus(false, false, null, null, null, null, null, false);
        }
        return hostStatus(userId, workspace.getHostId());
    }

    /** État runner d'un <b>poste</b> possédé (404 si le poste n'est pas le sien). */
    @Transactional(readOnly = true)
    public RunnerStatus hostStatus(UUID userId, UUID hostId) {
        RunnerHost host = hostService.requireOwned(userId, hostId);
        return statusOf(userId, host);
    }

    /**
     * État d'un poste déjà chargé et déjà vérifié possédé — la forme employée par les listes, qui
     * n'ont aucune raison de relire chaque poste une seconde fois.
     */
    @Transactional(readOnly = true)
    public RunnerStatus statusOf(UUID userId, RunnerHost host) {
        OffsetDateTime now = OffsetDateTime.now();
        // UNE seule lecture des jetons du poste : elle sert au dernier signe de vie ET à
        // `paired`. Les deux questions portent sur les mêmes lignes ; les poser deux fois
        // ferait payer un booléen au prix d'une requête (F-82 / SF-82-04).
        List<RunnerToken> tokens =
                tokenRepository.findByUserIdAndHostIdOrderByCreatedAtDesc(userId, host.getId());
        Optional<OffsetDateTime> lastSeen = tokens.stream()
                .map(RunnerToken::getLastSeenAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder());
        boolean heartbeatFresh = lastSeen
                .map(seen -> seen.isAfter(now.minus(staleAfter)))
                .orElse(false);
        boolean connected = registry.isConnected(host.getId()) || heartbeatFresh;
        boolean paired = tokens.stream().anyMatch(token -> token.isValidAt(now));
        return new RunnerStatus(connected, paired, lastSeen.orElse(null), declaredShell(host),
                host.getId(), host.getName(), host.getRootName(),
                Boolean.TRUE.equals(host.getElevated()));
    }

    /**
     * Genre d'interpréteur élu par le runner (F-38 / SF-38-27), <b>normalisé</b>, ou {@code null}
     * (F-45 / SF-45-05, décision D7).
     *
     * <p>La colonne {@code runner_hosts.shell} est alimentée par une trame venue d'un client : elle
     * repasse par la liste blanche de {@link RunnerShell} avant de sortir de la gateway, si bien
     * qu'une valeur inconnue — base d'une version antérieure, écriture manuelle — devient
     * {@code null} plutôt que d'être relayée telle quelle à l'écran.</p>
     *
     * <p>{@code null} et non « inconnu » : un runner qui n'a rien déclaré ne doit pas produire un
     * défaut à l'écran, mais l'<b>omission de la ligne</b>.</p>
     */
    private static String declaredShell(RunnerHost host) {
        return RunnerShell.fromDeclared(host.getShell())
                .map(RunnerShell::declared)
                .orElse(null);
    }

    /**
     * État runner : connecté ou non, <b>appairé</b> ou non, dernière activité observée (peut être
     * {@code null}), genre d'interpréteur élu ({@code posix} / {@code powershell} / {@code cmd}, ou
     * {@code null}), et ce que l'écran doit savoir du <b>poste</b> — son identifiant, son nom, la
     * racine qu'il a déclarée, et s'il tourne en administrateur.
     *
     * <p>Ces trois dernières valeurs décrivaient le projet avant F-48 ; elles décrivent maintenant
     * une machine, et voyagent avec son état plutôt qu'avec le détail du projet. L'élévation est
     * lue là où l'on autorise une commande : c'est le seul endroit où elle change une décision
     * (SF-38-18).</p>
     *
     * <p><b>{@code paired}</b> (F-82 / SF-82-04) répond à la question que {@code connected} ne sait
     * pas poser : « cette machine porte-t-elle encore un jeton utilisable ? ». Un poste non connecté
     * mais appairé n'a besoin d'<b>aucun code</b> — seulement qu'on relance le runner, qui retrouve
     * passerelle et racine à côté de son jeton (F-46 / SF-46-01). Un poste dont tous les jetons ont
     * été révoqués par le coupe-circuit (SF-38-08), ou dont le jeton a expiré, vaut {@code false} :
     * il lui faut réellement un nouveau code, et proposer une reprise vouée à l'échec serait pire
     * que ne rien proposer.</p>
     *
     * <p>Tout est {@code null} / {@code false} pour un projet rattaché à aucun poste — l'état d'un
     * projet qu'on vient de créer.</p>
     */
    public record RunnerStatus(boolean connected, boolean paired, OffsetDateTime lastSeenAt,
            String shell, UUID hostId, String hostName, String rootName, boolean elevated) {
    }
}
