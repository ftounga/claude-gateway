package fr.claudegateway.runner.toolchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.runner.RunnerTokenService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>La chaîne vidéo servie par le portail</b> (F-215 / SF-215-06), de bout en bout : manifeste, signature et paquet
 * pour le seul porteur d'un jeton runner ; reprise par {@code Range} ; refus nets sinon.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoToolchainApiIntegrationTest {

    private static final String BASE = "/api/runner/toolchain/video/";
    private static final String VERSION = "0.8.193-r9";
    private static final String TOKEN_HEADER = "X-Runner-Token";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenService tokenService;
    @Autowired private WorkspaceStorage storage;

    private String runnerToken;
    private final byte[] bundle = new byte[200_000];
    private final byte[] manifest = "{\n  \"kind\": \"video-toolchain\",\n  \"version\": \"0.8.193-r9\"\n}\n"
            .getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        User user = userRepository.save(User.builder().email("toolchain-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        RunnerHost host = hostRepository.save(RunnerHost.builder().userId(user.getId()).name("CAGIP").build());
        runnerToken = tokenService.issue(user.getId(), host.getId(), "poste-cagip").clearToken();
        for (int i = 0; i < bundle.length; i++) {
            bundle[i] = (byte) (i * 31 + 7);
        }
        storage.putFile("toolchain/video/" + VERSION + "/manifest.json", manifest, "application/json");
        storage.putFile("toolchain/video/" + VERSION + "/manifest.json.sig", "c2lnbmF0dXJl\n".getBytes(), "text/plain");
        storage.putFile("toolchain/video/" + VERSION + "/linux-x64.tar.gz", bundle, "application/gzip");
    }

    @AfterEach
    void tearDown() {
        storage.deletePrefix("toolchain/video/" + VERSION + "/");
    }

    @Test
    @DisplayName("le manifeste est servi à l'octet près (la signature porte sur ces octets)")
    void manifestIsServedByteForByte() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + VERSION + "/manifest").contextPath("/api")
                        .header(TOKEN_HEADER, runnerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(manifest);
    }

    @Test
    @DisplayName("la signature est servie en texte")
    void signatureIsServed() throws Exception {
        mockMvc.perform(get(BASE + VERSION + "/signature").contextPath("/api").header(TOKEN_HEADER, runnerToken))
                .andExpect(status().isOk())
                .andExpect(content().string("c2lnbmF0dXJl"));
    }

    @Test
    @DisplayName("le paquet est servi en entier, avec Accept-Ranges")
    void bundleIsServedWhole() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + VERSION + "/linux-x64").contextPath("/api")
                        .header(TOKEN_HEADER, runnerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Length", String.valueOf(bundle.length)))
                .andExpect(header().string("Content-Type", "application/gzip"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bundle);
    }

    @Test
    @DisplayName("une plage reprend le téléchargement : 206 et Content-Range")
    void rangeResumesTheDownload() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + VERSION + "/linux-x64").contextPath("/api")
                        .header(TOKEN_HEADER, runnerToken).header("Range", "bytes=150000-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 150000-199999/200000"))
                .andExpect(header().string("Content-Length", "50000"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray())
                .isEqualTo(Arrays.copyOfRange(bundle, 150_000, 200_000));
    }

    @Test
    @DisplayName("une plage hors fichier répond 416 avec la taille")
    void rangeBeyondTheFileIs416() throws Exception {
        mockMvc.perform(get(BASE + VERSION + "/linux-x64").contextPath("/api")
                        .header(TOKEN_HEADER, runnerToken).header("Range", "bytes=200000-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */200000"));
    }

    @Test
    @DisplayName("sans jeton, ou avec un jeton refusé : 401 générique sur les trois routes")
    void withoutAValidTokenEverythingIs401() throws Exception {
        for (String path : new String[] {"/manifest", "/signature", "/linux-x64"}) {
            mockMvc.perform(get(BASE + VERSION + path).contextPath("/api"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get(BASE + VERSION + path).contextPath("/api").header(TOKEN_HEADER, "faux-jeton"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Jeton runner refusé."));
        }
    }

    @Test
    @DisplayName("version non publiée, plateforme sans paquet, version mal formée : 404 explicite")
    void unpublishedThingsAre404() throws Exception {
        mockMvc.perform(get(BASE + "0.0.0-r0/manifest").contextPath("/api").header(TOKEN_HEADER, runnerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("video_toolchain_unavailable"));
        mockMvc.perform(get(BASE + VERSION + "/win-x64").contextPath("/api").header(TOKEN_HEADER, runnerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("video_toolchain_unavailable"));
        mockMvc.perform(get(BASE + "-bad/manifest").contextPath("/api").header(TOKEN_HEADER, runnerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("une plateforme hors liste n'est pas une route runner déclarée : refusée par la chaîne runner")
    void unknownPlatformIsRefusedByTheRunnerChain() throws Exception {
        mockMvc.perform(get(BASE + VERSION + "/linux-arm64").contextPath("/api").header(TOKEN_HEADER, runnerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("aucun utilisateur n'est posé dans le SecurityContext (D9)")
    void runnerRoutesNeverSetAUserPrincipal() throws Exception {
        mockMvc.perform(get(BASE + VERSION + "/manifest").contextPath("/api").header(TOKEN_HEADER, runnerToken))
                .andExpect(status().isOk());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
