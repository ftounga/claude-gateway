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
        assertThat(request.tools()).extracting(t -> t.name()).containsExactly("deleguer", "pieces", "afficher");
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
    void f240_05AClosingPromptIsRemovedAfterASentenceButNeverEmptiesTheAnswer() {
        JarvisFastBrainService.Outcome trimmed = service(streaming("Je n'en doute pas, Monsieur. ",
                "Par quoi souhaitez-vous commencer ?"), null, null).converse(ask("Journée chargée aujourd'hui."), null);
        assertThat(trimmed.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(trimmed.spokenText()).isEqualTo("Je n'en doute pas, Monsieur.");

        JarvisFastBrainService.Outcome alone = service(streaming("Dites-moi par où vous souhaitez commencer."), null,
                null).converse(ask("C'est noté."), null);
        assertThat(alone.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(alone.spokenText()).isEqualTo("Dites-moi par où vous souhaitez commencer.");
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
    void f240_05TheForcedAnnounceSaysWhatIsVerifiedNeverAHollowJeVerifie() {
        String opus = JarvisFastGuards.lastOpusAnswer("Univers : chantier 1, ORN-99\n"
                + "## Derniers échanges du fil du sujet courant (état à 09:15)\n"
                + "- 09:10 Monsieur : Regarde les tickets bloqués d'Orion-Run.\n"
                + "- 09:12 Opus : Deux tickets sont bloqués : ORN-41 export PDF, ORN-44 droits, ORN-41 encore.");
        assertThat(JarvisFastBrainService.forcedAnnounce(null, opus, null))
                .isEqualTo("Je regarde de nouveau où en sont ORN-41 et ORN-44, Monsieur.");
        assertThat(JarvisFastBrainService.forcedAnnounce("LDIC-223 est en revue.", opus, null))
                .isEqualTo("Je regarde de nouveau où en est LDIC-223, Monsieur.");
        assertThat(JarvisFastBrainService.forcedAnnounce("AB-1, BC-2, CD-3 et DE-4 attendent.", null, null))
                .isEqualTo("Je regarde de nouveau où en sont AB-1, BC-2 et CD-3, Monsieur.");
        assertThat(JarvisFastBrainService.forcedAnnounce("Le chantier 3 en est aux tests.", null, null))
                .isEqualTo("Je regarde de nouveau où en est le chantier 3, Monsieur.");
        assertThat(JarvisFastBrainService.forcedAnnounce("Tout va bien.", null, "Orion-Run"))
                .isEqualTo("Je vérifie de nouveau ce point sur Orion-Run, Monsieur.");
        assertThat(JarvisFastBrainService.forcedAnnounce(null, null, null))
                .isEqualTo(JarvisFastBrainService.FORCED_ANNOUNCE).isNotEqualTo("Je vérifie.");
    }

    @Test
    void f240_05VerifyAfterAnOpusAnswerAnnouncesItsTicketsWithoutCallingTheFastBrain() {
        JarvisFastBrainService service = service(streaming("Oui."), null, null);
        String context = "Maintenant : lundi\n## Derniers échanges du fil du sujet courant (état à 09:15)\n"
                + "- 09:10 Monsieur : Regarde les tickets bloqués d'Orion-Run.\n"
                + "- 09:12 Opus : Deux tickets sont bloqués sur Orion-Run : ORN-41 et ORN-44.";

        JarvisFastBrainService.Outcome outcome = service.bench(ask("Vérifie."), "claude-sonnet-5-5", context);

        assertThat(calls).isEmpty();
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(outcome.delegation().reason()).isEqualTo("forced");
        assertThat(outcome.delegation().annonce())
                .isEqualTo("Je regarde de nouveau où en sont ORN-41 et ORN-44, Monsieur.");
    }

    @Test
    void f240_05TheV10PromptForbidsNamingOpusAndSaysWhatToDoOnEtEnsuiteWithoutAnswer() {
        assertThat(JarvisFastBrainPrompt.CORE).contains("Ne nomme jamais Opus")
                .contains("enchaîne directement sur le prochain point qui attend")
                .contains("compris: Et ensuite ?")
                .doesNotContain("réponds avec ce que tu as, sans déléguer");
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

    // ------------------------------------------------------- F-237 / SF-237-08 : pieces + afficher

    /** Un fournisseur scripté : un tour par appel, dans l'ordre. */
    private AiAgentProvider scripted(AgentTurn... turns) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                return nextTurn(request, d -> { });
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                calls.add(request);
                AgentTurn turn = turns[Math.min(n.getAndIncrement(), turns.length - 1)];
                if (turn.text() != null && !turn.text().isEmpty()) {
                    listener.onTextDelta(turn.text());
                }
                return turn;
            }
        };
    }

    private static AgentToolCall tool(String id, String name, String json) {
        try {
            return new AgentToolCall(id, name, JSON.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SHOW_PROOFS = """
            {"action":"galerie","pieces":[],"portee":{"sujet":"","chantier":3,"categorie":"preuves","type":"",
             "serie":"","depuis":""},"index":null,"temps":null,"chapitre":"","page":null,"zoom":"","cible":"",
             "raison":"appui"}""";

    /** Un écouteur qui retient le dit et les ordres d'écran. */
    private static final class Screen implements JarvisFastBrainService.Sink {
        final StringBuilder said = new StringBuilder();
        final List<JarvisShowCommand> shows = new ArrayList<>();

        @Override
        public void text(String delta) {
            said.append(delta);
        }

        @Override
        public void show(JarvisShowCommand command) {
            shows.add(command);
        }
    }

    @Test
    void threeFixedToolsIdenticalByteForByteAcrossCallsAndWarmUp() throws Exception {
        JarvisFastBrainService service = service(answering("Oui."), null, null);
        service.warm(alice, workspace);
        service.converse(ask("Un"), null);
        service.converse(ask("Deux"), null);

        assertThat(calls).hasSize(3);
        String first = JSON.writeValueAsString(calls.get(0).tools());
        assertThat(JSON.writeValueAsString(calls.get(1).tools())).isEqualTo(first);
        assertThat(JSON.writeValueAsString(calls.get(2).tools())).isEqualTo(first);
        assertThat(calls.get(0).system()).isEqualTo(calls.get(2).system());
        assertThat(JarvisFastBrainPrompt.VERSION).isEqualTo("10");
        assertThat(first).doesNotContain("Kestrel").doesNotContain(subject.toString());
    }

    @Test
    void showIsRelayedWithTheSentenceInOneCall() {
        Screen screen = new Screen();
        JarvisFastBrainService.Outcome outcome = service(scripted(new AgentTurn("Oui, voici les preuves du chantier 3.",
                List.of(tool("s1", "afficher", SHOW_PROOFS)), true, 100, 10)), null, null)
                .converse(ask("Tu es sûr que le chantier 3 est terminé ?"), screen);

        assertThat(calls).hasSize(1);
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(screen.shows).hasSize(1);
        JarvisShowCommand cmd = screen.shows.get(0);
        assertThat(cmd.action()).isEqualTo("galerie");
        assertThat(cmd.reason()).isEqualTo("appui");
        assertThat(cmd.scope().chantier()).isEqualTo(3);
        assertThat(cmd.scope().category()).isEqualTo("preuves");
        assertThat(outcome.shows()).containsExactly(cmd);
        assertThat(screen.said.toString()).contains("voici les preuves");
    }

    @Test
    void showWithoutSentenceAsksForThePhraseOnce() {
        Screen screen = new Screen();
        JarvisFastBrainService.Outcome outcome = service(scripted(
                new AgentTurn("", List.of(tool("s1", "afficher", SHOW_PROOFS)), false, 100, 10),
                new AgentTurn("Voici les preuves du chantier 3.", List.of(), true, 100, 10)), null, null)
                .converse(ask("Montre-moi les preuves du chantier 3"), screen);

        assertThat(calls).hasSize(2);
        assertThat(screen.shows).hasSize(1);
        assertThat(outcome.spokenText()).isEqualTo("Voici les preuves du chantier 3.");
        // Le 2ᵉ appel rejoue le tour d'outil et son résultat ; même consigne, mêmes outils.
        AgentTurnRequest second = calls.get(1);
        assertThat(second.system()).isEqualTo(calls.get(0).system());
        assertThat(second.tools()).isEqualTo(calls.get(0).tools());
        assertThat(second.messages().get(second.messages().size() - 1).content().get(0))
                .isInstanceOf(fr.claudegateway.agent.AgentContentBlock.ToolResult.class);
    }

    @Test
    void piecesIsReadOnceByTheGatewayThenTheModelAnswersGroundedOnIt() {
        JarvisFastPieces pieces = mock(JarvisFastPieces.class);
        when(pieces.read(eq(alice), eq(subject), any())).thenReturn(
                "1 pièce\nvideo:abc@v2 | vidéo | « Démo du déploiement » | Le déploiement sur EKS | chantier 3");
        when(pieces.block(alice, subject)).thenReturn("## Pièces\nchantier 3 : besoin initial, 0 entrée, 0 preuve");
        String pick = """
                {"action":"piece","pieces":["video:abc@v2"],"portee":{"sujet":"","chantier":null,"categorie":"",
                 "type":"","serie":"","depuis":""},"index":null,"temps":null,"chapitre":"","page":null,"zoom":"",
                 "cible":"","raison":"demande"}""";
        Screen screen = new Screen();
        JarvisFastBrainService service = service(scripted(
                new AgentTurn("", List.of(tool("p1", "pieces", """
                        {"sujet":"","chantier":null,"categorie":"","type":"video","recherche":"deploiement"}""")),
                        false, 100, 10),
                new AgentTurn("Voici la démo du déploiement sur EKS.", List.of(tool("s1", "afficher", pick)), true,
                        100, 10)), null, null);
        service.setPieces(pieces);

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Montre celle sur le déploiement"), screen);

        assertThat(calls).hasSize(2);
        verify(pieces, org.mockito.Mockito.times(1)).read(eq(alice), eq(subject), any());
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        // « EKS » vient du résultat de pieces : la phrase est ancrée, pas déléguée d'office.
        assertThat(outcome.spokenText()).contains("EKS");
        assertThat(screen.shows).extracting(JarvisShowCommand::pieces).containsExactly(List.of("video:abc@v2"));
        // Le bloc « ## Pièces » est dans le message, jamais dans la consigne.
        String message = ((fr.claudegateway.agent.AgentContentBlock.Text) calls.get(0).messages()
                .get(calls.get(0).messages().size() - 1).content().get(0)).text();
        assertThat(message).contains("## Pièces").contains("chantier 3 : besoin initial");
        assertThat(calls.get(0).system()).doesNotContain("## Pièces\nchantier");
    }

    @Test
    void neverMoreThanThreeModelCallsAndASecondReadIsRefused() {
        JarvisFastPieces pieces = mock(JarvisFastPieces.class);
        when(pieces.read(any(), any(), any())).thenReturn("Aucune pièce.");
        AgentTurn reading = new AgentTurn("", List.of(tool("p", "pieces", """
                {"sujet":"","chantier":null,"categorie":"","type":"","recherche":""}"""),
                tool("s", "afficher", SHOW_PROOFS)), false, 100, 10);
        JarvisFastBrainService service = service(scripted(reading), null, null);
        service.setPieces(pieces);

        service.converse(ask("Montre les preuves"), new Screen());

        assertThat(calls).hasSizeLessThanOrEqualTo(JarvisFastBrainService.MAX_CALLS);
        verify(pieces, org.mockito.Mockito.times(1)).read(any(), any(), any());
    }

    @Test
    void atMostThreeProactivePiecesPerAnswer() {
        String many = """
                {"action":"piece","pieces":["dep:1","dep:2","dep:3","dep:4","dep:5"],"portee":{"sujet":"",
                 "chantier":null,"categorie":"","type":"","serie":"","depuis":""},"index":null,"temps":null,
                 "chapitre":"","page":null,"zoom":"","cible":"","raison":"appui"}""";
        Screen screen = new Screen();
        service(scripted(new AgentTurn("Voici ce que vous m'aviez fourni.", List.of(tool("s1", "afficher", many),
                tool("s2", "afficher", SHOW_PROOFS)), true, 100, 10)), null, null)
                .converse(ask("Qu'est-ce que je t'avais donné ?"), screen);

        // 3 pièces d'initiative au plus : la galerie suivante, d'initiative elle aussi, est refusée.
        assertThat(screen.shows).hasSize(1);
        assertThat(screen.shows.get(0).pieces()).containsExactly("dep:1", "dep:2", "dep:3");
    }

    @Test
    void theScreenBlockTravelsInTheLastMessageClipped() {
        String screenBlock = "## Écran\nGalerie : Entrées du chantier 3 (2 pièces) ; sélection : aucune\n"
                + "x".repeat(3_000);
        service(answering("D'accord."), null, null).converse(new JarvisFastBrainService.Request(alice, workspace, 3,
                "La deuxième", null, screenBlock), null);

        String message = ((fr.claudegateway.agent.AgentContentBlock.Text) calls.get(0).messages()
                .get(calls.get(0).messages().size() - 1).content().get(0)).text();
        assertThat(message).contains("## Écran\nGalerie : Entrées du chantier 3");
        assertThat(message.length()).isLessThan(3_000);
        assertThat(calls.get(0).system()).doesNotContain("Galerie : Entrées");
    }

    // ------------------------------------------------------- F-237 / SF-237-15 : porte du banc PIECES

    private static final String DELEGATE_CHECK = """
            {"demande":"Vérifier le chantier et générer les captures de preuve","label":"preuve du chantier",
             "annonce":"Je vérifie le chantier, Monsieur.","sujet":"","chantier":null}""";

    private JarvisFastBrainService withPiecesBlock(JarvisFastBrainService service, String block) {
        JarvisFastPieces pieces = mock(JarvisFastPieces.class);
        when(pieces.block(alice, subject)).thenReturn(block);
        service.setPieces(pieces);
        return service;
    }

    @Test
    void anUngroundedCaptionIsRemovedButTheScreenStillShowsWithoutEscalatingToOpus() {
        Screen screen = new Screen();
        JarvisFastBrainService.Outcome outcome = service(scripted(new AgentTurn("Voici la démo tournée chez Zorglub.",
                List.of(tool("s1", "afficher", SHOW_PROOFS)), true, 100, 10)), null, null)
                .converse(ask("Montre-moi les preuves du chantier 3."), screen);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.delegation()).isNull();
        assertThat(screen.shows).hasSize(1);
        assertThat(outcome.shows()).hasSize(1);
        assertThat(screen.said.toString()).doesNotContain("Zorglub").contains("Voici.");
        assertThat(outcome.guards().groundingTrips()).isEqualTo(1);
    }

    @Test
    void anUngroundedSentenceWithoutAnyShowIsStillDelegated() {
        JarvisFastBrainService.Outcome outcome = service(answering("Le chantier est chez Zorglub."), null, null)
                .converse(ask("Où en est le chantier 3 ?"), new Screen());

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        assertThat(outcome.reason()).isEqualTo(JarvisFastBrainService.REASON_GROUNDING);
    }

    @Test
    void aDoubtOnAChantierWithoutProofIsAProposalWithoutAnyModelCall() {
        Screen screen = new Screen();
        JarvisFastBrainService service = withPiecesBlock(service(delegating(DELEGATE_CHECK), null, null),
                "## Pièces\nchantier 5 : besoin initial, 1 entrée, 0 preuve\nchantier 3 : 2 entrées, 2 preuves");

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Le cache Redis du chantier 5, tu as vérifié ?"),
                screen);

        assertThat(calls).isEmpty();
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.reason()).isEqualTo(JarvisFastBrainService.REASON_PROOF_GUARD);
        assertThat(outcome.spokenText()).contains("chantier 5").endsWith("?");
        assertThat(screen.said.toString()).contains("Voulez-vous que je la génère");
        assertThat(screen.shows).isEmpty();
    }

    @Test
    void aDoubtThatTheModelDelegatesShowsTheExistingProofsInstead() {
        Screen screen = new Screen();
        JarvisFastBrainService service = withPiecesBlock(service(delegating(DELEGATE_CHECK), null, null),
                "## Pièces\nchantier 3 : besoin initial, 2 entrées, 2 preuves");

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Prouve-moi que le portail répond."), screen);

        assertThat(calls).hasSize(1);
        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.delegation()).isNull();
        assertThat(screen.shows).singleElement().satisfies(cmd -> {
            assertThat(cmd.action()).isEqualTo("galerie");
            assertThat(cmd.scope().chantier()).isEqualTo(3);
            assertThat(cmd.scope().category()).isEqualTo("preuves");
        });
        assertThat(outcome.spokenText()).isEqualTo("Voici les preuves du chantier 3.");
    }

    @Test
    void aDoubtWithUnknownProofsIsNeverDelegatedButProposed() {
        JarvisFastBrainService.Outcome outcome = service(delegating(DELEGATE_CHECK), null, null)
                .converse(ask("Tu es sûr que c'est fini ?"), new Screen());

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo(JarvisProofGuard.PROPOSE_CHECK);
    }

    @Test
    void theYesToTheProposalIsDelegated() {
        JarvisFastBrainService service = withPiecesBlock(service(delegating(DELEGATE_CHECK), null, null),
                "## Pièces\nchantier 5 : besoin initial, 1 entrée, 0 preuve");

        JarvisFastBrainService.Outcome outcome = service.converse(ask("Oui, vas-y."), new Screen());

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
    }

    @Test
    void anExplicitOrderIsNotGuarded() {
        JarvisFastBrainService service = withPiecesBlock(service(delegating(DELEGATE_CHECK), null, null),
                "## Pièces\nchantier 5 : besoin initial, 1 entrée, 0 preuve");

        JarvisFastBrainService.Outcome outcome = service.converse(
                ask("Tu es sûr pour le chantier 5 ? Génère la preuve."), new Screen());

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
    }

    // ------------------------------------------------------- F-240 / SF-240-04 : « dis-m'en plus » (⤵)

    private static final String WITH_OPUS = "## Derniers échanges du fil du sujet courant (état à 09:15)\n"
            + "- 09:10 Monsieur : Regarde les tickets bloqués.\n- 09:12 Opus : Deux tickets sont bloqués.\n";

    private JarvisFastContextBuilder contextWith(String text) {
        JarvisFastContextBuilder builder = mock(JarvisFastContextBuilder.class, invocation ->
                invocation.getMethod().getReturnType() == String.class ? ""
                        : org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation));
        when(builder.build(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(text);
        return builder;
    }

    @Test
    void f240TheMoreMarkIsRelayedUntouchedAndNeverWrittenInTheThread() {
        JarvisFastExchangeWriter writer = mock(JarvisFastExchangeWriter.class);
        JarvisFastBrainService service = service(streaming("compris: Dis-m'en plus.\n", "⤵"), null, null);
        service.setExchanges(writer);
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service.converse(ask("Dis-m'en plus."), heard::append);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo("⤵");
        assertThat(JarvisFastBrainService.isMore(outcome)).isTrue();
        assertThat(heard.toString().strip()).isEqualTo("⤵");
        org.mockito.Mockito.verifyNoInteractions(writer);
        // Une seconde demande de suite n'est pas une répétition creuse : la marque passe encore.
        JarvisFastBrainService.Outcome again = service.converse(ask("Encore."), null);
        assertThat(again.spokenText()).isEqualTo("⤵");
    }

    @Test
    void f240AfterTheMarkNothingElseIsSaid() {
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service(streaming("⤵\nVoici la suite détaillée. "), null, null)
                .converse(ask("Développe."), heard::append);
        assertThat(outcome.spokenText()).isEqualTo("⤵");
        assertThat(heard.toString()).doesNotContain("Voici");
    }

    @Test
    void f240AMoreRequestNeverDelegatesWhenTheThreadHasAnOpusAnswer() {
        JarvisFastBrainService service = service(delegating("""
                {"demande": "Développer le point Helios", "label": "point Helios", "annonce": "Je regarde.",
                 "sujet": "", "chantier": null}
                """), null, null);
        service.setContext(contextWith(WITH_OPUS), null, null);
        StringBuilder heard = new StringBuilder();
        JarvisFastBrainService.Outcome outcome = service.converse(ask("Tu peux développer le point Helios ?"),
                heard::append);

        assertThat(outcome.kind()).isEqualTo(JarvisFastBrainService.Kind.ANSWER);
        assertThat(outcome.spokenText()).isEqualTo("⤵");
        assertThat(outcome.delegation()).isNull();
        assertThat(heard.toString().strip()).isEqualTo("⤵");
    }

    @Test
    void f240WithoutAnOpusAnswerOrForANewRequestTheDelegationStands() {
        JarvisFastBrainService service = service(delegating("""
                {"demande": "x", "label": "y", "annonce": "Je regarde.", "sujet": "", "chantier": null}
                """), null, null);
        service.setContext(contextWith("## Maintenant\nlundi"), null, null);
        assertThat(service.converse(ask("Dis-m'en plus."), null).kind())
                .isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        service.setContext(contextWith(WITH_OPUS), null, null);
        assertThat(service.converse(ask("Détaille le ticket LDIC-223."), null).kind())
                .isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
        // « Vérifie » délègue toujours, réponse d'Opus ou non.
        assertThat(service.converse(ask("Vérifie."), null).kind()).isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
    }

    @Test
    void f240TheBenchUsesItsFrozenContextForTheGuard() {
        JarvisFastBrainService service = service(delegating("""
                {"demande": "x", "label": "y", "annonce": "Je regarde.", "sujet": "", "chantier": null}
                """), null, null);
        assertThat(service.bench(ask("Dis-m'en plus."), "claude-haiku-5-5", WITH_OPUS).spokenText()).isEqualTo("⤵");
        assertThat(service.bench(ask("Dis-m'en plus."), "claude-haiku-5-5", "## Maintenant").kind())
                .isEqualTo(JarvisFastBrainService.Kind.DELEGATE);
    }

    @Test
    void f240ThePromptTeachesTheMarkAndTheLengthOnce() {
        assertThat(JarvisFastBrainPrompt.system()).contains("# La suite d'une réponse d'Opus")
                .contains("écris seulement la ligne « ⤵ »").contains("environ 25 mots en tout (30 au plus)")
                .doesNotContain("Une à trois phrases orales");
    }
}
