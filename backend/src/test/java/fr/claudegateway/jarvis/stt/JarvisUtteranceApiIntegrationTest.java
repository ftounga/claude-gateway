package fr.claudegateway.jarvis.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.stt.utterance.UtteranceTranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** La retranscription de l'énoncé de bout en bout (F-239 / SF-239-04) : texte, vocabulaire isolé, erreurs. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisUtteranceApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisUsageService usage;

    @MockBean private UtteranceTranscriptionProvider provider;

    private User alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        reset(provider);
        when(provider.name()).thenReturn("elevenlabs");
        when(provider.isConfigured()).thenReturn(true);
        alice = userRepository.save(user("jarvis-utt-a-" + UUID.randomUUID() + "@ex.com"));
        aliceToken = jwtService.generateToken(alice);
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build();
    }

    private ResultActions send(String token, byte[] audio) throws Exception {
        var request = multipart("/api/jarvis/utterance");
        if (audio != null) {
            request.file(new MockMultipartFile("audio", "enonce.wav", "audio/wav", audio));
        }
        return mockMvc.perform(request.param("seconds", "2").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("200 : le texte retranscrit, le vocabulaire du compte (jamais celui d'autrui), la durée comptée")
    @SuppressWarnings("unchecked")
    void transcribesWithTheAccountsOwnVocabulary() throws Exception {
        workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("Kestrel-utt").build());
        User bob = userRepository.save(user("jarvis-utt-b-" + UUID.randomUUID() + "@ex.com"));
        workspaceRepository.save(Workspace.builder().userId(bob.getId()).name("Secret-de-Bob").build());
        when(provider.transcribe(any(), any(), any(), any())).thenReturn("Où en est Kestrel ?");

        send(aliceToken, new byte[64_000]).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Où en est Kestrel ?"))
                .andExpect(jsonPath("$.provider").value("elevenlabs-batch"));

        ArgumentCaptor<List<String>> vocabulary = ArgumentCaptor.forClass(List.class);
        verify(provider).transcribe(any(), any(), any(), vocabulary.capture());
        assertThat(vocabulary.getValue()).contains("Jarvis", "Kestrel-utt").doesNotContain("Secret-de-Bob");
        assertThat(usage.lastDays(alice.getId(), 1).get(0).getSttUtteranceSeconds()).isEqualTo(2L);
        assertThat(usage.lastDays(alice.getId(), 1).get(0).getSttStreamSeconds()).isZero();
    }

    @Test
    @DisplayName("400 sans audio ; 502 échec du fournisseur (sans détail) ; 503 non configuré ; 401 sans jeton")
    void errors() throws Exception {
        send(aliceToken, null).andExpect(status().isBadRequest());
        when(provider.transcribe(any(), any(), any(), any())).thenThrow(new TranscriptionProviderException("détail"));
        send(aliceToken, new byte[100]).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("utterance_failed"));
        when(provider.isConfigured()).thenReturn(false);
        send(aliceToken, new byte[100]).andExpect(status().isServiceUnavailable());
        mockMvc.perform(multipart("/api/jarvis/utterance")
                .file(new MockMultipartFile("audio", "a.wav", "audio/wav", new byte[10])).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
