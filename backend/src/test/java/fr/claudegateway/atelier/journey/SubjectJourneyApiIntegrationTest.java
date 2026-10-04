package fr.claudegateway.atelier.journey;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Le parcours d'un sujet, de bout en bout (F-176) — et <b>le terminal d'Alice est introuvable pour
 * Bob</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubjectJourneyApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private SubjectJourneyRepository journeyRepository;
    @Autowired
    private SubjectJourneyEventRepository eventRepository;
    @Autowired
    private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        eventRepository.deleteAll();
        journeyRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        aliceToken = tokenFor("alice-journey@ex.com");
        bobToken = tokenFor("bob-journey@ex.com");
        workspaceId = createWorkspace(aliceToken);
    }

    private String tokenFor(String email) {
        User user = userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        subscriptionRepository.save(Subscription.builder().userId(user.getId())
                .planCode(PlanCode.GOLD).status(SubscriptionStatus.ACTIVE).build());
        return jwtService.generateToken(user);
    }

    private String createWorkspace(String token) throws Exception {
        String body = mockMvc.perform(multipart("/api/workspaces").file(zip()).contextPath("/api")
                        .param("name", "Incident ingress")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static MockMultipartFile zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("README.md"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new MockMultipartFile("file", "project.zip", "application/zip", out.toByteArray());
    }

    ResultActions getJourney(String token) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspaceId + "/journey").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    ResultActions putMode(String token, String mode) throws Exception {
        return mockMvc.perform(put("/api/workspaces/" + workspaceId + "/journey/mode").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"" + mode + "\"}")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("un sujet jamais décidé est Libre, sans phase — et rien n'est écrit en lisant")
    void defaultIsLibre() throws Exception {
        getJourney(aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("LIBRE")))
                .andExpect(jsonPath("$.phase", nullValue()));
        org.assertj.core.api.Assertions.assertThat(journeyRepository.count()).isZero();
    }

    @Test
    @DisplayName("passer en Guidé ouvre l'Investigation ; repasser en Libre garde la phase")
    void switchModes() throws Exception {
        putMode(aliceToken, "GUIDE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("GUIDE")))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")))
                .andExpect(jsonPath("$.phaseLabel", is("Investigation")));
        putMode(aliceToken, "LIBRE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("LIBRE")))
                .andExpect(jsonPath("$.phase", is("INVESTIGATION")));
        getJourney(aliceToken).andExpect(jsonPath("$.mode", is("LIBRE")));
        org.assertj.core.api.Assertions.assertThat(eventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("un mode inconnu est refusé, lisiblement")
    void unknownMode() throws Exception {
        putMode(aliceToken, "TURBO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("journey_invalid")));
    }

    @Test
    @DisplayName("ISOLATION : le terminal d'Alice est introuvable pour Bob, en lecture comme en écriture")
    void isolation() throws Exception {
        getJourney(bobToken).andExpect(status().isNotFound());
        putMode(bobToken, "GUIDE").andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(journeyRepository.count()).isZero();
    }
}
