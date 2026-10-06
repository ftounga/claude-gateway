package fr.claudegateway.atelier.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.promptsource.PromptSourceStore;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** F-177 / SF-177-02 — proposer n'écrit rien ; appliquer écrit, au bon endroit, si rien n'a bougé. */
@ExtendWith(MockitoExtension.class)
class GovernanceProposalServiceTest {

    @Mock private GovernanceProposalRepository repository;
    @Mock private WorkspaceService workspaceService;
    @Mock private RunnerToolGateway gateway;
    @Mock private RunnerAuditService auditService;
    @Mock private PromptSourceStore promptSourceStore;

    private GovernanceProposalService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final Map<UUID, GovernanceProposal> rows = new HashMap<>();
    /** Fichiers : chemin de projet + "|" + chemin → contenu. */
    private final Map<String, String> files = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new GovernanceProposalService(repository, workspaceService, gateway, auditService,
                new ObjectMapper());
        service.setPromptSourceStore(promptSourceStore);
        lenient().when(repository.save(any())).thenAnswer(inv -> {
            GovernanceProposal p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            rows.put(p.getId(), p);
            return p;
        });
        lenient().when(repository.findByIdAndUserIdAndWorkspaceId(any(), any(), any())).thenAnswer(inv -> {
            GovernanceProposal p = rows.get((UUID) inv.getArgument(0));
            return p != null && p.getUserId().equals(inv.getArgument(1))
                    && p.getWorkspaceId().equals(inv.getArgument(2)) ? Optional.of(p) : Optional.empty();
        });
        lenient().when(gateway.readFile(any(), anyString(), anyString())).thenAnswer(inv -> {
            RunnerTarget t = inv.getArgument(0);
            String content = files.get(t.safeProjectPath() + "|" + inv.getArgument(2));
            return content == null ? RunnerCallResult.backendError("not_found", "absent") : ok(content);
        });
        lenient().when(gateway.writeFile(any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            RunnerTarget t = inv.getArgument(0);
            files.put(t.safeProjectPath() + "|" + inv.getArgument(2), inv.getArgument(3));
            return ok("");
        });
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private Workspace subject() {
        Workspace w = new Workspace();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        w.setHostId(hostId);
        w.setProjectPath("data-platform");
        lenient().when(workspaceService.requireOwned(userId, w.getId())).thenReturn(w);
        return w;
    }

    @Test
    void proposingARuleWritesNothingAndShowsTheDiff() {
        Workspace ws = subject();
        files.put("|GOUVERNANCE.md", "# Gouvernance\n\n## Langue\n\nFrançais.\n");

        GovernanceProposalBlock block = service.propose(userId, ws, "regle", "poste", "Jira",
                "Commentaires Jira courts.", "Demandé par le client");

        assertThat(block.path()).isEqualTo("GOUVERNANCE.md");
        assertThat(block.creates()).isFalse();
        assertThat(block.diff()).extracting(GovernanceProposalBlock.DiffLine::text)
                .contains("## Jira", "Commentaires Jira courts.");
        verify(gateway, never()).writeFile(any(), anyString(), anyString(), anyString());
        assertThat(rows.get(block.proposalId()).getContent())
                .contains("## Langue").endsWith("## Jira\n\nCommentaires Jira courts.\n");
    }

