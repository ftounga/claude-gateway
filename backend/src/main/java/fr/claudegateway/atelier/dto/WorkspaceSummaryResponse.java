package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.runner.host.HostMissionStatus;

/**
 * Vue résumée d'un workspace (liste). N'expose aucune clé de stockage interne.
 *
 * <p>La {@code source} figure dès la liste (F-31 / SF-31-02) : un projet Git et un projet d'archive
 * n'offrent pas les mêmes gestes, et l'écran doit pouvoir le montrer sans charger le détail. Depuis
 * F-38 (SF-38-05), la {@code executionTarget} y figure pour la même raison : un projet qui s'exécute
 * sur la machine de l'utilisateur se signale dès la liste.</p>
 *
 * <p>Le {@code hostName} (F-49 / SF-49-03) est le <b>nom du poste</b> sur lequel le projet vit, ou
 * {@code null} quand il n'est rattaché à aucune machine. Il est là pour que la liste des projets et
 * l'en-tête du terminal puissent montrer <b>chez quel client on travaille</b> sans un appel de plus
 * par projet. La <b>couleur</b> qui va avec n'est pas transmise : elle se calcule à l'écran, à
 * partir de ce nom.</p>
 *
 * <p>Le {@code hostMissionStatus} (F-60 / SF-60-02) est l'état de <b>mission</b> de ce poste —
 * {@code ACTIVE}, {@code PENDING}, {@code CLOSED} —, ou {@code null} pour un projet non rattaché.
 * Il voyage avec le nom, par la même lecture et sous la même isolation : la liste des projets et
 * l'en-tête du terminal peuvent dire <b>où en est la mission</b> sans un appel de plus.</p>
 */
public record WorkspaceSummaryResponse(
        UUID id, String name, OffsetDateTime createdAt, WorkspaceSource source, String gitRepo,
        WorkspaceExecutionTarget executionTarget, String hostName,
        HostMissionStatus hostMissionStatus) {

    /** Résumé d'un projet dont on ne cherche pas à nommer le poste. */
    public static WorkspaceSummaryResponse from(Workspace workspace) {
        return from(workspace, null, null);
    }

    /**
     * Résumé d'un projet, avec le nom de son poste.
     *
     * @param hostName          nom du poste, ou {@code null} si le projet n'est rattaché à aucune
     *                          machine. L'appelant ne doit y passer que le nom d'un poste
     *                          <b>possédé par le même utilisateur</b> : c'est là que se joue
     *                          l'isolation.
     * @param hostMissionStatus état de mission de ce poste, ou {@code null} — même provenance, donc
     *                          même isolation que {@code hostName}
     */
    public static WorkspaceSummaryResponse from(Workspace workspace, String hostName,
            HostMissionStatus hostMissionStatus) {
        String fullName = workspace.getGitOwner() == null || workspace.getGitRepo() == null
                ? null
                : workspace.getGitOwner() + "/" + workspace.getGitRepo();
        return new WorkspaceSummaryResponse(workspace.getId(), workspace.getName(),
                workspace.getCreatedAt(), workspace.sourceOrDefault(), fullName,
                workspace.executionTargetOrDefault(), hostName, hostMissionStatus);
    }
}
