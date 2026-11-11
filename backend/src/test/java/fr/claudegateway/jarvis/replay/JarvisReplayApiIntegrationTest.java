package fr.claudegateway.jarvis.replay;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.timing.JarvisTurnTiming;
import fr.claudegateway.jarvis.timing.JarvisTurnTimingRepository;
import fr.claudegateway.jarvis.trace.JarvisListenTraceEvent;
import fr.claudegateway.jarvis.trace.JarvisListenTraceRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-255 / SF-255-02 — le rejeu de séance : ADMIN seulement, ses propres séances, fenêtre validée. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisReplayApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private JarvisListenTraceRepository traces;
    @Autowired private JarvisTurnTimingRepository timings;
    @Autowired private AtelierMessageRepository messages;
    @Autowired private JwtService jwt;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;
    private String carolToken;
    private OffsetDateTime spoke;

    @BeforeEach
    void setUp() {
        alice = user("replay-alice-" + UUID.randomUUID() + "@ex.com", UserRole.ADMIN);
        bob = user("replay-bob-" + UUID.randomUUID() + "@ex.com", UserRole.ADMIN);
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(bob);
        carolToken = jwt.generateToken(user("replay-carol-" + UUID.randomUUID() + "@ex.com", UserRole.USER));
        spoke = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusSeconds(3);
        turn(alice, "tour-alice", "Où en est le waf, Jarvis ?");
        turn(bob, "tour-bob", "Secret de Bob");
    }

    private User user(String email, UserRole role) {
        return users.save(User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL).role(role)
                .createdAt(OffsetDateTime.now()).build());
    }

    /** Un tour exact (SF-255-01) et la parole rangée au fil Jarvis du compte. */
    private void turn(User owner, String turnId, String said) {
        Workspace thread = workspaces.save(Workspace.builder().userId(owner.getId()).name("Jarvis")
                .source(fr.claudegateway.atelier.WorkspaceSource.ARCHIVE)
                .executionTarget(fr.claudegateway.atelier.WorkspaceExecutionTarget.SANDBOX)
                .jarvisThreadUserId(owner.getId()).build());
        String page = "page-" + turnId;
        event(owner, page, turnId, 1, "listen_open", null, spoke.minusSeconds(5));
        event(owner, page, turnId, 2, "listen_end", "voiced=900", spoke);
        event(owner, page, turnId, 3, "transcript", "chars=" + said.length() + " mode=CONV_STREAM",
                spoke.plusNanos(800_000_000));
        timings.save(JarvisTurnTiming.builder().userId(owner.getId()).createdAt(spoke.plusSeconds(2))
                .listenMode("CONV_STREAM").brain("FAST").firstSoundMs(4_200).turnId(turnId).pageId(page).build());
        messages.save(AtelierMessage.builder().workspaceId(thread.getId()).userId(owner.getId()).role("USER")
                .content(said).via("JARVIS").brain("FAST").build());
    }

    private void event(User owner, String page, String turnId, int seq, String ev, String detail,
            OffsetDateTime at) {
        traces.save(JarvisListenTraceEvent.builder().userId(owner.getId()).pageId(page).seq(seq).atMs(seq * 1000L)
                .event(ev).detail(detail).turnId(turnId).createdAt(at.plusSeconds(1)).occurredAt(at).build());
    }

    private ResultActions get(String token, String path) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api" + path)
                .contextPath("/api").header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("séances : Alice voit sa séance et son bilan ; jamais le tour de Bob")
    void sessionsAreIsolated() throws Exception {
        get(aliceToken, "/admin/jarvis/replay/sessions").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].turns").value(1))
                .andExpect(jsonPath("$[0].bilan.heard").value(1))
                .andExpect(jsonPath("$[0].bilan.slow").value(1));
    }

    @Test
    @DisplayName("rejeu : tour exact, ce qui a été dit, verdicts ; Bob ne lit pas Alice")
    void replayIsIsolated() throws Exception {
        String from = spoke.minusMinutes(1).toString();
        String to = spoke.plusMinutes(1).toString();
        get(aliceToken, "/admin/jarvis/replay?from=" + from + "&to=" + to).andExpect(status().isOk())
                .andExpect(jsonPath("$.turns", hasSize(1)))
                .andExpect(jsonPath("$.turns[0].turnId").value("tour-alice"))
                .andExpect(jsonPath("$.turns[0].correlation").value("IDENTIFIANT"))
                .andExpect(jsonPath("$.turns[0].heard.userText").value("Où en est le waf, Jarvis ?"))
                .andExpect(jsonPath("$.turns[0].steps.firstSoundMs").value(4200))
                .andExpect(jsonPath("$.turns[0].verdicts[?(@.code == 'LENT')]").exists())
                .andExpect(jsonPath("$.thresholds.slowFirstSoundMs").value(3000));
        get(bobToken, "/admin/jarvis/replay?from=" + from + "&to=" + to).andExpect(status().isOk())
                .andExpect(jsonPath("$.turns", hasSize(1)))
                .andExpect(jsonPath("$.turns[0].turnId").value("tour-bob"))
                .andExpect(jsonPath("$.turns[0].heard.userText").value("Secret de Bob"));
    }

    @Test
    @DisplayName("erreurs : non ADMIN 403 ; fenêtre absente, inversée ou trop longue 400")
    void errors() throws Exception {
        get(carolToken, "/admin/jarvis/replay/sessions").andExpect(status().isForbidden());
        get(carolToken, "/admin/jarvis/replay?from=2026-11-09T16:00:00Z&to=2026-11-09T17:00:00Z")
                .andExpect(status().isForbidden());
        get(aliceToken, "/admin/jarvis/replay").andExpect(status().isBadRequest());
        get(aliceToken, "/admin/jarvis/replay?from=hier&to=demain").andExpect(status().isBadRequest());
        get(aliceToken, "/admin/jarvis/replay?from=2026-11-09T17:00:00Z&to=2026-11-09T16:00:00Z")
                .andExpect(status().isBadRequest());
        get(aliceToken, "/admin/jarvis/replay?from=2026-11-09T00:00:00Z&to=2026-11-09T07:00:00Z")
                .andExpect(status().isBadRequest());
        get(aliceToken, "/admin/jarvis/replay?from=2020-01-01T00:00:00Z&to=2020-01-01T01:00:00Z")
                .andExpect(status().isOk()).andExpect(jsonPath("$.turns", hasSize(0)));
    }
}