    @Test
    void applyingAHostRuleWritesAtTheRootAndRefreshesEverySubjectOfTheHost() {
        Workspace ws = subject();
        Workspace other = new Workspace();
        other.setId(UUID.randomUUID());
        when(workspaceService.listByHost(userId, hostId)).thenReturn(List.of(ws, other));
        when(workspaceService.findHostTerminal(userId, hostId)).thenReturn(Optional.empty());
        UUID id = service.propose(userId, ws, "REGLE", "POSTE", "Jira", "Commentaires Jira courts.", null)
                .proposalId();

        GovernanceProposalView view = service.apply(userId, ws.getId(), id);

        assertThat(view.status()).isEqualTo("APPLIED");
        assertThat(files.get("|GOUVERNANCE.md")).contains("Commentaires Jira courts.");
        ArgumentCaptor<RunnerTarget> target = ArgumentCaptor.forClass(RunnerTarget.class);
        verify(gateway).writeFile(target.capture(), anyString(), eq("GOUVERNANCE.md"), anyString());
        assertThat(target.getValue().hostId()).isEqualTo(hostId);
        assertThat(target.getValue().safeProjectPath()).isEmpty();
        verify(auditService).recordCall(eq(userId), any(), anyString(), eq("gouvernance_appliquer"),
                eq("GOUVERNANCE.md"), any());
        verify(promptSourceStore).putHostGovernance(eq(userId), eq(new ArrayList<>(List.of(ws, other))),
                anyString());
        assertThat(rows.get(id).getAppliedDigest()).isNotBlank();
    }

    @Test
    void applyRefusesWhenTheFileChangedSinceTheProposal() {
        Workspace ws = subject();
        UUID id = service.propose(userId, ws, "REGLE", "SUJET", "Jira", "Courts.", null).proposalId();
        files.put("data-platform|GOUVERNANCE.md", "modifié à la main");

        assertThatThrownBy(() -> service.apply(userId, ws.getId(), id))
                .isInstanceOf(ProposalConflictException.class);
        verify(gateway, never()).writeFile(any(), anyString(), anyString(), anyString());
    }

    @Test
    void aSkillGetsItsFileAndFrontMatter() {
        Workspace ws = subject();

        GovernanceProposalBlock block = service.propose(userId, ws, "SKILL", "SUJET", "Ticket Jira !",
                "# Ticket\n\nÉtapes…", "Rédiger un ticket Jira au format du client");

        assertThat(block.path()).isEqualTo(".claude/skills/ticket-jira.md");
        assertThat(block.creates()).isTrue();
        assertThat(rows.get(block.proposalId()).getContent())
                .startsWith("---\nname: ticket-jira\ndescription: Rédiger un ticket Jira au format du client\n---");
    }

    @Test
    void scopeMustMakeSense() {
        Workspace terminal = subject();
        terminal.setHostTerminal(true);
        terminal.setProjectPath("");
        assertThatThrownBy(() -> service.propose(userId, terminal, "REGLE", "SUJET", "x", "y", null))
                .isInstanceOf(InvalidProposalException.class);

        Workspace hosted = new Workspace();
        hosted.setId(UUID.randomUUID());
        hosted.setUserId(userId);
        assertThatThrownBy(() -> service.propose(userId, hosted, "REGLE", "POSTE", "x", "y", null))
                .isInstanceOf(InvalidProposalException.class);
        assertThatThrownBy(() -> service.propose(userId, hosted, "AGENT", "SUJET", "x", "y", null))
                .isInstanceOf(InvalidProposalException.class);
    }

    @Test
    void anotherUsersProposalIsNotFound() {
        Workspace ws = subject();
        UUID id = service.propose(userId, ws, "REGLE", "SUJET", "Jira", "Courts.", null).proposalId();
        UUID intruder = UUID.randomUUID();
        when(workspaceService.requireOwned(intruder, ws.getId()))
                .thenThrow(new WorkspaceNotFoundException("absent"));

        assertThatThrownBy(() -> service.apply(intruder, ws.getId(), id))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(gateway, never()).writeFile(any(), anyString(), anyString(), anyString());
    }

    @Test
    void replacingASectionKeepsTheRest() {
        List<GovernanceProposalBlock.DiffLine> diff = new ArrayList<>();
        String out = GovernanceProposalService.upsertRule(
                "# G\n\n## Jira\n\nLongs.\n\n## Langue\n\nFrançais.\n", "Jira", "Courts.", diff);

        assertThat(out).isEqualTo("# G\n\n## Jira\n\nCourts.\n\n## Langue\n\nFrançais.\n");
        assertThat(diff).extracting(GovernanceProposalBlock.DiffLine::kind).contains("DEL", "ADD");
    }
}
