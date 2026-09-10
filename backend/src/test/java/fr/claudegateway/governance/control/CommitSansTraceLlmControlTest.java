package fr.claudegateway.governance.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * Le premier verrou déterministe du produit (F-52 / SF-52-01).
 *
 * <p>Ce que ces tests protègent : qu'un {@code git commit} portant une trace d'assistant soit
 * <b>refusé</b>, que le refus dise quoi faire, et — tout aussi important — que rien d'autre ne le
 * soit. Un verrou qui hurle à tort est un verrou qu'on décroche.</p>
 */
class CommitSansTraceLlmControlTest {

    private final CommitSansTraceLlmControl control = new CommitSansTraceLlmControl();
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private AtelierCheckpointVerdict judge(String command) {
        return control.evaluate(
                AtelierCheckpointContext.beforeCommand(userId, workspaceId, command, null));
    }

    // ------------------------------------------------------------------ identité

    @Test
    void itAnnouncesItselfOnTheCommandCheckpoint() {
        assertThat(control.id()).isEqualTo("commit-sans-trace-llm");
        assertThat(control.kind()).isEqualTo(AtelierCheckpointKind.BEFORE_COMMAND);
        assertThat(control.description()).isNotBlank();
    }

    // ------------------------------------------------------------------ ce qui bloque

    @Test
    void aCoSignatureIsRefusedAndTheRefusalSaysWhatToDo() {
        AtelierCheckpointVerdict verdict = judge(
                "git commit -m \"feat: ajoute X\n\nCo-Authored-By: Claude <noreply@exemple>\"");

        assertThat(verdict.blocked()).isTrue();
        // Le message porte le GESTE, pas le constat : il est lu par un modèle qui doit corriger.
        assertThat(verdict.correction()).contains("Réécris-le").contains("relance la commande");
    }

    @Test
    void theRobotEmojiIsRefusedOnAnAmend() {
        assertThat(judge("git commit --amend -m \"fix: chemin\n\n🤖 Generated\"").blocked())
                .isTrue();
    }

    @Test
    void aGeneratedWithMentionIsRefused() {
        assertThat(judge("git commit -m \"docs\n\nGenerated with [Claude Code](https://x)\"").blocked())
                .isTrue();
    }

    @Test
    void aSessionLinkIsRefused() {
        assertThat(judge("git commit -m \"chore\n\nClaude-Session: https://exemple/s/1\"").blocked())
                .isTrue();
    }

    @Test
    void aProviderCoAuthorAddressIsRefused() {
        assertThat(judge("git commit -m \"x\n\nCo-Authored-By: Bot <noreply@anthropic.com>\"").blocked())
                .isTrue();
    }

    @Test
    void theCaseOfTheMarkerDoesNotMatter() {
        assertThat(judge("git commit -m \"x\n\nco-authored-by:   CLAUDE\"").blocked()).isTrue();
    }

    @Test
    void gitInvokedWithItsOwnOptionsIsStillAGitCommit() {
        assertThat(judge("git -C /home/moi/projet commit -m \"x Claude-Session: y\"").blocked()).isTrue();
        assertThat(judge("git --no-pager commit -m \"x Claude-Session: y\"").blocked()).isTrue();
        assertThat(judge("/usr/bin/git commit -m \"x Claude-Session: y\"").blocked()).isTrue();
    }

    @Test
    void aCommitBuriedInAChainOfCommandsIsStillFound() {
        assertThat(judge("cd frontend && git add -A && git commit -m \"x\n\n🤖\"").blocked())
                .isTrue();
    }

    // ------------------------------------------------------------------ ce qui passe

    @Test
    void aCleanCommitPasses() {
        assertThat(judge("git commit -m \"feat(F-52): le premier paquet de gouvernance\"").blocked())
                .isFalse();
    }

    @Test
    void aCommandThatIsNotACommitPassesEvenCarryingAMarker() {
        // La liste de marqueurs ne s'applique qu'à ce qui FABRIQUE un commit : le reste est du
        // travail légitime, à commencer par chercher ces marqueurs dans un dépôt.
        assertThat(judge("git log --grep=\"Co-Authored-By: Claude\"").blocked()).isFalse();
        assertThat(judge("grep -rn \"Co-Authored-By: Claude\" .").blocked()).isFalse();
        assertThat(judge("echo \"Co-Authored-By: Claude\" > /tmp/note").blocked()).isFalse();
        assertThat(judge("git status").blocked()).isFalse();
    }

    @Test
    void wordsThatMerelyMentionTheProviderPass() {
        // Le produit s'appelle claude-gateway : refuser le mot « Claude » rendrait tout commit
        // impossible dans son propre dépôt.
        assertThat(judge("git commit -m \"feat: relaie l'API Claude via AIProvider\"").blocked())
                .isFalse();
        assertThat(judge("git commit -m \"docs: Anthropic est le premier fournisseur\"").blocked())
                .isFalse();
    }

    @Test
    void anEmptyOrAbsentCommandIsNeverJudged() {
        assertThat(judge(null).blocked()).isFalse();
        assertThat(judge("   ").blocked()).isFalse();
        assertThat(control.evaluate(null).blocked()).isFalse();
    }

    @Test
    void anEnvironmentPrefixDoesNotHideTheCommit() {
        assertThat(judge("GIT_EDITOR=true git commit -m \"x 🤖\"").blocked()).isTrue();
    }
}
