package fr.claudegateway.atelier.quickaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionRequest;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionView;
import fr.claudegateway.atelier.skills.SkillCatalogService;
import fr.claudegateway.atelier.skills.SkillEntry;
import fr.claudegateway.atelier.skills.SkillLaunch;

/** F-275 / SF-275-01 — la liste fusionnée, les règles d'écriture, sous isolation. */
@ExtendWith(MockitoExtension.class)
class QuickActionServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private SkillCatalogService skillCatalog;
    @Mock private QuickActionRepository actions;
    @Mock private QuickActionLaunchRepository launches;

    private QuickActionService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private Workspace terminal;
    private Workspace reporting;
    private final List<QuickAction> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new QuickActionService(workspaceService, skillCatalog, actions, launches);
        terminal = workspace("data-platform", hostId);
        reporting = workspace("reporting-securite", hostId);
        lenient().when(workspaceService.requireOwned(userId, terminal.getId())).thenReturn(terminal);
        lenient().when(workspaceService.requireOwned(userId, reporting.getId())).thenReturn(reporting);
        lenient().when(workspaceService.listByHost(userId, hostId)).thenReturn(List.of(terminal, reporting));
        lenient().when(actions.findVisible(eq(userId), any(), any())).thenAnswer(inv -> List.copyOf(stored));
        lenient().when(actions.save(any())).thenAnswer(inv -> {
            QuickAction a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
                stored.add(a);
            }
            return a;
        });
        lenient().when(launches.findByUserIdAndActionKeyIn(eq(userId), anyCollection())).thenReturn(List.of());
        lenient().when(launches.findByUserIdAndActionKey(eq(userId), any())).thenReturn(Optional.empty());
    }

    private Workspace workspace(String name, UUID host) {
        Workspace w = new Workspace();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setName(name);
        w.setProjectPath(name);
        w.setHostId(host);
        return w;
    }

    private static SkillEntry skill(String name, String origin, SkillLaunch launch) {
        return new SkillEntry(name, ".claude/skills/" + name + ".md", "", origin, launch);
    }

    @Test
    void customActionsComeFirstThenOneActionPerSkillTrigger() {
        service.create(userId, terminal.getId(), new QuickActionRequest("Veille du comité", "prépare la veille du comité",
                null, reporting.getId(), null, "ENVOYER", List.of(1), "MATIN", null));
        when(skillCatalog.catalog(userId, terminal)).thenReturn(List.of(
                skill("suivi-hebdo", "POSTE", new SkillLaunch(List.of("point hebdo", "suivi du jour"), null, false)),
                skill("deploy", "SUJET", SkillLaunch.NONE)));

        List<QuickActionView> list = service.list(userId, terminal.getId(), true);

        assertThat(list).extracting(QuickActionView::label).containsExactly("Veille du comité", "Point hebdo",
                "Suivi du jour");
        QuickActionView custom = list.get(0);
        assertThat(custom.source()).isEqualTo("PERSO");
        assertThat(custom.scope()).isEqualTo("POSTE");
        assertThat(custom.targetName()).isEqualTo("reporting-securite");
        assertThat(custom.clickMode()).isEqualTo("ENVOYER");
        assertThat(custom.suggestDays()).containsExactly(1);
        assertThat(custom.suggestMoment()).isEqualTo("MATIN");
        QuickActionView hebdo = list.get(1);
        assertThat(hebdo.source()).isEqualTo("SKILL");
        assertThat(hebdo.skill()).isEqualTo("suivi-hebdo");
        assertThat(hebdo.phrase()).isEqualTo("point hebdo");
        assertThat(hebdo.key()).isEqualTo("s:" + hostId + ":suivi-hebdo:point hebdo");
        assertThat(hebdo.targetWorkspaceId()).isNull();
        assertThat(hebdo.clickMode()).isEqualTo("DEPOSER");
    }

    @Test
    void aCustomActionWithTheSamePhraseMasksTheSkillTrigger() {
        service.create(userId, terminal.getId(), new QuickActionRequest("Point hebdo sécurité", "Point  HEBDO", null,
                reporting.getId(), null, null, null, null, null));
        when(skillCatalog.catalog(userId, terminal)).thenReturn(List.of(
                skill("suivi-hebdo", "POSTE", new SkillLaunch(List.of("point hebdo", "suivi du jour"), null, false))));

        assertThat(service.list(userId, terminal.getId(), true)).extracting(QuickActionView::phrase)
                .containsExactly("Point  HEBDO", "suivi du jour");
    }

    @Test
    void theSkillSubjectIsResolvedOnTheHostAndAnUnknownOneIsSaidMissing() {
        when(skillCatalog.catalog(userId, terminal)).thenReturn(List.of(
                skill("suivi-hebdo", "POSTE", new SkillLaunch(List.of("point hebdo"), "Reporting-Securite", true)),
                skill("autre", "POSTE", new SkillLaunch(List.of("autre chose"), "inconnu", false))));

        List<QuickActionView> list = service.list(userId, terminal.getId(), true);

        assertThat(list.get(0).targetWorkspaceId()).isEqualTo(reporting.getId());
        assertThat(list.get(0).clickMode()).isEqualTo("ENVOYER");
        assertThat(list.get(1).targetWorkspaceId()).isNull();
        assertThat(list.get(1).targetMissing()).isTrue();
    }

    @Test
    void withoutSkillsTheCatalogIsNeverRead() {
        service.list(userId, terminal.getId(), false);

        verify(skillCatalog, never()).catalog(any(), any());
    }

    @Test
    void theLastRunIsCarriedByItsKey() {
        when(skillCatalog.catalog(userId, terminal)).thenReturn(List.of(
                skill("suivi-hebdo", "POSTE", new SkillLaunch(List.of("point hebdo"), null, false))));
        OffsetDateTime at = OffsetDateTime.now().minusDays(2);
        when(launches.findByUserIdAndActionKeyIn(eq(userId), anyCollection())).thenReturn(List.of(
                QuickActionLaunch.builder().userId(userId).actionKey("s:" + hostId + ":suivi-hebdo:point hebdo")
                        .lastLaunchedAt(at).launchCount(3).build()));

        assertThat(service.list(userId, terminal.getId(), true).get(0).lastRunAt()).isEqualTo(at);
    }

    @Test
    void teamsAndJarvisTerminalsHaveNoQuickActions() {
        terminal.setTeamsTerminal(true);
        assertThat(service.list(userId, terminal.getId(), true)).isEmpty();
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "b", null, null, null, null, null, null, null)))
                .isInstanceOf(QuickActionException.class);
    }

    @Test
    void theDefaultScopeIsTheHostOrTheSubjectWithoutHost() {
        assertThat(QuickActionService.defaultScope(terminal)).isEqualTo("POSTE");
        Workspace hosted = workspace("hébergé", null);
        assertThat(QuickActionService.defaultScope(hosted)).isEqualTo("SUJET");
        when(workspaceService.requireOwned(userId, hosted.getId())).thenReturn(hosted);
        assertThatThrownBy(() -> service.create(userId, hosted.getId(),
                new QuickActionRequest("a", "b", "POSTE", null, null, null, null, null, null)))
                .hasMessageContaining("pas de poste");
    }

    @Test
    void invalidFieldsAreRefusedWithAReadableMessage() {
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest(" ", "b", null, null, null, null, null, null, null)))
                .hasMessageContaining("libellé");
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "", null, null, null, null, null, null, null)))
                .hasMessageContaining("phrase");
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "b", "MONDE", null, null, null, null, null, null)))
                .hasMessageContaining("Portée");
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "b", null, null, null, "LANCER", null, null, null)))
                .hasMessageContaining("Clic");
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "b", null, null, null, null, List.of(8), null, null)))
                .hasMessageContaining("Jour");
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "b", null, null, null, null, List.of(1), "SOIR", null)))
                .hasMessageContaining("Moment");
    }

    @Test
    void aTargetOfAnotherUserOrAnotherHostIsRefused() {
        UUID foreign = UUID.randomUUID();
        when(workspaceService.requireOwned(userId, foreign)).thenThrow(new WorkspaceNotFoundException("x"));
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "b", null, foreign, null, null, null, null, null)))
                .isInstanceOf(QuickActionException.class).hasMessageContaining("Aucun sujet");
        Workspace elsewhere = workspace("ailleurs", UUID.randomUUID());
        when(workspaceService.requireOwned(userId, elsewhere.getId())).thenReturn(elsewhere);
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "b", null, elsewhere.getId(), null, null, null, null, null)))
                .hasMessageContaining("pas sur le poste");
    }

    @Test
    void aLabelAlreadyVisibleHereIsAConflict() {
        service.create(userId, terminal.getId(), new QuickActionRequest("Point hebdo", "a", null, null, null, null, null,
                null, null));
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest(" point  HEBDO ", "b", null, null, null, null, null, null, null)))
                .isInstanceOf(QuickActionException.class)
                .extracting(e -> ((QuickActionException) e).kind()).isEqualTo(QuickActionException.Kind.CONFLICT);
    }

    @Test
    void theCapPerAccountIsEnforced() {
        when(actions.countByUserId(userId)).thenReturn((long) QuickActionService.MAX_ACTIONS_PER_USER);
        assertThatThrownBy(() -> service.create(userId, terminal.getId(),
                new QuickActionRequest("a", "b", null, null, null, null, null, null, null)))
                .hasMessageContaining("Plafond");
    }

    @Test
    void anUpdateChangesOnlyTheGivenFieldsAndCanClearTheTarget() {
        QuickActionView created = service.create(userId, terminal.getId(), new QuickActionRequest("Suivi", "suivi du jour",
                "COMPTE", reporting.getId(), null, null, List.of(1, 2), "MATIN", null));
        when(actions.findByIdAndUserId(created.id(), userId)).thenReturn(Optional.of(stored.get(0)));

        QuickActionView updated = service.update(userId, terminal.getId(), created.id(),
                new QuickActionRequest(null, null, null, null, true, "ENVOYER", null, null, null));

        assertThat(updated.label()).isEqualTo("Suivi");
        assertThat(updated.scope()).isEqualTo("COMPTE");
        assertThat(updated.targetWorkspaceId()).isNull();
        assertThat(updated.clickMode()).isEqualTo("ENVOYER");
        assertThat(updated.suggestDays()).containsExactly(1, 2);
    }

    @Test
    void anActionOfAnotherUserIsNotFound() {
        UUID other = UUID.randomUUID();
        when(actions.findByIdAndUserId(other, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(userId, terminal.getId(), other))
                .isInstanceOf(QuickActionException.class)
                .extracting(e -> ((QuickActionException) e).kind()).isEqualTo(QuickActionException.Kind.NOT_FOUND);
    }

    @Test
    void aLaunchIsRecordedAndCounted() {
        var view = service.recordLaunch(userId, terminal.getId(), " c:" + UUID.randomUUID() + " ");
        assertThat(view.lastRunAt()).isNotNull();
        verify(launches).save(any());
        assertThatThrownBy(() -> service.recordLaunch(userId, terminal.getId(), "x"))
                .isInstanceOf(QuickActionException.class);
    }
}
