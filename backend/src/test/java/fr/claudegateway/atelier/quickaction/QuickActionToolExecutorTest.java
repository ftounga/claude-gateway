package fr.claudegateway.atelier.quickaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionRequest;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionView;

/** F-275 / SF-275-02 — l'outil action_rapide : ajouter, lister, modifier, supprimer, sous la garde du terminal. */
@ExtendWith(MockitoExtension.class)
class QuickActionToolExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private QuickActionService service;

    private final QuickActionToolCatalog catalog = new QuickActionToolCatalog();
    private QuickActionToolExecutor executor;
    private final UUID userId = UUID.randomUUID();
    private Workspace terminal;
    private Workspace reporting;

    @BeforeEach
    void setUp() {
        executor = new QuickActionToolExecutor(catalog, service);
        terminal = new Workspace();
        terminal.setId(UUID.randomUUID());
        terminal.setUserId(userId);
        terminal.setHostId(UUID.randomUUID());
        reporting = new Workspace();
        reporting.setId(UUID.randomUUID());
        reporting.setUserId(userId);
        reporting.setName("reporting-securite");
        lenient().when(service.resolveSubject(userId, terminal, "reporting-securite")).thenReturn(reporting);
    }

    private static JsonNode in(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static QuickActionView view(String label, String target, String click) {
        return new QuickActionView("c:x", "PERSO", UUID.randomUUID(), label, "point hebdo", null, "POSTE", null, target,
                false, click, List.of(1), "MATIN", null);
    }

    private QuickAction stored(String label) {
        return QuickAction.builder().id(UUID.randomUUID()).userId(userId).label(label)
                .labelKey(label.toLowerCase()).phrase("x").scope("POSTE").clickMode("DEPOSER").build();
    }

    @Test
    void addResolvesTheSubjectOnTheHostAndMapsEveryField() throws Exception {
        when(service.create(eq(userId), eq(terminal), any())).thenReturn(view("Point hebdo", "reporting-securite", "ENVOYER"));

        var outcome = executor.execute(userId, terminal, "action_rapide", in("""
                {"operation":"ajouter","libelle":"Point hebdo","phrase":"point hebdo","sujet":"reporting-securite",
                 "portee":"poste","clic":"envoyer","suggestion_jours":["lundi"],"suggestion_moment":"matin"}"""));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("dans reporting-securite", "envoi direct", "Actions rapides");
        ArgumentCaptor<QuickActionRequest> req = ArgumentCaptor.forClass(QuickActionRequest.class);
        verify(service).create(eq(userId), eq(terminal), req.capture());
        assertThat(req.getValue().targetWorkspaceId()).isEqualTo(reporting.getId());
        assertThat(req.getValue().scope()).isEqualTo("POSTE");
        assertThat(req.getValue().clickMode()).isEqualTo("ENVOYER");
        assertThat(req.getValue().suggestDays()).containsExactly(1);
        assertThat(req.getValue().suggestMoment()).isEqualTo("MATIN");
    }

    @Test
    void workingDaysExpandToMondayToFriday() throws Exception {
        when(service.create(eq(userId), eq(terminal), any())).thenReturn(view("Suivi", null, "DEPOSER"));
        executor.execute(userId, terminal, "action_rapide",
                in("{\"operation\":\"ajouter\",\"libelle\":\"Suivi\",\"phrase\":\"suivi du jour\",\"suggestion_jours\":[\"jours_ouvres\"]}"));
        ArgumentCaptor<QuickActionRequest> req = ArgumentCaptor.forClass(QuickActionRequest.class);
        verify(service).create(eq(userId), eq(terminal), req.capture());
        assertThat(req.getValue().suggestDays()).containsExactly(1, 2, 3, 4, 5);
        assertThat(req.getValue().targetWorkspaceId()).isNull();
    }

    @Test
    void listRendsCustomAndSkillActionsWithTheirLastRun() throws Exception {
        when(service.list(userId, terminal, true)).thenReturn(List.of(
                view("Point hebdo", "reporting-securite", "ENVOYER"),
                new QuickActionView("s:h:suivi-hebdo:suivi du jour", "SKILL", null, "Suivi du jour", "suivi du jour",
                        "suivi-hebdo", "POSTE", null, null, false, "DEPOSER", List.of(), null,
                        OffsetDateTime.parse("2026-11-19T08:00:00Z"))));

        var outcome = executor.execute(userId, terminal, "action_rapide", in("{\"operation\":\"lister\"}"));

        assertThat(outcome.content()).contains("2 action(s)", "« Point hebdo »", "jamais lancée", "[skill suivi-hebdo]",
                "lancée le 2026-11-19");
    }

    @Test
    void updateByLabelPassesOnlyTheGivenFieldsAndCanClearTheTargetAndSuggestion() throws Exception {
        QuickAction existing = stored("Point hebdo");
        when(service.visible(userId, terminal)).thenReturn(List.of(existing));
        when(service.update(eq(userId), eq(terminal), eq(existing.getId()), any())).thenReturn(view("Point hebdo", null, "DEPOSER"));

        var outcome = executor.execute(userId, terminal, "action_rapide",
                in("{\"operation\":\"modifier\",\"action\":\" point HEBDO \",\"sujet\":\"\",\"sans_suggestion\":true}"));

        assertThat(outcome.error()).isFalse();
        ArgumentCaptor<QuickActionRequest> req = ArgumentCaptor.forClass(QuickActionRequest.class);
        verify(service).update(eq(userId), eq(terminal), eq(existing.getId()), req.capture());
        assertThat(req.getValue().label()).isNull();
        assertThat(req.getValue().phrase()).isNull();
        assertThat(req.getValue().clearTarget()).isTrue();
        assertThat(req.getValue().clearSuggestion()).isTrue();
    }

    @Test
    void deleteByLabelDeletesAndAnUnknownLabelChangesNothing() throws Exception {
        QuickAction existing = stored("Point hebdo");
        when(service.visible(userId, terminal)).thenReturn(List.of(existing));

        assertThat(executor.execute(userId, terminal, "action_rapide",
                in("{\"operation\":\"supprimer\",\"action\":\"Point hebdo\"}")).error()).isFalse();
        verify(service).delete(userId, existing.getId());

        var unknown = executor.execute(userId, terminal, "action_rapide",
                in("{\"operation\":\"supprimer\",\"action\":\"Veille\"}"));
        assertThat(unknown.error()).isTrue();
        assertThat(unknown.content()).contains("Aucune action rapide « Veille »", "Point hebdo");
    }

    @Test
    void anActionOfAnotherUserIsNeverDesignable() throws Exception {
        // Seules les actions visibles dans CE terminal (sous le user_id du tour) sont cherchées : un id d'autrui échoue.
        when(service.visible(userId, terminal)).thenReturn(List.of());
        var outcome = executor.execute(userId, terminal, "action_rapide",
                in("{\"operation\":\"supprimer\",\"action\":\"" + UUID.randomUUID() + "\"}"));
        assertThat(outcome.error()).isTrue();
        verify(service, never()).delete(any(), any());
    }

    @Test
    void anUnknownOperationOrDayIsRefusedReadably() throws Exception {
        assertThat(executor.execute(userId, terminal, "action_rapide", in("{\"operation\":\"lancer\"}")).content())
                .contains("ajouter, lister, modifier ou supprimer");
        var day = executor.execute(userId, terminal, "action_rapide",
                in("{\"operation\":\"ajouter\",\"libelle\":\"a\",\"phrase\":\"b\",\"suggestion_jours\":[\"férié\"]}"));
        assertThat(day.error()).isTrue();
        assertThat(day.content()).contains("Jour inconnu");
    }

    @Test
    void theToolIsNeitherDeclaredNorExecutableInTeamsOrJarvisOrForAnotherUser() throws Exception {
        assertThat(catalog.toolsFor(userId, terminal)).extracting(t -> t.name()).containsExactly("action_rapide");
        assertThat(catalog.toolsFor(UUID.randomUUID(), terminal)).isEmpty();
        terminal.setTeamsTerminal(true);
        assertThat(catalog.toolsFor(userId, terminal)).isEmpty();
        var outcome = executor.execute(userId, terminal, "action_rapide", in("{\"operation\":\"lister\"}"));
        assertThat(outcome.error()).isTrue();
        verify(service, never()).list(any(), any(Workspace.class), eq(true));
        terminal.setTeamsTerminal(false);
        terminal.setJarvisThreadUserId(userId);
        assertThat(catalog.toolsFor(userId, terminal)).isEmpty();
    }
}
