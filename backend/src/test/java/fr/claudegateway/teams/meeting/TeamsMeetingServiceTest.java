package fr.claudegateway.teams.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.teams.TeamsToolCatalog;
import fr.claudegateway.teams.meeting.dto.CreateMeetingRequest;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;

/**
 * Tests unitaires du service Réunions (F-128 / SF-128-01) : création nominale + ordre runner, refus de
 * validation, échec runner (aucune ligne persistée), transitions d'état, isolation par le finder scopé.
 */
@ExtendWith(MockitoExtension.class)
class TeamsMeetingServiceTest {

    @Mock private MeetingRepository repository;
    @Mock private WorkspaceService workspaceService;
    @Mock private RunnerToolGateway runnerToolGateway;

    private TeamsMeetingService service;
    private RadarScope scope;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TeamsMeetingService(repository, workspaceService, runnerToolGateway, new ObjectMapper());
        scope = new RadarScope(userId, hostId);
    }

    private void stubTeamsTerminal() {
        Workspace terminal = new Workspace();
        terminal.setHostId(hostId);
        // id/projectPath restent nuls : RunnerTargets.of les tolère (projectPath -> "").
        when(workspaceService.openTeamsTerminal(userId, hostId)).thenReturn(terminal);
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 0L, null, null, null, "", false);
    }

    @Test
    @DisplayName("Création nominale : ordre runner teams_meeting_join émis, ligne RECORDING persistée")
    void create_nominal() {
        stubTeamsTerminal();
        when(runnerToolGateway.teamsRead(any(RunnerTarget.class), anyString(), eq(TeamsToolCatalog.MEETING_JOIN),
                any(JsonNode.class))).thenReturn(ok("cap-42"));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));

        MeetingResponse response = service.create(scope, new CreateMeetingRequest(
                "https://teams.microsoft.com/l/meetup-join/xyz", "Comité", null, true, null));

        assertThat(response.state()).isEqualTo("RECORDING");
        assertThat(response.retentionDays()).isEqualTo(Meeting.DEFAULT_RETENTION_DAYS);
        assertThat(response.consentAcknowledged()).isTrue();
        assertThat(response.captureRef()).isEqualTo("cap-42");

        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(runnerToolGateway).teamsRead(any(RunnerTarget.class), anyString(),
                eq(TeamsToolCatalog.MEETING_JOIN), input.capture());
        assertThat(input.getValue().path("url").asText()).isEqualTo("https://teams.microsoft.com/l/meetup-join/xyz");
        assertThat(input.getValue().path("purpose").asText()).isEqualTo("meeting");
        assertThat(input.getValue().path("participants_informed").asBoolean()).isTrue();
        ArgumentCaptor<Meeting> saved = ArgumentCaptor.forClass(Meeting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getHostId()).isEqualTo(hostId);
    }

    @Test
    @DisplayName("Refus sans consentement : 400 et aucun ordre runner ni persistance")
    void create_withoutConsent() {
        assertThatThrownBy(() -> service.create(scope, new CreateMeetingRequest(
                "https://teams.microsoft.com/x", null, null, false, null)))
                .isInstanceOf(MeetingValidationException.class);
        verify(runnerToolGateway, never()).teamsRead(any(), anyString(), anyString(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Refus URL non http(s) : 400")
    void create_badUrl() {
        assertThatThrownBy(() -> service.create(scope, new CreateMeetingRequest(
                "ftp://nope", null, null, true, null)))
                .isInstanceOf(MeetingValidationException.class);
    }

    @Test
    @DisplayName("Refus rétention hors bornes : 400")
    void create_badRetention() {
        assertThatThrownBy(() -> service.create(scope, new CreateMeetingRequest(
                "https://teams.microsoft.com/x", null, null, true, 999)))
                .isInstanceOf(MeetingValidationException.class);
    }

    @Test
    @DisplayName("Échec runner : MeetingCaptureException et AUCUNE ligne persistée (pas de fantôme)")
    void create_runnerFailure() {
        stubTeamsTerminal();
        when(runnerToolGateway.teamsRead(any(RunnerTarget.class), anyString(), eq(TeamsToolCatalog.MEETING_JOIN),
                any(JsonNode.class)))
                .thenReturn(RunnerCallResult.backendError("runner_unavailable", "poste éteint"));

        assertThatThrownBy(() -> service.create(scope, new CreateMeetingRequest(
                "https://teams.microsoft.com/x", null, null, true, 30)))
                .isInstanceOf(MeetingCaptureException.class)
                .extracting(e -> ((MeetingCaptureException) e).code())
                .isEqualTo(MeetingCaptureException.RUNNER_UNAVAILABLE);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Arrêt : RECORDING -> STOPPED avec endedAt ; second arrêt refusé (409)")
    void stop_transitions() {
        Meeting m = Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.RECORDING)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30).build();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserIdAndHostId(id, userId, hostId)).thenReturn(Optional.of(m));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));

        MeetingResponse stopped = service.stop(scope, id);
        assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(stopped.endedAt()).isNotNull();

        assertThatThrownBy(() -> service.stop(scope, id)).isInstanceOf(MeetingStateException.class);
    }

    @Test
    @DisplayName("Pause puis reprise : RECORDING <-> PAUSED")
    void pause_resume() {
        Meeting m = Meeting.builder().userId(userId).hostId(hostId).state(MeetingState.RECORDING)
                .meetingUrl("https://x").consentAcknowledged(true).retentionDays(30).build();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserIdAndHostId(id, userId, hostId)).thenReturn(Optional.of(m));
        when(repository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.pause(scope, id).state()).isEqualTo("PAUSED");
        assertThat(service.resume(scope, id).state()).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("Isolation : un id hors périmètre (finder scopé vide) -> 404")
    void get_isolation() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserIdAndHostId(id, userId, hostId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(scope, id)).isInstanceOf(MeetingNotFoundException.class);
    }
}
