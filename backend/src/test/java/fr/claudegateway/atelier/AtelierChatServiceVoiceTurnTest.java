package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-231 / SF-231-06 — <b>un tour né à la voix porte sa marque</b> : la demande et la réponse sont rangées avec
 * {@code via = JARVIS} ; la marque ne vaut qu'une fois, et jamais pour un autre fil.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceVoiceTurnTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    StubAiAgentProvider agentProvider;
    AtelierChatService service;
    final UUID userId = UUID.randomUUID();
    final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
    }

    List<AtelierMessage> saved() {
        ArgumentCaptor<AtelierMessage> captor = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void laDemandeEtLaReponseDUnTourNeALaVoixSontMarquees() {
        service.markVoiceTurn(userId, workspaceId, null);
        agentProvider.enqueueFinal("🎙 C'est fait.");

        service.chat(userId, workspaceId, "valide l'étape trois");

        assertThat(saved()).filteredOn(m -> "USER".equals(m.getRole()))
                .extracting(AtelierMessage::getVia).containsExactly(AtelierMessage.VIA_JARVIS);
        assertThat(saved()).filteredOn(m -> "ASSISTANT".equals(m.getRole()))
                .extracting(AtelierMessage::getVia).containsExactly(AtelierMessage.VIA_JARVIS);
    }

    @Test
    void F239_SF_239_12_unTourNeALaVoixCompteDansLeCompteurDeJarvisUnTourTapeNon() {
        fr.claudegateway.jarvis.JarvisUsageService usage = Mockito.mock(fr.claudegateway.jarvis.JarvisUsageService.class);
        service.setJarvisUsage(usage);
        service.chat(userId, workspaceId, "bonjour");
        Mockito.verifyNoInteractions(usage);

        service.markVoiceTurn(userId, workspaceId, null);
        service.chat(userId, workspaceId, "valide l'étape trois");
        verify(usage).addOpus(org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void SF_231_03_laConsigneOraleVaDansLeMessageJamaisDansLeSysteme() {
        service.markVoiceTurn(userId, workspaceId, null);
        agentProvider.enqueueFinal("🎙 C'est fait.");

        service.chat(userId, workspaceId, "valide l'étape trois");

        assertThat(sentToModel()).contains("[Mode Jarvis").contains("email_me")
                // SF-231-04 : les actions qui engagent sont reformulées puis confirmées.
                .contains("Vous confirmez ?")
                // F-233 / SF-233-03 : annonce avant outils (D-PO-7), « Monsieur » et vouvoiement (D-PO-8).
                .contains("Si tu dois utiliser des outils, commence par une ligne « 🎙 »")
                .contains("appelle-le « Monsieur »");
        assertThat(agentProvider.lastRequest.system()).doesNotContain("Mode Jarvis").doesNotContain("Monsieur");
        // Le fil garde la parole de l'utilisateur, pas la consigne.
        assertThat(saved()).filteredOn(m -> "USER".equals(m.getRole()))
                .extracting(AtelierMessage::getContent).containsExactly("valide l'étape trois");
    }

    @Test
    void SF_235_09_laPersonnaliteEtLeTonSobreVontDansLeMessageLeSystemeResteIdentique() {
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");
        String typedSystem = agentProvider.lastRequest.system();

        fr.claudegateway.jarvis.preferences.JarvisPreferenceService prefs =
                Mockito.mock(fr.claudegateway.jarvis.preferences.JarvisPreferenceService.class);
        when(prefs.humour(userId)).thenReturn(fr.claudegateway.jarvis.preferences.JarvisHumour.LIGHT);
        service.setJarvisPersonality(prefs,
                new fr.claudegateway.jarvis.preferences.JarvisSobriety(java.time.Clock.systemUTC()));
        service.markVoiceTurn(userId, workspaceId, null);
        agentProvider.enqueueFinal("🎙 Je regarde l'incident, Monsieur.");
        service.chat(userId, workspaceId, "on a un incident en production");

        assertThat(sentToModel()).contains(fr.claudegateway.jarvis.JarvisOralNote.PERSONALITY_LIGHT)
                .contains(fr.claudegateway.jarvis.preferences.JarvisSobriety.NOTE);
        assertThat(agentProvider.lastRequest.system()).isEqualTo(typedSystem).doesNotContain("majordome");
    }

    @Test
    void SF_234_13_montrerPasseParLeMessageEtLePrefixeResteIdentiqueAuTerminal() {
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");
        String typedSystem = agentProvider.lastRequest.system();
        List<String> typedTools = agentProvider.lastRequest.tools().stream().map(t -> t.name()).toList();

        service.markVoiceTurn(userId, workspaceId, null);
        agentProvider.enqueueFinal("🎙 Voici vos clients.\n🖥 montrer: clients");
        service.chat(userId, workspaceId, "montre-moi mes clients");

        assertThat(sentToModel()).contains("🖥 montrer: <filtre>").contains("chantiers-bloques")
                .contains("etapes-en-cours").contains("a-envoyer")
                // F-237 / SF-237-09 — les formes « pièces » de la ligne, dans le message (préfixe intact).
                .contains("🖥 montrer: preuves chantier N").contains("🖥 montrer: pieces");
        // D18 : aucun outil réservé à Jarvis, consigne système inchangée ⇒ préfixe identique au tour tapé.
        assertThat(agentProvider.lastRequest.system()).isEqualTo(typedSystem).doesNotContain("montrer");
        assertThat(agentProvider.lastRequest.tools().stream().map(t -> t.name()).toList()).isEqualTo(typedTools);
    }

    @Test
    void SF_231_03_unTourTapeNeRecoitPasLaConsigneOrale() {
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(sentToModel()).doesNotContain("Mode Jarvis");
    }

    // ------------------------------------------------------------- F-235 / SF-235-04 : la délégation dans le fil

    private AtelierMessage row(String role, String content, String brain) {
        AtelierMessage m = AtelierMessage.builder().workspaceId(workspaceId).userId(userId).role(role)
                .content(content).brain(brain).via(brain == null ? null : AtelierMessage.VIA_JARVIS).build();
        m.setCreatedAt(java.time.OffsetDateTime.now());
        return m;
    }

    @Test
    void SF_235_04_lesEchangesRapidesNeSontJamaisRejouesEtArriventEnNoteAuTourSuivant() {
        List<AtelierMessage> past = List.of(
                row("USER", "bonjour", null), row("ASSISTANT", "Bonjour !", null),
                row("USER", "où en est le chantier 3 ?", AtelierMessage.BRAIN_FAST),
                row("ASSISTANT", "Le chantier 3 en est aux tests.", AtelierMessage.BRAIN_FAST));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId)).thenReturn(past);
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "et le chantier 4 ?");

        List<fr.claudegateway.agent.AgentMessage> sent = agentProvider.lastRequest.messages();
        String replayed = sent.subList(0, sent.size() - 1).stream().map(Object::toString)
                .reduce("", String::concat);
        assertThat(replayed).contains("bonjour").doesNotContain("où en est le chantier 3");
        assertThat(sent.get(sent.size() - 1).toString())
                .contains("[Échanges avec Jarvis depuis ton dernier tour — réponses rapides, non vérifiées")
                .contains("Jarvis : Le chantier 3 en est aux tests.")
                .contains("Utilisateur : où en est le chantier 3 ?");
        // Le plus récent d'abord.
        String last = sent.get(sent.size() - 1).toString();
        assertThat(last.indexOf("Jarvis : Le chantier 3")).isLessThan(last.indexOf("Utilisateur : où en est"));
    }

    @Test
    void SF_235_04_sansEchangeRapideLaConsigneEstInchangee() {
        assertThat(service.withFastExchanges(userId, workspaceWith(), "c")).isEqualTo("c");
    }

    private Workspace workspaceWith() {
        Workspace w = new Workspace();
        w.setId(workspaceId);
        w.setUserId(userId);
        return w;
    }

    @Test
    void SF_235_04_uneDelegationJointSaDemandeReformuleeAuMessageEtMarqueLeTour() {
        fr.claudegateway.jarvis.fastbrain.JarvisDelegationRegistry registry =
                new fr.claudegateway.jarvis.fastbrain.JarvisDelegationRegistry(java.time.Clock.systemUTC(), null);
        service.setJarvisDelegations(registry);
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");
        String typedSystem = agentProvider.lastRequest.system();

        registry.offer(userId, workspaceId, null, UUID.randomUUID(),
                "Lister les tickets Jira bloqués du client Edenred", "tickets bloqués d'Edenred");
        service.markVoiceTurn(userId, workspaceId, null);
        agentProvider.enqueueFinal("🎙 Deux tickets bloqués.");
        service.chat(userId, workspaceId, "regarde les tickets bloqués d'Edenred");

        assertThat(sentToModel())
                .contains("[Jarvis — demande reformulée : « Lister les tickets Jira bloqués du client Edenred »]");
        assertThat(agentProvider.lastRequest.system()).isEqualTo(typedSystem);
        assertThat(saved()).filteredOn(m -> "USER".equals(m.getRole()) && m.isViaJarvis())
                .extracting(AtelierMessage::getContent, AtelierMessage::getBrain)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("regarde les tickets bloqués d'Edenred",
                        AtelierMessage.BRAIN_DELEGATED));
        // Prise une seule fois.
        assertThat(registry.take(userId, workspaceId, null)).isNull();
    }

    @Test
    void SF_235_04_laReformulationDUnAutreCompteOuDUnAutreFilNeSePrendPas() {
        fr.claudegateway.jarvis.fastbrain.JarvisDelegationRegistry registry =
                new fr.claudegateway.jarvis.fastbrain.JarvisDelegationRegistry(java.time.Clock.systemUTC(), null);
        registry.offer(userId, workspaceId, 3, UUID.randomUUID(), "x", "y");
        assertThat(registry.take(UUID.randomUUID(), workspaceId, 3)).isNull();
        assertThat(registry.take(userId, workspaceId, null)).isNull();
        assertThat(registry.take(userId, workspaceId, 3)).isNotNull();
    }

    private String sentToModel() {
        return agentProvider.lastRequest.messages().stream().map(Object::toString)
                .reduce("", String::concat);
    }

    @Test
    void laMarqueNeVautQuUneFois() {
        service.markVoiceTurn(userId, workspaceId, null);

        assertThat(service.consumeVoiceTurn(userId, workspaceId, null)).isTrue();
        assertThat(service.consumeVoiceTurn(userId, workspaceId, null)).isFalse();
    }

    @Test
    void laMarqueDUnFilNeDeteintPasSurUnAutre() {
        service.markVoiceTurn(userId, workspaceId, 7);

        assertThat(service.consumeVoiceTurn(userId, workspaceId, null)).isFalse();
        assertThat(service.consumeVoiceTurn(userId, workspaceId, 7)).isTrue();
    }

    @Test
    void unTourTapeNePorteAucuneMarque() {
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(saved()).extracting(AtelierMessage::getVia).containsOnlyNulls();
    }

    @Test
    void SF_233_09_laPartEntendueDUneReponseCoupeeVaDansLeMessageJamaisDansLeSysteme() {
        service.markVoiceTurn(userId, workspaceId, null, "La migration est à l'étape");
        agentProvider.enqueueFinal("🎙 Les tests passent.");

        service.chat(userId, workspaceId, "et les tests ?");

        assertThat(sentToModel()).contains("[Jarvis — ta réponse orale a été coupée après : « La migration est à "
                + "l'étape ». Le reste n'a pas été entendu.]");
        assertThat(agentProvider.lastRequest.system()).doesNotContain("coupée");
        assertThat(saved()).filteredOn(m -> "USER".equals(m.getRole()))
                .extracting(AtelierMessage::getContent).containsExactly("et les tests ?");
    }

    @Test
    void SF_233_09_sansCoupureLaConsigneEstInchangee() {
        assertThat(fr.claudegateway.jarvis.JarvisOralNote.appendCut("c", null)).isEqualTo("c");
        assertThat(fr.claudegateway.jarvis.JarvisOralNote.appendCut("c", "  ")).isEqualTo("c");
        assertThat(fr.claudegateway.jarvis.JarvisOralNote.appendCut("c", "a « b »")).contains("« a \" b \" »");
    }
}
