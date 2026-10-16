package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.radar.RadarSubjectProject;
import fr.claudegateway.radar.RadarSubjectProjectRepository;
import fr.claudegateway.radar.RadarSubjectProjectState;
import fr.claudegateway.radar.RadarSubjectRepository;

/**
 * Nature, qui agit, sujet (F-180 / SF-180-02) : l'heuristique ne fait que proposer ; l'agent pose la
 * nature ; le sujet unique lié au terminal est rempli ; l'étape bloquée et les gestes « à toi » se voient.
 */
class TerminalActionNatureTest {

    private final TerminalActionRepository repository = mock(TerminalActionRepository.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final RadarSubjectProjectRepository links = mock(RadarSubjectProjectRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private Workspace terminal;
    private TerminalActionService service;

    @BeforeEach
    void setUp() {
        terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setHostId(hostId);
        terminal.setName("data-platform");
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(terminal);
        when(workspaces.findByIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(terminal));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.findByUserIdAndWorkspaceIdAndDedupKey(any(), any(), any())).thenReturn(Optional.empty());
        service = new TerminalActionService(repository, workspaceService, clock);
        service.setSubjectResolver(new TerminalActionSubjectResolver(links, mock(RadarSubjectRepository.class)));
    }

    private RadarSubjectProject link(UUID subject) {
        return RadarSubjectProject.builder().userId(userId).hostId(hostId).subjectId(subject)
                .workspaceId(workspaceId).state(RadarSubjectProjectState.CONFIRMED).build();
    }

    @Test
    @DisplayName("heuristique : accès, validation, incident, transmission, info")
    void suggestCategorie() {
        assertThat(TerminalActionQualifier.suggestCategorie("Demander l'accès VPN à Karim"))
                .isEqualTo(TerminalActionCategorie.ACCES);
        assertThat(TerminalActionQualifier.suggestCategorie("Obtenir la validation du RSSI"))
                .isEqualTo(TerminalActionCategorie.VALIDATION);
        assertThat(TerminalActionQualifier.suggestCategorie("Ouvrir un ticket pour la panne du proxy"))
                .isEqualTo(TerminalActionCategorie.INCIDENT);
        assertThat(TerminalActionQualifier.suggestCategorie("Envoyer le fichier d'export"))
                .isEqualTo(TerminalActionCategorie.TRANSMISSION);
        assertThat(TerminalActionQualifier.suggestCategorie("Savoir quel environnement cibler"))
                .isEqualTo(TerminalActionCategorie.INFO);
    }

    @Test
    @DisplayName("heuristique : un geste à soi, un tiers, sinon le client")
    void suggestQui() {
        TerminalAction sso = TerminalAction.builder().description("Se reconnecter en aws sso login").build();
        assertThat(TerminalActionQualifier.suggestQui(sso)).isEqualTo(TerminalActionQui.MOI);
        TerminalAction support = TerminalAction.builder().description("Ouvrir un cas au support Atlassian").build();
        assertThat(TerminalActionQualifier.suggestQui(support)).isEqualTo(TerminalActionQui.TIERS);
        TerminalAction remi = TerminalAction.builder().description("Demander le compte").person("Rémi").build();
        assertThat(TerminalActionQualifier.suggestQui(remi)).isEqualTo(TerminalActionQui.CLIENT);
    }

    @Test
    @DisplayName("l'agent pose la nature et qui agit à l'inscription")
    void recordWithQualification() {
        var rec = service.record(userId, workspaceId, null, "Demander l'accès VPN", null, "Karim",
                TerminalActionKind.ACTION, "vpn",
                new TerminalActionService.Qualification(TerminalActionCategorie.ACCES, TerminalActionQui.CLIENT));
        assertThat(rec.action().getCategorie()).isEqualTo(TerminalActionCategorie.ACCES);
        assertThat(rec.action().getQui()).isEqualTo(TerminalActionQui.CLIENT);
    }

    @Test
    @DisplayName("une valeur inconnue est une erreur lisible")
    void unknownValue() {
        assertThatThrownBy(() -> TerminalActionService.Qualification.parse("ACCESS", null))
                .isInstanceOf(InvalidTerminalActionException.class).hasMessageContaining("ACCES");
        assertThatThrownBy(() -> TerminalActionService.Qualification.parse(null, "LUI"))
                .isInstanceOf(InvalidTerminalActionException.class).hasMessageContaining("MOI");
    }

    @Test
    @DisplayName("outil : categorie inconnue → résultat en erreur, le tour continue")
    void toolRejectsUnknown() throws Exception {
        var executor = new TerminalActionToolExecutor(service);
        var input = new ObjectMapper().readTree("{\"description\":\"x\",\"categorie\":\"NAWAK\"}");
        var outcome = executor.execute(userId, terminal, input);
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("categorie vaut");
    }

    @Test
    @DisplayName("sujet : un seul sujet lié et confirmé → posé ; deux → nul")
    void subjectFilledOnlyWhenUnique() {
        UUID subject = UUID.randomUUID();
        when(links.findByUserIdAndHostIdAndState(userId, hostId, RadarSubjectProjectState.CONFIRMED))
                .thenReturn(List.of(link(subject)));
        assertThat(service.create(userId, workspaceId, null, "x", null, null, null).getSubjectId())
                .isEqualTo(subject);

        when(links.findByUserIdAndHostIdAndState(userId, hostId, RadarSubjectProjectState.CONFIRMED))
                .thenReturn(List.of(link(subject), link(UUID.randomUUID())));
        assertThat(service.create(userId, workspaceId, null, "y", null, null, null).getSubjectId()).isNull();
    }

    @Test
    @DisplayName("liste du tour : la nature et « geste à toi » en tête du crochet")
    void turnNoteLine() {
        TerminalAction a = TerminalAction.builder().id(UUID.randomUUID()).workspaceId(workspaceId)
                .description("Se reconnecter en SSO").status(TerminalActionStatus.A_FAIRE)
                .categorie(TerminalActionCategorie.ACCES).qui(TerminalActionQui.MOI)
                .createdAt(OffsetDateTime.now(clock)).build();
        assertThat(TerminalActionTurnNote.line(a, OffsetDateTime.now(clock), null))
                .startsWith("- [ACCES · GESTE À TOI · À FAIRE");
    }

    @Test
    @DisplayName("tableau : l'étape bloquée de SON terminal, le compte des gestes à toi")
    void boardShowsBlockedStepAndMoi() {
        TerminalAction a = TerminalAction.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .hostId(hostId).description("Demander l'accès forge").dedupKey("acces-forge")
                .status(TerminalActionStatus.A_FAIRE).qui(TerminalActionQui.MOI)
                .createdAt(OffsetDateTime.now(clock)).build();
        when(repository.findBoardOfHost(eq(userId), eq(hostId), any(), any())).thenReturn(List.of(a));
        SubjectJourneyRepository journeys = mock(SubjectJourneyRepository.class);
        SubjectJourney journey = new SubjectJourney();
        journey.setWorkspaceId(workspaceId);
        journey.setPlanJson("[{\"title\":\"Cadrer\"},{\"title\":\"Préparer\"},"
                + "{\"title\":\"Déployer\",\"waitsOn\":\"Accès forge\"}]");
        when(journeys.findByUserIdAndWorkspaceIdIn(eq(userId), anyCollection())).thenReturn(List.of(journey));
        TerminalActionQueryService queries = new TerminalActionQueryService(repository, workspaces, clock,
                new TerminalActionFollowUp(3));
        queries.setJourneys(journeys);

        var board = queries.board(userId, workspaceId);

        assertThat(board.moi()).isEqualTo(1);
        var step = board.here().get(0).blockedStep();
        assertThat(step).isNotNull();
        assertThat(step.number()).isEqualTo(3);
        assertThat(step.title()).isEqualTo("Déployer");
        assertThat(step.terminal()).isEqualTo("data-platform");
    }
}
