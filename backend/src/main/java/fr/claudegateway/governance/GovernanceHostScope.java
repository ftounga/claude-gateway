package fr.claudegateway.governance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Ce qu'un poste <b>contient</b>, et à qui il appartient (F-75 / SF-75-01).
 *
 * <p>Un seul endroit répond à la question « ce poste est-il le mien, et quels dossiers porte-t-il ? ».
 * C'est là que l'isolation se joue : un poste réel est vérifié <b>possédé</b>, et ses projets sont
 * lus par {@code user_id} + {@code host_id}. Le poste « Hébergé » n'a rien à vérifier — c'est, par
 * construction, l'ensemble des projets sans machine <b>de cet utilisateur</b>.</p>
 *
 * <p><b>Un poste introuvable rend « introuvable », jamais « interdit »</b> : un 403 apprendrait à
 * l'appelant qu'un poste existe sous cet identifiant.</p>
 */
@Service
public class GovernanceHostScope {

    private final RunnerHostService hostService;
    private final WorkspaceService workspaceService;

    public GovernanceHostScope(RunnerHostService hostService, WorkspaceService workspaceService) {
        this.hostService = hostService;
        this.workspaceService = workspaceService;
    }

    /**
     * Résout une référence reçue d'une URL en poste <b>possédé</b>.
     *
     * @throws fr.claudegateway.runner.host.RunnerHostNotFoundException si la référence est illisible,
     *                                                                 le poste inconnu, ou à autrui
     */
    @Transactional(readOnly = true)
    public GovernanceHostRef require(UUID userId, String ref) {
        GovernanceHostRef host = GovernanceHostRef.parse(ref);
        if (!host.hosted()) {
            hostService.requireOwned(userId, host.hostId());
        }
        return host;
    }

    /** Le poste d'un projet possédé : son poste réel, ou « Hébergé » s'il n'a pas de machine. */
    @Transactional(readOnly = true)
    public GovernanceHostRef hostOf(UUID userId, UUID workspaceId) {
        return hostOf(workspaceService.requireOwned(userId, workspaceId));
    }

    /** Un projet <b>possédé</b>, chargé une fois pour toutes. */
    @Transactional(readOnly = true)
    public Workspace projectOf(UUID userId, UUID workspaceId) {
        return workspaceService.requireOwned(userId, workspaceId);
    }

    /** Le poste d'un projet déjà chargé — aucune relecture, aucun contrôle à rejouer. */
    public GovernanceHostRef hostOf(Workspace workspace) {
        UUID hostId = workspace.getHostId();
        return hostId == null ? GovernanceHostRef.HOSTED : GovernanceHostRef.of(hostId);
    }

    /**
     * Les projets rangés sous ce poste — ceux qui recevront les fichiers.
     *
     * <p>C'est le déplacement de F-75 : l'<b>activation</b> vit sur le poste, les <b>artefacts</b>
     * restent par projet. Un poste sans projet est un état normal : la gouvernance s'y applique déjà,
     * et le premier dossier ajouté demain en héritera.</p>
     */
    @Transactional(readOnly = true)
    public List<Workspace> projectsOf(UUID userId, GovernanceHostRef host) {
        return host.hosted()
                ? workspaceService.listWithoutHost(userId)
                : workspaceService.listByHost(userId, host.hostId());
    }

    /** Nom lisible du poste. Un identifiant ne dit rien à personne, et l'écran annonce un nom. */
    @Transactional(readOnly = true)
    public String nameOf(UUID userId, GovernanceHostRef host) {
        if (host.hosted()) {
            return RunnerHostOverviewResponse.HOSTED_NAME;
        }
        return hostService.requireOwned(userId, host.hostId()).getName();
    }

    /**
     * Les postes <b>gouvernables</b> d'un utilisateur : ses machines, puis « Hébergé » s'il porte au
     * moins un projet.
     *
     * <p>« Hébergé » n'apparaît que s'il contient quelque chose — même règle que l'accueil de la
     * Forge (F-71) : une carte vide ne poserait qu'une question dont personne n'a besoin.</p>
     */
    @Transactional(readOnly = true)
    public List<GovernanceHostRef> governable(UUID userId) {
        List<GovernanceHostRef> refs = new java.util.ArrayList<>(
                hostService.list(userId).stream().map(RunnerHost::getId)
                        .map(GovernanceHostRef::of).toList());
        if (!workspaceService.listWithoutHost(userId).isEmpty()) {
            refs.add(GovernanceHostRef.HOSTED);
        }
        return List.copyOf(refs);
    }

    /** Poste réel possédé, s'il existe encore — utilisé pour nommer sans faire échouer une lecture. */
    @Transactional(readOnly = true)
    public Optional<RunnerHost> find(UUID userId, GovernanceHostRef host) {
        if (host.hosted()) {
            return Optional.empty();
        }
        try {
            return Optional.of(hostService.requireOwned(userId, host.hostId()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
