package fr.claudegateway.jarvis.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionRepository;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le banc PANCARTES, côté passerelle</b> (F-248 / SF-248-10, cadrage §6, §8) — 40 scénarios déterministes rejoués
 * <b>sans modèle</b> (fournisseur simulé) sur {@code POST /api/jarvis/converse} : lecture d'office (attentes) et lecture
 * ciblée (outil {@code etat}), présentation, secrets semés, un second compte. Portes : <b>0 pancarte hors
 * catalogue</b>, <b>0 avant le premier mot</b>, <b>0 secret</b>, <b>présentation vide</b>, ciblée ⇔ outil lu.
 */
@SpringBootTest(properties = "app.jarvis.fast-brain.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCardsBenchTest {

    static final List<String> SECRETS = List.of("hunter2hunter2", "glpat-abcdefghijklmnop1234", "BOB-SECRET");
    private static final Pattern IDS = Pattern.compile("\"id\":\"(fait:[^\"]+)\"");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private TerminalActionRepository actions;
    @Autowired private JarvisStateRegistry registry;
    @Autowired private JwtService jwt;
    @MockitoBean private AiAgentProvider provider;

    /** Le script du fournisseur pour le scénario en cours : appeler {@code etat} d'abord, ou répondre tout de suite. */
    private final AtomicReference<Boolean> callEtat = new AtomicReference<>(false);

    record Seed(String token, UUID subject, Set<String> allowedPrefixes) {
    }

    private Seed seed(int n) {
        User u = users.save(User.builder().email("banc-" + n + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
        UUID host = hosts.save(RunnerHost.builder().userId(u.getId()).name("CAGIP-" + n).rootName("dev").os("linux")
                .shell("posix").elevated(false).lastSeenAt(OffsetDateTime.now()).build()).getId();
        UUID ws = workspaces.save(Workspace.builder().userId(u.getId()).name("Sujet " + n).hostId(host)
                .projectPath("s" + n).executionTarget(WorkspaceExecutionTarget.RUNNER).build()).getId();
        OffsetDateTime at = OffsetDateTime.now().minusHours(2);
        Set<String> allowed = new HashSet<>();
        for (int i = 0; i < 1 + n % 4; i++) {
            UUID a = actions.save(TerminalAction.builder().userId(u.getId()).workspaceId(ws)
                    .description("Attente " + i + " password=hunter2hunter2").kind(TerminalActionKind.ACTION)
                    .status(i % 2 == 0 ? TerminalActionStatus.A_FAIRE : TerminalActionStatus.DEMANDE).person("Rémi")
                    .createdAt(at).updatedAt(at).build()).getId();
            allowed.add("fait:attente:" + a);
        }
        String key = "JIRA:BANC-" + (100 + n);
        registry.record(u.getId(), List.of(
                new JarvisStateRegistry.Observation(host, ws, "TICKET", key, "status", "En revue", at, "TOUR", null,
                        "HAUTE"),
                new JarvisStateRegistry.Observation(host, ws, "TICKET", key, "title",
                        "Migration glpat-abcdefghijklmnop1234", at, "TOUR", null, "HAUTE")));
        allowed.add(JarvisStateCards.id(host, key));
        allowed.add("fait:personne:"); // préfixe : les personnes de l'univers du compte
        return new Seed(jwt.generateToken(u), ws, allowed);
    }

    private String converse(Seed seed, boolean presentation) throws Exception {
        MvcResult started = mvc.perform(post("/api/jarvis/converse").contextPath("/api")
                .header("Authorization", "Bearer " + seed.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":\"" + seed.subject() + "\",\"text\":\"Merci Jarvis\""
                        + (presentation ? ",\"presentation\":true" : "") + "}"))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    @DisplayName("40 scénarios : 0 hors catalogue, 0 avant le premier mot, 0 secret, présentation vide, ciblée ⇔ etat lu")
    void bench() throws Exception {
        // Un second compte, dont rien ne doit jamais apparaître ailleurs.
        Seed bob = seed(999);
        actions.save(TerminalAction.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .description("BOB-SECRET").kind(TerminalActionKind.ACTION).status(TerminalActionStatus.A_FAIRE)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        assertThat(bob).isNotNull();
        when(provider.nextTurn(any(AgentTurnRequest.class), any())).thenAnswer(inv -> {
            AgentTurnRequest call = inv.getArgument(0);
            AiAgentProvider.AgentTextListener listener = inv.getArgument(1);
            boolean afterTool = JSON.writeValueAsString(call.messages()).contains("etat-1");
            if (Boolean.TRUE.equals(callEtat.get()) && !afterTool) {
                return new AgentTurn("", List.of(new AgentToolCall("etat-1", "etat",
                        JSON.readTree("{\"cle\":\"\",\"famille\":\"ticket\",\"client\":\"\"}"))), false, 100, 10);
            }
            listener.onTextDelta("Bien, Monsieur.");
            return new AgentTurn("Bien, Monsieur.", List.of(), true, 100, 8);
        });
        int withCards = 0;
        int targetedSets = 0;
        List<String> failures = new ArrayList<>();
        for (int n = 0; n < 40; n++) {
            boolean presentation = n % 8 == 7;
            boolean etat = !presentation && n % 3 == 0;
            callEtat.set(etat);
            Seed seed = seed(n);
            String body = converse(seed, presentation);
            int cards = body.indexOf("event:cards");
            if (presentation) {
                if (cards >= 0) {
                    failures.add(n + " : pancartes en présentation");
                }
                continue;
            }
            if (cards < 0) {
                failures.add(n + " : aucun jeu alors que des faits ont été lus");
                continue;
            }
            withCards++;
            int text = body.indexOf("event:text");
            if (text < 0 || text > cards) {
                failures.add(n + " : jeu avant le premier mot");
            }
            if (cards > body.indexOf("event:done")) {
                failures.add(n + " : jeu après done");
            }
            String payload = body.substring(cards, body.indexOf("event:done"));
            Matcher m = IDS.matcher(payload);
            while (m.find()) {
                String id = m.group(1);
                boolean known = seed.allowedPrefixes().contains(id)
                        || (id.startsWith("fait:personne:") && seed.allowedPrefixes().contains("fait:personne:"));
                if (!known) {
                    failures.add(n + " : pancarte hors catalogue " + id);
                }
            }
            for (String secret : SECRETS) {
                if (payload.contains(secret)) {
                    failures.add(n + " : secret émis " + secret);
                }
            }
            boolean targeted = payload.contains("\"targeted\":true");
            if (targeted) {
                targetedSets++;
            }
            if (targeted != etat) {
                failures.add(n + " : ciblée=" + targeted + " alors que etat=" + etat);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(withCards).isEqualTo(35);
        assertThat(targetedSets).isGreaterThanOrEqualTo(10);
    }
}
