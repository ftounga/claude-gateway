package fr.claudegateway.atelier.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.TerminalActionFollowUp;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourneyChantierRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyEventRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.notifications.AttentesDigestService;
import fr.claudegateway.notifications.NotificationDigestWriter;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-220 / SF-220-05 — reporter un chantier (il sort des compteurs du tableau du client) et le rappel quotidien
 * des chantiers dormants (une fois par jour, jamais pour un chantier reporté) ; Bob ne touche à rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChantierSnoozeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubjectJourneyRepository journeyRepository;
    @Autowired private SubjectJourneyChantierRepository chantierRepository;
    @Autowired private SubjectJourneyEventRepository eventRepository;
    @Autowired private ChantierSnoozeRepository snoozeRepository;
    @Autowired private SubjectJourneyService service;
    @Autowired private DormantChantierScanner scanner;
    @Autowired private TerminalActionRepository actions;
    @Autowired private TerminalActionFollowUp followUp;
    @Autowired private NotificationDigestWriter digests;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private RunnerHost host;
    private Workspace ingress;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from notification_digests");
        snoozeRepository.deleteAll();
        eventRepository.deleteAll();
        chantierRepository.deleteAll();
        journeyRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();
        User alice = userRepository.save(User.builder().email("alice-snooze@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        User bob = userRepository.save(User.builder().email("bob-snooze@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        bobToken = jwtService.generateToken(bob);
        host = hostRepository.save(RunnerHost.builder().userId(aliceId).name("EDENRED").rootName("dev")
                .os("linux").shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now()).build());
        ingress = workspaceRepository.save(Workspace.builder().userId(aliceId).name("ingress")
                .hostId(host.getId()).projectPath("ingress")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
        mockMvc.perform(put("/api/workspaces/" + ingress.getId() + "/journey/mode").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"GUIDE\",\"title\":\"Cert\"}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        service.submitDiagnosis(aliceId, ingress.getId(), "cert expiré", "openssl", "ELEVEE");
        service.confirmDiagnosis(aliceId, ingress.getId());
        service.setPlan(aliceId, ingress.getId(), JourneyPlan.fromToolInput(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("[{\"title\":\"Lire\",\"risk\":\"LECTURE\"}]")));
        service.validatePlan(aliceId, ingress.getId(), null);
    }

    private ResultActions snooze(String token, int number, String body) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ingress.getId() + "/journey/chantiers/" + number + "/snooze")
                .contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions unsnooze(String token, int number) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/" + ingress.getId() + "/journey/chantiers/" + number + "/snooze")
                .contextPath("/api").header("Authorization", "Bearer " + token));
    }

    private ResultActions board() throws Exception {
        return mockMvc.perform(get("/api/runner-hosts/" + host.getId() + "/chantiers").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken));
    }

    private void backdate(int days) {
        OffsetDateTime old = OffsetDateTime.now().minusDays(days).minusHours(1);
        jdbc.update("update subject_journeys set updated_at = ?, chantier_opened_at = ? where workspace_id = ?",
                old, old, ingress.getId());
    }

    @Test
    @DisplayName("reporter : le chantier sort des compteurs ; reporter encore remplace ; ne plus reporter le rend")
    void snoozeAndUnsnooze() throws Exception {
        board().andExpect(jsonPath("$.counts.outil", is(1))).andExpect(jsonPath("$.counts.snoozed", is(0)))
                .andExpect(jsonPath("$.chantiers[0].snoozedUntil", nullValue()));

        snooze(aliceToken, 1, "{\"days\":3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number", is(1)))
                .andExpect(jsonPath("$.until", notNullValue()));
        OffsetDateTime first = snoozeRepository.findAll().get(0).getSnoozedUntil();
        assertThat(first).isBetween(OffsetDateTime.now().plusDays(3).minusMinutes(5),
                OffsetDateTime.now().plusDays(3).plusMinutes(5));
        board().andExpect(jsonPath("$.counts.outil", is(0))).andExpect(jsonPath("$.counts.snoozed", is(1)))
                .andExpect(jsonPath("$.chantiers[0].snoozedUntil", notNullValue()));

        snooze(aliceToken, 1, "{\"days\":7}").andExpect(status().isOk());
        assertThat(snoozeRepository.findAll()).hasSize(1);
        assertThat(snoozeRepository.findAll().get(0).getSnoozedUntil()).isAfter(first.plusDays(3));

        unsnooze(aliceToken, 1).andExpect(status().isNoContent());
        unsnooze(aliceToken, 1).andExpect(status().isNoContent());
        board().andExpect(jsonPath("$.counts.outil", is(1))).andExpect(jsonPath("$.counts.snoozed", is(0)));
    }

    @Test
    @DisplayName("refus : jours hors de 1 à 30, chantier non ouvert (400) ; Bob → 404")
    void refusals() throws Exception {
        snooze(aliceToken, 1, "{\"days\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Reporter de 1 à 30 jours.")));
        snooze(aliceToken, 1, "{\"days\":31}").andExpect(status().isBadRequest());
        snooze(aliceToken, 1, "{}").andExpect(status().isBadRequest());
        snooze(aliceToken, 9, "{\"days\":3}").andExpect(status().isBadRequest());
        snooze(bobToken, 1, "{\"days\":3}").andExpect(status().isNotFound());
        unsnooze(bobToken, 1).andExpect(status().isNotFound());
        assertThat(snoozeRepository.findAll()).isEmpty();
    }

    private AttentesDigestService digestAt(PushNotificationService push, OffsetDateTime now) {
        AttentesDigestService digest = new AttentesDigestService(actions, followUp, digests, push,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
        digest.setDormant(scanner);
        return digest;
    }

    @Test
    @DisplayName("rappel : un chantier dormant → « Des chantiers dorment », une fois par jour ; reporté → rien")
    void dormantReminder() throws Exception {
        PushNotificationService push = mock(PushNotificationService.class);
        OffsetDateTime now = OffsetDateTime.now();

        digestAt(push, now).run();
        verify(push, never()).notify(eq(aliceId), any(), any()); // actif : rien

        backdate(6);
        assertThat(scanner.usersWithDormant(now)).contains(aliceId);
        digestAt(push, now).run();
        digestAt(push, now.plusMinutes(1)).run();
        verify(push, times(1)).notify(eq(aliceId), isNull(), eq(PushEvent.CHANTIERS_DORMANT));

        snooze(aliceToken, 1, "{\"days\":3}").andExpect(status().isOk());
        assertThat(scanner.usersWithDormant(now)).doesNotContain(aliceId);
    }

    @Test
    @DisplayName("une seule notification par jour : la relance due passe avant les chantiers dormants")
    void priority() {
        UUID someone = UUID.randomUUID();
        assertThat(AttentesDigestService.dominant(someone, Set.of(), Set.of(someone)))
                .isEqualTo(PushEvent.ATTENTES_TO_FOLLOW_UP);
        assertThat(AttentesDigestService.dominant(someone, Set.of(someone), Set.of(someone)))
                .isEqualTo(PushEvent.ATTENTES_POINT_DU_MATIN);
        assertThat(AttentesDigestService.dominant(someone, Set.of(), Set.of()))
                .isEqualTo(PushEvent.CHANTIERS_DORMANT);
        assertThat(PushEvent.CHANTIERS_DORMANT.critical()).isFalse();
        assertThat(PushEvent.CHANTIERS_DORMANT.body()).doesNotContainPattern("\\d");
    }
}
