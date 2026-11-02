package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;

/** F-235 / SF-235-01 — le cerveau rapide : répondre, déléguer, déléguer d'office, s'effacer. */
class JarvisFastBrainServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID alice = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private Workspace workspace;
    private JarvisUsageService usage;
    private WorkspaceRepository workspaces;
    private final List<AgentTurnRequest> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workspace = Workspace.builder().id(subject).userId(alice).name("Kestrel").build();
        usage = mock(JarvisUsageService.class);
        when(usage.monthFastBrainCostUsd(any())).thenReturn(BigDecimal.ZERO);
        workspaces = mock(WorkspaceRepository.class);
    }

    private JarvisFastBrainService service(AiAgentProvider provider, String budgetEur, Duration timeout) {
        JarvisFastBrainProperties props = new JarvisFastBrainProperties(true, null, null, null,
                budgetEur == null ? null : new BigDecimal(budgetEur), timeout);
        return new JarvisFastBrainService(provider, props, usage, workspaces,
                Clock.fixed(Instant.parse("2026-11-01T13:32:00Z"), ZoneOffset.UTC));
    }

    private AiAgentProvider answering(String text) {
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                calls.add(request);
                return new AgentTurn(text, List.of(), true, 100, 10);
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                listener.onTextDelta(text);
                return new AgentTurn(text, List.of(), true, 100, 10);
            }
        };
    }

    private AiAgentProvider delegating(String json) {
        return request -> {
            calls.add(request);
            try {
                return new AgentTurn("", List.of(new AgentToolCall("t1", "deleguer", JSON.readTree(json))), false,
                        100, 10);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private JarvisFastBrainService.Request ask(String text) {
        return new JarvisFastBrainService.Request(alice, workspace, 3, text, null);
    }

    @Test
    void answersWithItsOwnTextAndStreamsIt() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(answering("Le chantier 3 en est aux tests."), null, null)
                .converse(ask("Où en est le chantier 3 ?"), heard::append);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo("Le chantier 3 en est aux tests.");
        assertThat(heard.toString().strip()).isEqualTo("Le chantier 3 en est aux tests.");
        // Le coût est compté à la fin de l’appel, en tâche de fond : on lui laisse le temps.
        verify(usage, org.mockito.Mockito.timeout(2_000)).addFastBrain(eq(alice), any());
    }

    @Test
    void sendsOnlyTheDelegateToolWithReasoningOffAndACompactShape() {
        service(answering("Bonjour."), null, null).converse(ask("Bonjour"), null);

        AgentTurnRequest request = calls.get(0);
        assertThat(request.model()).isEqualTo("claude-haiku-5-5");
        assertThat(request.tools()).extracting(t -> t.name()).containsExactly("deleguer");
        assertThat(request.reasoning().disabled()).isTrue();
        assertThat(request.reasoning().effort()).isEqualTo("low");
        assertThat(request.shape().maxTokens()).isEqualTo(600);
        assertThat(request.shape().serverTools()).isFalse();
        assertThat(request.shape().strictTools()).isTrue();
        // Le volatil (heure, sujet, parole) est dans le message, jamais dans la consigne système.
        assertThat(request.system()).isEqualTo(JarvisFastBrainPrompt.system());
        assertThat(request.system()).doesNotContain("Kestrel").doesNotContain("2026");
        String message = ((fr.claudegateway.agent.AgentContentBlock.Text) request.messages().get(0).content().get(0))
                .text();
        assertThat(message).contains("Kestrel").contains("Chantier courant : 3").contains("Bonjour");
    }

    @Test
    void systemPromptIsIdenticalFromOneCallToTheNext() {
        JarvisFastBrainService service = service(answering("Oui."), null, null);
        service.converse(ask("Un"), null);
        service.converse(new JarvisFastBrainService.Request(alice, workspace, null, "Deux", null), null);

        assertThat(calls.get(0).system()).isEqualTo(calls.get(1).system());
        assertThat(calls.get(0).tools()).isEqualTo(calls.get(1).tools());
    }

    @Test
    void delegatesWithTheModelsReformulationOnTheCurrentSubject() {
        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "Lister les tickets bloqués d'Edenred", "label": "tickets bloqués d'Edenred",
                 "annonce": "Je regarde les tickets bloqués d'Edenred, Monsieur.", "sujet": "", "chantier": null}
                """), null, null).converse(ask("Regarde les tickets bloqués"), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(outcome.delegation().automatic()).isFalse();
        assertThat(outcome.delegation().subjectId()).isEqualTo(subject);
        assertThat(outcome.delegation().chantier()).isEqualTo(3);
        assertThat(outcome.delegation().label()).isEqualTo("tickets bloqués d'Edenred");
        assertThat(outcome.delegation().annonce()).startsWith("Je regarde les tickets bloqués");
    }

    @Test
    void anotherAccountsSubjectIsNeverTargeted() {
        UUID bobs = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(bobs, alice)).thenReturn(Optional.empty());

        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "x", "label": "y", "annonce": "Je regarde.", "sujet": "%s", "chantier": null}
                """.formatted(bobs)), null, null).converse(ask("Regarde chez Bob"), null);

        assertThat(outcome.delegation().subjectId()).isEqualTo(subject);
    }

    @Test
    void aNamedSubjectOfTheSameAccountIsTargeted() {
        UUID other = UUID.randomUUID();
        when(workspaces.findByIdAndUserId(other, alice))
                .thenReturn(Optional.of(Workspace.builder().id(other).userId(alice).name("AGENOR").build()));

        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "x", "label": "y", "annonce": "Sur AGENOR, je regarde.", "sujet": "%s", "chantier": null}
                """.formatted(other)), null, null).converse(ask("Sur AGENOR, regarde"), null);

        assertThat(outcome.delegation().subjectId()).isEqualTo(other);
        assertThat(outcome.delegation().chantier()).isNull();
    }

    @Test
    void aRefusalBecomesAnAutomaticDelegationOfTheOriginalWords() {
        AiAgentProvider refusing = request -> new AgentTurn("", List.of(), true, 10, 0, false, List.of(), 0, 0, 0,
                true, "cyber");

        JarvisFastBrainService.Outcome outcome = service(refusing, null, null).converse(ask("Fais ceci"), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(outcome.delegation().automatic()).isTrue();
        assertThat(outcome.delegation().reason()).isEqualTo("refusal");
        assertThat(outcome.delegation().demande()).isEqualTo("Fais ceci");
    }

    @Test
    void anErrorBecomesAnAutomaticDelegation() {
        AiAgentProvider failing = request -> {
            throw new IllegalStateException("panne");
        };

        JarvisFastBrainService.Outcome outcome = service(failing, null, null).converse(ask("Où en est Kestrel ?"),
                null);

        assertThat(outcome.delegation().automatic()).isTrue();
        assertThat(outcome.delegation().reason()).isEqualTo("error");
        // F-239 / SF-239-02 (D1) — l'annonce d'office n'est plus « Je vérifie, Monsieur. » : silence.
        assertThat(outcome.delegation().annonce()).isEmpty();
    }

    // ---------------------------------------------------------------- F-239 / SF-239-02 : salutation, invitations

    @Test
    void aGreetingIsNeverDelegatedTheUngroundedNewsIsDropped() {
        // 2026-11-02 08:11:52 : « Bonjour Jarvis. » ⇒ bilan non demandé, garde d'ancrage, délégation à Opus (23 s).
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(streaming("Bonjour Monsieur. ",
                "Deux fermetures d'attentes attendent votre clic, et LDIC-223 reste le prochain chantier."), null, null)
                .converse(ask("Bonjour Jarvis."), heard::append);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo("Bonjour Monsieur.");
        assertThat(outcome.guards().groundingTrips()).isEqualTo(1);
        assertThat(heard.toString()).doesNotContain("LDIC");
    }

    @Test
    void aGreetingTheModelDelegatesOrFailsGetsACourteousReplySaidNow() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "faire le point", "label": "point du jour", "annonce": "Je fais le point.", "sujet": "",
                 "chantier": null}
                """), null, null).converse(ask("Bonjour Jarvis"), heard::append);
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo("Bonjour, Monsieur.");
        assertThat(heard.toString().strip()).isEqualTo("Bonjour, Monsieur.");

        AiAgentProvider failing = request -> {
            throw new IllegalStateException("panne");
        };
        outcome = service(failing, null, null).converse(ask("Merci Jarvis"), null);
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo("Avec plaisir, Monsieur.");
    }

    @Test
    void aGreetingAtTheHardCapGetsTheFixedReplyNeverOpus() {
        when(usage.monthFastBrainCostUsd(any())).thenReturn(new BigDecimal("1000"));
        JarvisFastBrainService.Outcome outcome = service(answering("Bonjour."), "1", null)
                .converse(ask("Bonjour Jarvis"), null);
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(calls).isEmpty();
    }

    @Test
    void anInvitationToSpeakIsRemovedEvenAsAConclusion() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(streaming("Le chantier en est aux tests. ",
                "Je vous écoute pour la suite de la journée."), null, null)
                .converse(ask("Et le chantier ?"), heard::append);

        assertThat(outcome.spokenText()).isEqualTo("Le chantier en est aux tests.");
        assertThat(outcome.guards().fillersRemoved()).isEqualTo(1);
        assertThat(heard.toString()).doesNotContain("écoute");
    }

    @Test
    void withoutASubjectTheQuestionIsSaid() {
        StringBuilder heard = new StringBuilder();
        service(delegating("""
                {"demande": "x", "label": "tickets", "annonce": "Je regarde les tickets.", "sujet": "",
                 "chantier": null}
                """), null, null).converse(new JarvisFastBrainService.Request(alice, null, null,
                "Regarde les tickets", null), heard::append);
        assertThat(heard.toString().strip()).isEqualTo(JarvisFastBrainService.ASK_SUBJECT);
    }

    @Test
    void noFirstTokenWithinTheDelayBecomesAnAutomaticDelegation() {
        AiAgentProvider slow = request -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AgentTurn("Trop tard.", List.of(), true, 10, 2);
        };

        JarvisFastBrainService.Outcome outcome = service(slow, null, Duration.ofMillis(100)).converse(ask("Vite"),
                null);

        assertThat(outcome.delegation().automatic()).isTrue();
        assertThat(outcome.delegation().reason()).isEqualTo("timeout");
    }

    @Test
    void anEmptyAnswerBecomesAnAutomaticDelegation() {
        JarvisFastBrainService.Outcome outcome = service(answering("  "), null, null).converse(ask("Euh"), null);

        assertThat(outcome.delegation().reason()).isEqualTo("empty");
    }

    @Test
    void f239OverTheBudgetTheFastBrainCarriesOnNeverOpus() {
        // 25 € dépensés (0,92 €/$) pour un budget de 20 € : plafond doux — le cerveau rapide répond, signalé.
        when(usage.monthFastBrainCostUsd(alice)).thenReturn(new BigDecimal("25"));

        JarvisFastBrainService.Outcome outcome = service(answering("Kestrel avance bien."), "20", null)
                .converse(ask("Où en est Kestrel ?"), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.reason()).isEqualTo("budget_soft");
        assertThat(calls).hasSize(1);
    }

    @Test
    void f239AtTwiceTheBudgetAFixedReplyNoCallNeverOpus() {
        when(usage.monthFastBrainCostUsd(alice)).thenReturn(new BigDecimal("50"));
        StringBuilder heard = new StringBuilder();

        JarvisFastBrainService.Outcome outcome = service(answering("Oui."), "20", null)
                .converse(ask("Où en est Kestrel ?"), heard::append);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.reason()).isEqualTo("budget_hard");
        assertThat(heard.toString().strip()).isEqualTo(JarvisFastBrainService.BUDGET_HARD_REPLY);
        assertThat(calls).isEmpty();
    }

    @Test
    void f239TheAccountQuotaReachedIsSaidNeverEscalated() {
        JarvisFastBrainService service = service(answering("Oui."), null, null);
        QuotaService quota = mock(QuotaService.class);
        org.mockito.Mockito.doThrow(new QuotaExceededException("quota")).when(quota).assertWithinQuota(alice);
        service.setAccounting(null, null, null, quota);

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Où en est Kestrel ?"), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.reason()).isEqualTo("quota");
        assertThat(outcome.spokenText()).isEqualTo(JarvisFastBrainService.QUOTA_REPLY);
        assertThat(calls).isEmpty();
    }

    // ------------------------------------------------------------- SF-235-02 : mémoire, contexte, pré-chauffage

    @Test
    void replaysTheSessionWordsThenTheContextOnTheLastMessageOnly() {
        JarvisFastBrainService service = service(answering("Oui, Monsieur."), null, null);
        JarvisFastMemory memory = new JarvisFastMemory(Clock.systemUTC());
        JarvisFastContextBuilder builder = mock(JarvisFastContextBuilder.class);
        when(builder.build(any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn("CONTEXTE-1").thenReturn("CONTEXTE-2");
        when(builder.reference(any())).thenReturn("## Univers\nKestrel, Zahi");
        service.setContext(builder, memory, null);

        service.converse(ask("Première"), null);
        service.converse(ask("Deuxième"), null);

        AgentTurnRequest first = calls.get(0);
        AgentTurnRequest second = calls.get(1);
        // F-239 / SF-239-11 — [univers de référence] → [« Noté. »] → échanges → [volatil + parole].
        assertThat(second.messages()).hasSize(5);
        assertThat(text(second, 0)).startsWith("[Référence — univers]").contains("Kestrel, Zahi");
        assertThat(text(second, 1)).isEqualTo("Noté.");
        assertThat(text(second, 2)).isEqualTo("Première");
        assertThat(text(second, 3)).isEqualTo("Oui, Monsieur.");
        assertThat(text(second, 4)).contains("CONTEXTE-2").contains("Deuxième").doesNotContain("CONTEXTE-1")
                .doesNotContain("Kestrel, Zahi");
        // Le préfixe du second appel prolonge celui du premier, octet pour octet (relu en cache).
        assertThat(text(first, 0)).isEqualTo(text(second, 0));
        assertThat(text(first, 1)).isEqualTo(text(second, 1));
        // Le dernier message n'est pas écrit en cache (forme compacte).
        assertThat(second.shape().cacheLastMessage()).isFalse();
        // Deux contextes différents, une seule consigne système.
        assertThat(first.system()).isEqualTo(second.system());
    }

    @Test
    void f239TheDayStateRidesInTheLastMessageNeverInTheSystemPrompt() {
        JarvisFastBrainService service = service(answering("Bonjour, Monsieur. La journée commence bien ?"), null,
                null);
        service.setContext(null, new JarvisFastMemory(Clock.systemUTC()), null);

        service.converse(ask("Bonjour Jarvis."), null);
        service.converse(ask("Oui, merci."), null);

        String first = text(calls.get(0), calls.get(0).messages().size() - 1);
        String second = text(calls.get(1), calls.get(1).messages().size() - 1);
        assertThat(first).contains("Première salutation du jour : oui").contains("Question de politesse déjà posée : non");
        assertThat(second).contains("Première salutation du jour : non")
                .contains("Question de politesse déjà posée : oui");
        assertThat(calls.get(0).system()).isEqualTo(calls.get(1).system()).doesNotContain("## Conversation du jour");
    }

    @Test
    void f239MoreInvitationsAreRemoved() {
        assertThat(JarvisFastGuards.isListeningInvitation("Je reste prêt à dérouler le programme.")).isTrue();
        assertThat(JarvisFastGuards.isListeningInvitation("Que puis-je faire pour vous, Monsieur ?")).isTrue();
    }

    @Test
    void f239AtMostThreeSentencesNoMetaTalkNoDetailOffer() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(streaming("Kestrel avance. ", "Le contexte ne m'en dit pas plus. ",
                "Kestrel est en recette. ", "Kestrel attend une validation. ", "Kestrel reste ouvert. ",
                "Je peux détailler les étapes si vous le souhaitez."), null, null)
                .converse(ask("Fais-moi le point sur Kestrel."), heard::append);

        assertThat(outcome.spokenText())
                .isEqualTo("Kestrel avance. Kestrel est en recette. Kestrel attend une validation.");
        assertThat(heard.toString()).doesNotContain("contexte").doesNotContain("détailler").doesNotContain("ouvert");
    }

    @Test
    void warmsAtMostEveryFourMinutesWithZeroOutput() {
        JarvisFastBrainService service = service(answering(""), null, null);

        assertThat(service.warm(alice, workspace)).isTrue();
        assertThat(service.warm(alice, workspace)).isFalse();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).shape().maxTokens()).isZero();
        assertThat(calls.get(0).system()).isEqualTo(JarvisFastBrainPrompt.system());
    }

    @Test
    void aDelegationIsRegisteredAsRunning() {
        JarvisFastBrainService service = service(delegating("""
                {"demande": "x", "label": "tickets bloqués", "annonce": "Je regarde les tickets bloqués.",
                 "sujet": "", "chantier": null}
                """), null, null);
        JarvisDelegationRegistry registry = new JarvisDelegationRegistry(Clock.systemUTC(), null);
        service.setContext(null, null, registry);

        service.converse(ask("Regarde les tickets bloqués"), null);

        assertThat(registry.running(alice)).extracting(JarvisDelegationRegistry.Running::label)
                .containsExactly("tickets bloqués");
    }

    // ------------------------------------------------------------- SF-235-03 : garde-fous

    private AiAgentProvider streaming(String... deltas) {
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                return nextTurn(request, d -> { });
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                for (String d : deltas) {
                    listener.onTextDelta(d);
                }
                return new AgentTurn(String.join("", deltas), List.of(), true, 100, 10);
            }
        };
    }

    // ---------------------------------------------------------------- F-239 / SF-239-05 : « compris: »

    /** Un écouteur qui retient le dit et le compris. */
    private static final class Heard implements JarvisFastBrainService.Sink {
        final StringBuilder said = new StringBuilder();
        String understood;

        @Override
        public void text(String delta) {
            said.append(delta);
        }

        @Override
        public void understood(String text) {
            understood = text;
        }
    }

    @Test
    void theUnderstoodLineIsShownNeverSaidAndBecomesWhatWasSaid() {
        Heard heard = new Heard();
        JarvisFastBrainService service = service(streaming("compris: Où en est ", "Kestrel ?\n",
                "Kestrel avance bien."), null, null);
        JarvisFastMemory memory = new JarvisFastMemory(Clock.systemUTC());
        service.setContext(null, memory, null);

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Où en est Kestrelle ?"), heard);

        assertThat(heard.understood).isEqualTo("Où en est Kestrel ?");
        assertThat(heard.said.toString().strip()).isEqualTo("Kestrel avance bien.");
        assertThat(outcome.spokenText()).isEqualTo("Kestrel avance bien.");
        assertThat(outcome.understood()).isEqualTo("Où en est Kestrel ?");
        assertThat(memory.recall(alice).get(0).said()).isEqualTo("Où en est Kestrel ?");
    }

    @Test
    void anUnderstoodLineThatInventsAnActionIsDropped() {
        Heard heard = new Heard();
        JarvisFastBrainService.Outcome outcome = service(streaming("compris: Envoie le ticket à Kestrel.\n",
                "Kestrel avance bien."), null, null).converse(ask("Regarde le ticket de Kestrel."), heard);

        assertThat(heard.understood).isNull();
        assertThat(outcome.understood()).isNull();
        assertThat(heard.said.toString()).doesNotContain("compris").doesNotContain("Envoie");
        assertThat(outcome.spokenText()).isEqualTo("Kestrel avance bien.");
    }

    @Test
    void anAnswerWithoutTheLineIsUnchangedAndALineAloneIsAnEmptyAnswer() {
        Heard heard = new Heard();
        assertThat(service(streaming("Comment va Kestrel. Kestrel avance bien."), null, null).converse(ask("Kestrel ?"), heard)
                .spokenText()).isEqualTo("Comment va Kestrel. Kestrel avance bien.");

        JarvisFastBrainService.Outcome alone = service(streaming("compris: Où en est Kestrel ?"), null, null)
                .converse(ask("Où en est Kestrelle ?"), new Heard());
        assertThat(alone.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(alone.delegation().reason()).isEqualTo("empty");
        assertThat(alone.understood()).isEqualTo("Où en est Kestrel ?");
        assertThat(alone.delegation().demande()).isEqualTo("Où en est Kestrel ?");
    }

    @Test
    void anUngroundedNumberIsNeverSaidAndTheExchangeIsDelegated() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(streaming("Kestrel avance bien. ", "Il reste 42 tickets ",
                "ouverts."), null, null).converse(ask("Où en est Kestrel ?"), heard::append);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(outcome.delegation().reason()).isEqualTo("grounding");
        assertThat(outcome.delegation().demande()).isEqualTo("Où en est Kestrel ?");
        assertThat(outcome.guards().groundingTrips()).isEqualTo(1);
        assertThat(heard.toString()).contains("Kestrel avance bien.").doesNotContain("42");
    }

    @Test
    void anUngroundedTicketKeyOrNameIsNeverSaid() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(streaming("Le ticket KES-418 bloque."), null, null)
                .converse(ask("Qu'est-ce qui bloque ?"), heard::append);
        assertThat(outcome.delegation().reason()).isEqualTo("grounding");
        assertThat(heard.toString()).isEmpty();

        outcome = service(streaming("C'est chez Thales que ça coince."), null, null)
                .converse(ask("Qu'est-ce qui coince ?"), null);
        assertThat(outcome.delegation().reason()).isEqualTo("grounding");
    }

    @Test
    void aFactPresentInTheWordsIsSaid() {
        JarvisFastBrainService.Outcome outcome = service(streaming("Le ticket KES-418 est noté chez Thales."), null,
                null).converse(ask("Note le ticket KES-418 de Thales"), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
    }

    @Test
    void aFillerAloneIsRemovedButKeptInsideAFullSentence() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(streaming("Très bien. ", "Le chantier en est aux tests. ",
                "Je m'en occupe, Monsieur."), null, null).converse(ask("Et le chantier ?"), heard::append);

        assertThat(outcome.spokenText()).isEqualTo("Le chantier en est aux tests.");
        assertThat(outcome.guards().fillersRemoved()).isEqualTo(2);
        assertThat(heard.toString()).doesNotContain("Très bien");

        outcome = service(streaming("Très bien, le chantier en est aux tests."), null, null)
                .converse(ask("Et le chantier ?"), null);
        assertThat(outcome.spokenText()).isEqualTo("Très bien, le chantier en est aux tests.");
    }

    @Test
    void onlyFillersBecomesAnAutomaticDelegation() {
        JarvisFastBrainService.Outcome outcome = service(streaming("Une seconde."), null, null)
                .converse(ask("Et alors ?"), null);

        assertThat(outcome.delegation().reason()).isEqualTo("empty");
    }

    @Test
    void aSentenceFromTheLastAnswersIsNotRepeated() {
        JarvisFastBrainService service = service(streaming("Avec plaisir. ", "Le chantier en est aux tests."), null,
                null);
        service.setContext(null, new JarvisFastMemory(Clock.systemUTC()), null);

        service.converse(ask("Merci"), null);
        JarvisFastBrainService.Outcome second = service.converse(ask("Merci encore"), null);

        assertThat(second.spokenText()).doesNotContain("Avec plaisir");
        assertThat(second.guards().repeatsRemoved()).isEqualTo(2);
        assertThat(second.delegation().reason()).isEqualTo("empty");
    }

    @Test
    void verifyForcesOpusOnThePreviousQuestionWithoutCallingTheFastBrain() {
        JarvisFastBrainService service = service(streaming("Le chantier en est aux tests."), null, null);
        service.setContext(null, new JarvisFastMemory(Clock.systemUTC()), null);
        service.converse(ask("Où en est le chantier ?"), null);
        calls.clear();

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Vérifie, s'il te plaît"), null);

        assertThat(calls).isEmpty();
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(outcome.delegation().reason()).isEqualTo("forced");
        assertThat(outcome.delegation().demande()).startsWith("Où en est le chantier ?");
        assertThat(outcome.guards().forcedDelegation()).isTrue();
        assertThat(service.converse(ask("Demande à Opus"), null).delegation().reason()).isEqualTo("forced");
        assertThat(service.converse(ask("Non, c'est faux"), null).delegation().reason()).isEqualTo("forced");
    }

    @Test
    void textImitatingAToolCallIsNeverSaid() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(streaming("deleguer(", "{\"demande\": \"x\"})"), null, null)
                .converse(ask("Regarde"), heard::append);

        assertThat(outcome.delegation().reason()).isEqualTo("tool_text");
        assertThat(outcome.guards().toolText()).isTrue();
        assertThat(heard.toString()).isEmpty();
    }

    @Test
    void aHollowAnnounceIsReplacedByOneThatNamesTheObject() {
        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "x", "label": "tickets bloqués", "annonce": "Je m'en occupe.", "sujet": "",
                 "chantier": null}
                """), null, null).converse(ask("Regarde les tickets bloqués"), null);

        assertThat(outcome.delegation().annonce()).isEqualTo("Je regarde : tickets bloqués.");
    }

    @Test
    void monsieurAtMostOncePerAnswer() {
        JarvisFastBrainService.Outcome outcome = service(streaming("Bonsoir, Monsieur. ",
                "Le chantier en est aux tests, Monsieur."), null, null).converse(ask("Bonsoir"), null);

        assertThat(outcome.spokenText()).isEqualTo("Bonsoir, Monsieur. Le chantier en est aux tests.");
    }

    // ------------------------------------------------------------- SF-235-09 : personnalité

    @Test
    void theAccountsHumourPicksTheFrozenSystemVariantAndTroubleAddsTheSoberNote() {
        JarvisFastBrainService service = service(streaming("Entendu, je note."), null, null);
        fr.claudegateway.jarvis.preferences.JarvisPreferenceService prefs =
                mock(fr.claudegateway.jarvis.preferences.JarvisPreferenceService.class);
        when(prefs.humour(alice)).thenReturn(fr.claudegateway.jarvis.preferences.JarvisHumour.NONE);
        service.setPersonality(prefs, null);

        service.converse(ask("On a un incident en production"), null);
        service.converse(ask("Bonjour"), null);

        assertThat(calls.get(0).system())
                .isEqualTo(JarvisFastBrainPrompt.system(fr.claudegateway.jarvis.preferences.JarvisHumour.NONE))
                .isEqualTo(calls.get(1).system());
        assertThat(text(calls.get(0), 0)).endsWith(fr.claudegateway.jarvis.preferences.JarvisSobriety.NOTE);
        assertThat(text(calls.get(1), calls.get(1).messages().size() - 1))
                .doesNotContain(fr.claudegateway.jarvis.preferences.JarvisSobriety.NOTE);
    }

    @Test
    void aSentenceImitatingTheWorkIsRemoved() {
        JarvisFastBrainService.Outcome outcome = service(streaming("Comme dirait Stark, c'est parti. ",
                "Le chantier en est aux tests."), null, null).converse(ask("Et le chantier ?"), null);

        assertThat(outcome.spokenText()).isEqualTo("Le chantier en est aux tests.");
        assertThat(outcome.guards().imitationsRemoved()).isEqualTo(1);
    }

    // ------------------------------------------------------------- SF-235-04 : délégation dans le fil

    @Test
    void withoutASubjectJarvisAsksWhichOneInsteadOfChoosing() {
        JarvisFastBrainService.Outcome outcome = service(delegating("""
                {"demande": "x", "label": "tickets bloqués", "annonce": "Je regarde les tickets bloqués.",
                 "sujet": "", "chantier": null}
                """), null, null).converse(new JarvisFastBrainService.Request(alice, null, null,
                "Regarde les tickets bloqués", null), null);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo(JarvisFastBrainService.ASK_SUBJECT);
    }

    @Test
    void aDelegationOffersItsReformulationToTheTargetThread() {
        JarvisFastBrainService service = service(delegating("""
                {"demande": "Lister les tickets bloqués", "label": "tickets bloqués",
                 "annonce": "Je regarde les tickets bloqués.", "sujet": "", "chantier": null}
                """), null, null);
        JarvisDelegationRegistry registry = new JarvisDelegationRegistry(Clock.systemUTC(), null);
        service.setContext(null, null, registry);

        service.converse(ask("Regarde les tickets bloqués"), null);

        assertThat(registry.take(alice, subject, 3).demande()).isEqualTo("Lister les tickets bloqués");
    }

    @Test
    void aLiveThreadTurnsTheDelegationIntoAnAddition() {
        JarvisFastBrainService service = service(delegating("""
                {"demande": "Et aussi les tickets en revue", "label": "tickets en revue",
                 "annonce": "Je regarde les tickets en revue.", "sujet": "", "chantier": null}
                """), null, null);
        JarvisDelegationRegistry registry = mock(JarvisDelegationRegistry.class);
        when(registry.live(alice, subject, 3)).thenReturn(true);
        service.setContext(null, null, registry);

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Et les tickets en revue"), null);

        assertThat(outcome.delegation().annonce()).isEqualTo("J'ajoute ça à la recherche en cours : tickets en revue.");
    }

    @Test
    void aFastAnswerIsWrittenInTheThreadButADelegationIsNot() {
        JarvisFastExchangeWriter writer = mock(JarvisFastExchangeWriter.class);
        JarvisFastBrainService service = service(streaming("Le chantier en est aux tests."), null, null);
        service.setExchanges(writer);
        service.converse(ask("Et le chantier ?"), null);
        verify(writer).write(alice, subject, 3, "Et le chantier ?", "Le chantier en est aux tests.");

        JarvisFastExchangeWriter writer2 = mock(JarvisFastExchangeWriter.class);
        JarvisFastBrainService delegating = service(delegating("""
                {"demande": "x", "label": "y", "annonce": "Je regarde y.", "sujet": "", "chantier": null}
                """), null, null);
        delegating.setExchanges(writer2);
        delegating.converse(ask("Regarde y"), null);
        org.mockito.Mockito.verifyNoInteractions(writer2);
    }

    private static String text(AgentTurnRequest request, int index) {
        return ((fr.claudegateway.agent.AgentContentBlock.Text) request.messages().get(index).content().get(0)).text();
    }
}
