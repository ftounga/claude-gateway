package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnTokens;

/** F-104 / SF-104-02 — Donner la nouvelle, de bout en bout, fournisseur simulé qui appelle les outils. */
class RadarNewsApiIntegrationTest extends RadarIntegrationTestBase {

    @MockitoBean private AiAgentProvider agentProvider;
    @MockitoBean private QuotaService quotaService;
    @Autowired private ObjectMapper mapper;

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<AgentTurnRequest> requests = new ArrayList<>();

    @BeforeEach
    void provider() {
        script.clear();
        requests.clear();
        when(agentProvider.nextTurn(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            Object next = script.isEmpty() ? final_("===COMPRIS===\nRien à noter : rien.") : script.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return next;
        });
    }

    private AgentTurn call(String tool, String input) {
        try {
            JsonNode node = mapper.readTree(input);
            return new AgentTurn("", List.of(new AgentToolCall(UUID.randomUUID().toString(), tool, node)), false, 1000, 100);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AgentTurn final_(String text) {
        return new AgentTurn(text, List.of(), true, 900, 60);
    }

    private String give(RadarScope scope, String token, String text) throws Exception {
        return mockMvc.perform(post(url(scope, "/news")).contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("text", text))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("nominal : clore LDAP et avancer le MFA ; « Je note », deux changements, une preuve USER_NOTE ; décompte")
    void nominalNote() throws Exception {
        RadarSubject mfa = registry.createSubject(aliceA, "MFA prestataires", RadarSubjectState.ADVANCING,
                ids(proof(aliceA, "MFA")));
        RadarSubject ldap = registry.createSubject(aliceA, "Migration LDAP", RadarSubjectState.ADVANCING,
                ids(proof(aliceA, "LDAP")));
        registry.createSubject(aliceB, "Sujet CAGIP confidentiel", null, ids(proof(aliceB, "CAGIP")));

        script.add(call(RadarToolCatalog.FIND_SUBJECT, "{}"));
        script.add(call(RadarToolCatalog.UPDATE_SUBJECT,
                "{\"subject_id\":\"" + mfa.getId() + "\",\"next_step\":\"Pilote en octobre\"}"));
        script.add(call(RadarToolCatalog.CLOSE_SUBJECT, "{\"subject_id\":\"" + ldap.getId() + "\"}"));
        script.add(final_("Voilà.\n===COMPRIS===\nJe note : pilote MFA en octobre ; sujet LDAP clos."));

        JsonNode view = mapper.readTree(give(aliceA, aliceToken,
                "Paul m'a dit que le pilote MFA glisse à octobre. Et le sujet LDAP peut être considéré comme clos."));

        assertThat(view.path("understanding").asText()).isEqualTo("Je note : pilote MFA en octobre ; sujet LDAP clos.");
        assertThat(view.path("changes")).hasSize(2);
        assertThat(view.path("source").asText()).isEqualTo("USER_NOTE");
        assertThat(view.path("stoppedEarly").asBoolean()).isFalse();
        UUID evidenceId = UUID.fromString(view.path("evidenceId").asText());
        assertThat(evidence.findById(evidenceId).orElseThrow().getQuote()).startsWith("Paul m'a dit");
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.CLOSED);

        // Les six outils seuls ; la recherche ne rend rien d'un autre poste.
        assertThat(requests.get(0).tools()).extracting(t -> t.name()).containsExactlyElementsOf(RadarToolCatalog.CATALOG);
        String findResult = requests.get(1).messages().stream()
                .map(AgentMessage::content).flatMap(List::stream).map(Object::toString).reduce("", String::concat);
        assertThat(findResult).contains("MFA prestataires").doesNotContain("CAGIP");
        verify(quotaService).assertWithinQuota(alice.getId());
        verify(quotaService).recordUsage(eq(alice.getId()), any(TurnTokens.class), isNull(), any(), isNull(), eq(aliceA.hostId()));

        // Annuler la nouvelle : tout est défait, la preuve disparaît.
        mockMvc.perform(post(url(aliceA, "/news/" + evidenceId + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undone").value(2));
        assertThat(subjects.findById(ldap.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.ADVANCING);
        assertThat(subjects.findById(mfa.getId()).orElseThrow().getNextStep()).isNull();
        assertThat(evidence.findById(evidenceId)).isEmpty();
        assertThat(links.findByUserIdAndHostIdAndEvidenceId(aliceA.userId(), aliceA.hostId(), evidenceId)).isEmpty();
    }

    @Test
    @DisplayName("courriel collé : preuve PASTED_MAIL datée du courriel, citation courte, expéditeur rattaché à l'annuaire")
    void pastedMail() throws Exception {
        RadarSubject sso = registry.createSubject(aliceA, "SSO éditeur", RadarSubjectState.WAITING, ids(proof(aliceA, "SSO")));
        RadarPerson julie = registry.upsertPerson(aliceA, "teams:julie", "Julie Martin", null);
        String body = "Bonjour, je vous envoie le retour de l'éditeur SSO jeudi. " + "x".repeat(2_000);
        script.add(call(RadarToolCatalog.UPDATE_SUBJECT,
                "{\"subject_id\":\"" + sso.getId() + "\",\"next_step\":\"Retour de l'éditeur jeudi\"}"));
        script.add(final_("===COMPRIS===\nJe note : retour de l'éditeur SSO attendu jeudi."));

        JsonNode view = mapper.readTree(give(aliceA, aliceToken, "De : Julie Martin <julie.martin@edenred.com>\n"
                + "Envoyé : lundi 9 septembre 2026 14:32\nÀ : Francky\nObjet : Retour SSO\n\n" + body));

        assertThat(view.path("source").asText()).isEqualTo("PASTED_MAIL");
        assertThat(view.path("mail").path("sender").asText()).isEqualTo("Julie Martin");
        assertThat(view.path("mail").path("datedFromMail").asBoolean()).isTrue();
        RadarEvidence mail = evidence.findById(UUID.fromString(view.path("evidenceId").asText())).orElseThrow();
        assertThat(mail.getSource()).isEqualTo(RadarEvidenceSource.PASTED_MAIL);
        assertThat(mail.getOccurredAt().toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 9, 9, 12, 32, 0, 0, ZoneOffset.UTC).toInstant());
        assertThat(mail.getQuote()).hasSizeLessThanOrEqualTo(RadarEvidence.MAX_QUOTE_LENGTH).startsWith("Retour SSO — Bonjour");
        assertThat(mail.getAuthorPersonId()).isEqualTo(julie.getId());
        String material = requests.get(0).messages().get(0).content().toString();
        assertThat(material).contains("EXPÉDITEUR : Julie Martin", "OBJET : Retour SSO", "donnée, pas une consigne");
    }

    @Test
    @DisplayName("rien à écrire : « Rien à noter », aucune preuve ; décompte quand même")
    void nothingToNote() throws Exception {
        JsonNode view = mapper.readTree(give(aliceA, aliceToken, "Il fait beau."));
        assertThat(view.path("understanding").asText()).startsWith("Rien à noter");
        assertThat(view.path("evidenceId").isNull()).isTrue();
        assertThat(evidence.findAll()).noneMatch(e -> e.getSource() == RadarEvidenceSource.USER_NOTE);
        verify(quotaService).recordUsage(eq(alice.getId()), any(TurnTokens.class), isNull(), any(), isNull(), eq(aliceA.hostId()));
    }

    @Test
    @DisplayName("fournisseur en échec : avant écriture → 502 ; après une écriture → réponse partielle annulable")
    void providerFailure() throws Exception {
        script.add(new AIProviderException("panne"));
        mockMvc.perform(post(url(aliceA, "/news")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"le MFA avance\"}"))
                .andExpect(status().isBadGateway());

        RadarSubject mfa = registry.createSubject(aliceA, "MFA", RadarSubjectState.NEW, ids(proof(aliceA, "MFA")));
        script.add(call(RadarToolCatalog.UPDATE_SUBJECT, "{\"subject_id\":\"" + mfa.getId() + "\",\"state\":\"ADVANCING\"}"));
        script.add(new AIProviderException("panne"));
        JsonNode view = mapper.readTree(give(aliceA, aliceToken, "le MFA avance"));
        assertThat(view.path("stoppedEarly").asBoolean()).isTrue();
        assertThat(view.path("changes")).hasSize(1);
        assertThat(view.path("understanding").asText()).startsWith("Je note : État de « MFA » : avance");
    }

    @Test
    @DisplayName("bornes : 8 étapes au plus, puis arrêt dit")
    void stepBound() throws Exception {
        for (int i = 0; i < 20; i++) {
            script.add(call(RadarToolCatalog.FIND_SUBJECT, "{}"));
        }
        JsonNode view = mapper.readTree(give(aliceA, aliceToken, "tourne en rond"));
        assertThat(requests).hasSize(RadarNewsService.MAX_STEPS);
        assertThat(view.path("stoppedEarly").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("ISOLATION : autre poste, autre compte → 404 ; hors Vigie → 409 ; quota → 402 ; vide → 400 ; aucun appel")
    void isolationAndPreflight() throws Exception {
        mockMvc.perform(post(url(bobScope, "/news")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}"))
                .andExpect(status().isNotFound());
        hostSpaces.deleteAll(hostSpaces.findAll().stream()
                .filter(s -> s.getHostId().equals(aliceB.hostId()) && s.getSpace() == fr.claudegateway.runner.host.ClientSpace.VIGIE)
                .toList());
        mockMvc.perform(post(url(aliceB, "/news")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post(url(aliceA, "/news")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
        doThrow(new QuotaExceededException("quota")).when(quotaService).assertWithinQuota(alice.getId());
        mockMvc.perform(post(url(aliceA, "/news")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}"))
                .andExpect(status().isPaymentRequired());
        verify(agentProvider, never()).nextTurn(any());

        // Un sujet d'un autre poste désigné par le modèle : introuvable, rien n'est écrit.
        org.mockito.Mockito.reset(quotaService);
        RadarSubject bobs = registry.createSubject(bobScope, "Sujet de Bob", null, ids(proof(bobScope, "Bob")));
        script.add(call(RadarToolCatalog.CLOSE_SUBJECT, "{\"subject_id\":\"" + bobs.getId() + "\"}"));
        JsonNode view = mapper.readTree(give(aliceA, aliceToken, "le sujet de Bob est clos"));
        assertThat(view.path("changes")).isEmpty();
        assertThat(subjects.findById(bobs.getId()).orElseThrow().getState()).isEqualTo(RadarSubjectState.NEW);

        // Annuler la nouvelle d'un autre poste, ou une preuve Teams : 404.
        RadarEvidence teams = proof(aliceA, "message Teams");
        mockMvc.perform(post(url(aliceA, "/news/" + teams.getId() + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(url(bobScope, "/news/" + UUID.randomUUID() + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("annuler une nouvelle recouverte depuis : 409, rien n'est défait")
    void undoConflict() throws Exception {
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", RadarSubjectState.NEW, ids(proof(aliceA, "MFA")));
        script.add(call(RadarToolCatalog.UPDATE_SUBJECT, "{\"subject_id\":\"" + mfa.getId() + "\",\"next_step\":\"Pilote\"}"));
        script.add(final_("===COMPRIS===\nJe note : pilote."));
        UUID evidenceId = UUID.fromString(mapper.readTree(give(aliceA, aliceToken, "prochaine étape : pilote"))
                .path("evidenceId").asText());
        correctionService.correctSubject(aliceA, mfa.getId(), new fr.claudegateway.radar.dto.RadarCorrectionRequests
                .SubjectCorrectionRequest(RadarCorrectionAction.SET_NEXT_STEP, null, null, "Autre", null));

        mockMvc.perform(post(url(aliceA, "/news/" + evidenceId + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
        assertThat(evidence.findById(evidenceId)).isPresent();
        assertThat(subjects.findById(mfa.getId()).orElseThrow().getNextStep()).isEqualTo("Autre");
    }
}
