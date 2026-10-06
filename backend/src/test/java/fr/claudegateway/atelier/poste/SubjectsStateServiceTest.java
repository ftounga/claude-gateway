package fr.claudegateway.atelier.poste;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.billing.AdministratorEntitlement;
import fr.claudegateway.bilan.SessionBilan;
import fr.claudegateway.bilan.SessionBilanRepository;
import fr.claudegateway.quota.ProjectCostAggregate;
import fr.claudegateway.quota.TurnCostView;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * <b>{@code sujets_etat}</b> (F-178 / SF-178-02) : un tableau par sujet du poste — dernière activité,
 * parcours, attentes ouvertes, dernier bilan, semaine — isolé utilisateur + poste, montants aux seuls
 * administrateurs.
 */
class SubjectsStateServiceTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AtelierMessageRepository messages = mock(AtelierMessageRepository.class);
    private final SubjectJourneyRepository journeys = mock(SubjectJourneyRepository.class);
    private final TerminalActionRepository actions = mock(TerminalActionRepository.class);
    private final SessionBilanRepository bilans = mock(SessionBilanRepository.class);
    private final UsageTurnRepository turns = mock(UsageTurnRepository.class);
    private final TurnCostView costView = mock(TurnCostView.class);
    private final AdministratorEntitlement admins = mock(AdministratorEntitlement.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID dataPlatform = UUID.randomUUID();
    private final UUID lzi = UUID.randomUUID();
    private SubjectsStateService service;

    @BeforeEach
    void setUp() {
        service = new SubjectsStateService(workspaceService, messages, journeys, actions, bilans, turns, costView,
                admins, clock);
        when(workspaceService.listByHost(userId, hostId)).thenReturn(List.of(
                ws(lzi, "lzi", userId, hostId),
                ws(dataPlatform, "data-platform", userId, hostId),
                ws(UUID.randomUUID(), "intrus", UUID.randomUUID(), hostId)));
        when(messages.lastActivityByWorkspace(any(), eq(userId))).thenReturn(List.of(
                new Object[] {dataPlatform, OffsetDateTime.parse("2026-10-06T09:00:00Z")},
                new Object[] {lzi, OffsetDateTime.parse("2026-09-28T09:00:00Z")}));
        SubjectJourney guided = new SubjectJourney();
        guided.setWorkspaceId(dataPlatform);
        guided.setUserId(userId);
        guided.setMode(JourneyMode.GUIDE);
        guided.setPhase(JourneyPhase.EXECUTION);
        guided.setChantierNumber(2);
        guided.setChantierTitle("Jeton Atlantis");
        when(journeys.findByUserIdAndWorkspaceIdIn(eq(userId), any())).thenReturn(List.of(guided));
        TerminalAction attente = new TerminalAction();
        attente.setWorkspaceId(dataPlatform);
        attente.setDescription("Obtenir le jeton Atlantis de l'équipe sécurité");
        attente.setStatus(TerminalActionStatus.DEMANDE);
        attente.setCreatedAt(OffsetDateTime.parse("2026-10-01T09:00:00Z"));
        TerminalAction autrePoste = new TerminalAction();
        autrePoste.setWorkspaceId(UUID.randomUUID());
        autrePoste.setDescription("hors sujet du poste");
        autrePoste.setStatus(TerminalActionStatus.A_FAIRE);
        when(actions.findByUserIdAndHostIdAndStatusIn(userId, hostId, TerminalActionStatus.OPEN_STATES))
                .thenReturn(List.of(attente, autrePoste));
        SessionBilan bilan = new SessionBilan();
        bilan.setCreatedAt(OffsetDateTime.parse("2026-10-03T18:00:00Z"));
        bilan.setTurns(12);
        bilan.setCostEur(new BigDecimal("4.2"));
        bilan.setSuggestionCount(3);
        when(bilans.findFirstByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, dataPlatform))
                .thenReturn(Optional.of(bilan));
        when(bilans.findFirstByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, lzi)).thenReturn(Optional.empty());
        ProjectCostAggregate week = mock(ProjectCostAggregate.class);
        when(week.getWorkspaceId()).thenReturn(dataPlatform);
        when(week.getCostUsd()).thenReturn(new BigDecimal("3.5"));
        when(week.getTurns()).thenReturn(14L);
        when(turns.aggregateCostByProject(eq(userId), any(), any())).thenReturn(List.of(week));
        when(costView.labelFor(any(BigDecimal.class), anyBoolean())).thenReturn("3,10 €");
    }

    private static Workspace ws(UUID id, String name, UUID owner, UUID host) {
        Workspace w = new Workspace();
        w.setId(id);
        w.setName(name);
        w.setUserId(owner);
        w.setHostId(host);
        return w;
    }

    @Test
    @DisplayName("un bloc par sujet du poste, le plus récemment actif d'abord, intrus écartés")
    void describesEachSubject() {
        when(admins.isAdministrator(userId)).thenReturn(true);

        String out = service.describe(userId, hostId);

        assertThat(out).contains("2 sujets");
        assertThat(out.indexOf("## data-platform")).isLessThan(out.indexOf("## lzi"));
        assertThat(out).doesNotContain("intrus").doesNotContain("hors sujet du poste");
        assertThat(out).contains("Dernière activité : 2026-10-06");
        assertThat(out).contains("Guidé · phase Exécution · chantier n°2 « Jeton Atlantis »");
        assertThat(out).contains("Attentes ouvertes : 1 — « Obtenir le jeton Atlantis de l'équipe sécurité » (demandé)");
        assertThat(out).contains("Dernier bilan : 2026-10-03 · 12 tours · 4,20 € · 3 suggestion(s)");
        assertThat(out).contains("Semaine : 3,10 € · 14 tours");
        // lzi : rien que des valeurs neutres.
        assertThat(out).contains("Parcours : Libre").contains("Dernier bilan : aucun").contains("Semaine : aucun tour");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Collection<UUID>> ids = (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
        verify(messages).lastActivityByWorkspace(ids.capture(), eq(userId));
        assertThat(ids.getValue()).containsExactlyInAnyOrder(dataPlatform, lzi);
    }

    @Test
    @DisplayName("non administrateur : aucun montant, seulement les tours")
    void noAmountsForNonAdmin() {
        when(admins.isAdministrator(userId)).thenReturn(false);

        String out = service.describe(userId, hostId);

        assertThat(out).doesNotContain("€");
        assertThat(out).contains("Dernier bilan : 2026-10-03 · 12 tours · 3 suggestion(s)");
        assertThat(out).contains("Semaine : 14 tours");
        verify(costView, never()).labelFor(any(BigDecimal.class), anyBoolean());
    }

    @Test
    @DisplayName("poste sans sujet : message neutre, aucune lecture")
    void emptyHost() {
        UUID empty = UUID.randomUUID();
        when(workspaceService.listByHost(userId, empty)).thenReturn(List.of());

        assertThat(service.describe(userId, empty)).contains("Aucun sujet");
        verify(messages, never()).lastActivityByWorkspace(any(), any());
    }
}
