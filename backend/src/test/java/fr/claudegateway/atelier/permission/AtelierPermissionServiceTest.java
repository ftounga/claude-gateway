package fr.claudegateway.atelier.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Politique de permission allow/ask/deny persistée (F-121 / SF-121-02) : précédence des règles,
 * « toujours autoriser cette commande », et isolation {@code (user_id, workspace_id)}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierPermissionServiceTest {

    @Mock private AtelierPermissionRuleRepository repository;
    private AtelierPermissionService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AtelierPermissionService(repository);
    }

    private static AtelierPermissionRule rule(String tool, String prefix, PermissionEffect effect) {
        return AtelierPermissionRule.builder().tool(tool).commandPrefix(prefix)
                .effect(effect.name()).build();
    }

    @Test
    void noRuleReturnsEmpty() {
        when(repository.findByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(List.of());
        assertThat(service.ruleFor(userId, workspaceId, "bash", "ls")).isEmpty();
    }

    @Test
    void aToolLevelDenyIsReturnedForThatTool() {
        when(repository.findByUserIdAndWorkspaceId(userId, workspaceId))
                .thenReturn(List.of(rule("edit_file", null, PermissionEffect.DENY)));
        assertThat(service.ruleFor(userId, workspaceId, "edit_file", null))
                .contains(PermissionEffect.DENY);
    }

    @Test
    void aCommandPrefixRuleBeatsTheToolLevelRuleForBash() {
        when(repository.findByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(List.of(
                rule("bash", null, PermissionEffect.ASK),
                rule("bash", "git", PermissionEffect.ALLOW)));

        // Le premier mot est « git » : la règle de commande l'emporte sur la règle d'outil.
        assertThat(service.ruleFor(userId, workspaceId, "bash", "git commit -m x"))
                .contains(PermissionEffect.ALLOW);
        // Une autre commande retombe sur la règle d'outil.
        assertThat(service.ruleFor(userId, workspaceId, "bash", "npm test"))
                .contains(PermissionEffect.ASK);
    }

    @Test
    void theLongestMatchingPrefixWins() {
        when(repository.findByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(List.of(
                rule("bash", "git", PermissionEffect.ALLOW),
                rule("bash", "git push", PermissionEffect.DENY)));

        assertThat(service.ruleFor(userId, workspaceId, "bash", "git push origin main"))
                .contains(PermissionEffect.DENY);
        assertThat(service.ruleFor(userId, workspaceId, "bash", "git status"))
                .contains(PermissionEffect.ALLOW);
    }

    @Test
    void alwaysAllowBashPersistsARuleOnTheFirstWord() {
        when(repository.findByUserIdAndWorkspaceIdAndToolAndCommandPrefix(userId, workspaceId, "bash", "git"))
                .thenReturn(Optional.empty());

        service.alwaysAllowCommand(userId, workspaceId, "bash", "git commit -m \"x\"");

        ArgumentCaptor<AtelierPermissionRule> saved = ArgumentCaptor.forClass(AtelierPermissionRule.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTool()).isEqualTo("bash");
        assertThat(saved.getValue().getCommandPrefix()).isEqualTo("git");
        assertThat(saved.getValue().effect()).isEqualTo(PermissionEffect.ALLOW);
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(workspaceId);
    }

    @Test
    void alwaysAllowNonBashPersistsAToolLevelRule() {
        when(repository.findByUserIdAndWorkspaceIdAndToolAndCommandPrefixIsNull(userId, workspaceId, "edit_file"))
                .thenReturn(Optional.empty());

        service.alwaysAllowCommand(userId, workspaceId, "edit_file", null);

        ArgumentCaptor<AtelierPermissionRule> saved = ArgumentCaptor.forClass(AtelierPermissionRule.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTool()).isEqualTo("edit_file");
        assertThat(saved.getValue().getCommandPrefix()).isNull();
        assertThat(saved.getValue().effect()).isEqualTo(PermissionEffect.ALLOW);
    }

    @Test
    void setRuleUpdatesAnExistingRuleRatherThanInserting() {
        AtelierPermissionRule existing = rule("bash", "rm", PermissionEffect.ASK);
        when(repository.findByUserIdAndWorkspaceIdAndToolAndCommandPrefix(userId, workspaceId, "bash", "rm"))
                .thenReturn(Optional.of(existing));

        service.setRule(userId, workspaceId, "bash", "rm", PermissionEffect.DENY);

        // La règle existante est mise à jour, pas dupliquée.
        assertThat(existing.effect()).isEqualTo(PermissionEffect.DENY);
        verify(repository).save(existing);
    }

    @Test
    void readingIsIsolatedToTheOwnerAndWorkspace() {
        when(repository.findByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(List.of());
        service.ruleFor(userId, workspaceId, "bash", "ls");
        // La lecture filtre TOUJOURS sur le couple (user_id, workspace_id) : jamais un identifiant seul.
        verify(repository).findByUserIdAndWorkspaceId(userId, workspaceId);
    }
}
