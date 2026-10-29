package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.exec.RunnerTargets;

/** F-221 / SF-221-06 — le worktree d'un chantier parallèle : identifiant, chemins, commandes, cible. */
class LaneWorktreeTest {

    private static final UUID SUJET = UUID.fromString("d5f10db2-1111-2222-3333-444455556666");

    @Test
    @DisplayName("identifiant, chemin sous la racine du poste, branche, chemin vu du projet")
    void namesAndPaths() {
        String id = LaneWorktree.id(SUJET, 8);
        assertThat(id).isEqualTo("d5f10db2-ch8");
        assertThat(LaneWorktree.hostPath(id)).isEqualTo(".atelier-chantiers/d5f10db2-ch8");
        assertThat(LaneWorktree.branch(id)).isEqualTo("atelier/chantier-d5f10db2-ch8");
        assertThat(LaneWorktree.fromProject("", id)).isEqualTo(".atelier-chantiers/d5f10db2-ch8");
        assertThat(LaneWorktree.fromProject("agenor", id)).isEqualTo("../.atelier-chantiers/d5f10db2-ch8");
        assertThat(LaneWorktree.fromProject("clients/agenor/", id)).isEqualTo("../../.atelier-chantiers/d5f10db2-ch8");
        assertThat(LaneWorktree.addCommand("agenor", id))
                .isEqualTo("git worktree add -b atelier/chantier-d5f10db2-ch8 ../.atelier-chantiers/d5f10db2-ch8 HEAD");
        assertThat(LaneWorktree.addExistingBranchCommand("agenor", id))
                .isEqualTo("git worktree add ../.atelier-chantiers/d5f10db2-ch8 atelier/chantier-d5f10db2-ch8");
    }

    @Test
    @DisplayName("la cible runner du tour isolé est le worktree ; sans worktree, le projet")
    void runnerTargetFollowsTheTurnWorktree() {
        Workspace workspace = new Workspace();
        workspace.setId(SUJET);
        workspace.setHostId(UUID.randomUUID());
        workspace.setProjectPath("agenor");
        assertThat(RunnerTargets.of(workspace).projectPath()).isEqualTo("agenor");
        workspace.setTurnWorktreePath(".atelier-chantiers/d5f10db2-ch8");
        assertThat(RunnerTargets.of(workspace).projectPath()).isEqualTo(".atelier-chantiers/d5f10db2-ch8");
    }
}
