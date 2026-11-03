package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionService;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.veille.JarvisWatchAlert;
import fr.claudegateway.atelier.veille.JarvisWatchAlertRepository;
import fr.claudegateway.jarvis.JarvisReturnService;
import fr.claudegateway.jarvis.universe.JarvisUniverse;
import fr.claudegateway.jarvis.universe.JarvisUniverseService;

/** F-235 / SF-235-02 — le contexte compact : blocs, heure, bornes, isolation, blocs en échec omis. */
class JarvisFastContextBuilderTest {

    private static final Instant NOW = Instant.parse("2026-11-01T13:32:00Z");
    private final UUID alice = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private Workspace workspace;
    private JarvisUniverseService universe;
    private TerminalActionService actions;
    private JarvisReturnService returns;
    private JarvisWatchAlertRepository alerts;
    private AtelierMessageRepository messages;
    private JarvisDelegationRegistry delegations;
    private JarvisFastContextBuilder builder;

    @BeforeEach
    void setUp() {
        workspace = Workspace.builder().id(subject).userId(alice).name("Edenred").build();
        universe = mock(JarvisUniverseService.class);
        actions = mock(TerminalActionService.class);
        returns = mock(JarvisReturnService.class);
        alerts = mock(JarvisWatchAlertRepository.class);
        messages = mock(AtelierMessageRepository.class);
        delegations = new JarvisDelegationRegistry(clock, mock(LiveTurnRegistry.class));
        builder = new JarvisFastContextBuilder(clock, delegations);
        builder.setSources(universe, actions, returns, alerts, messages);

        when(universe.universe(alice)).thenReturn(new JarvisUniverse(OffsetDateTime.now(clock), List.of(
                new JarvisUniverse.Client(UUID.randomUUID(), "CAGIP", false, true, null, List.of(
                        new JarvisUniverse.Subject(subject, "Edenred", false, true, List.of(
                                new JarvisUniverse.Chantier(3, "Migration Keycloak", "BUILD", "En construction", 2, 5,
                                        new JarvisUniverse.Step(3, "Tests d'intégration"), true, false,
                                        List.of("Paul"))))),
                        List.of(new JarvisUniverse.Person("Paul Martin", List.of(), "RSSI")), List.of(), false)),
                1));
        when(actions.list(alice, subject, true)).thenReturn(List.of(TerminalAction.builder()
                .description("Relancer Paul sur le certificat").status(TerminalActionStatus.A_FAIRE).build()));
        when(returns.toSend(alice, null)).thenReturn(new JarvisReturnService.ToSendList(1, List.of(
                new JarvisReturnService.ToSend(subject, "Edenred", UUID.randomUUID(), 0, "mail", "Point Keycloak",
                        "Paul", null, null, "", null, true))));
        when(alerts.findByUserIdAndCreatedAtGreaterThanEqual(eq(alice), any())).thenReturn(List.of(
                JarvisWatchAlert.builder().userId(alice).sentence("Tickets Edenred : EDN-42 passe en bloqué.")
                        .createdAt(OffsetDateTime.now(clock).minusHours(2)).build()));
        when(messages.findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(subject, alice)).thenReturn(List.of(
                message("ASSISTANT", "🎙 Le certificat est en place, Monsieur.\n\nDétail : " + "x".repeat(900), null, 1),
                message("USER", "Où en est le certificat ?", null, 2),
                message("USER", "Message d'un autre chantier", 7, 3)));
    }

    private AtelierMessage message(String role, String content, Integer chantier, int minutesAgo) {
        return AtelierMessage.builder().workspaceId(subject).userId(alice).role(role).content(content)
                .chantierNumber(chantier).createdAt(OffsetDateTime.now(clock).minusMinutes(minutesAgo)).build();
    }

    @Test
    void f245TheJarvisThreadExchangesComeBeforeTheSubjectThread() {
        UUID thread = UUID.randomUUID();
        fr.claudegateway.jarvis.thread.JarvisThreadService threads =
                mock(fr.claudegateway.jarvis.thread.JarvisThreadService.class);
        when(threads.idOf(alice)).thenReturn(java.util.Optional.of(thread));
        builder.setThreads(threads);
        when(messages.findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(thread, alice)).thenReturn(List.of(
                fr.claudegateway.atelier.AtelierMessage.builder().workspaceId(thread).userId(alice).role("ASSISTANT")
                        .content("Bonjour, Monsieur.").brain(fr.claudegateway.atelier.AtelierMessage.BRAIN_FAST)
                        .build(),
                fr.claudegateway.atelier.AtelierMessage.builder().workspaceId(thread).userId(alice).role("USER")
                        .content("Bonjour Jarvis").build()));

        String context = builder.build(alice, workspace, 3, null, false);

        assertThat(context).contains("## Derniers échanges du fil Jarvis");
        assertThat(context).contains("Monsieur : Bonjour Jarvis").contains("Jarvis : Bonjour, Monsieur.");
        assertThat(context.indexOf("fil Jarvis")).isLessThan(context.indexOf("fil du sujet courant") < 0
                ? Integer.MAX_VALUE : context.indexOf("fil du sujet courant"));
        verify(threads, org.mockito.Mockito.never()).open(any());
    }

    @Test
    void f245WithoutAJarvisThreadNothingIsAddedNorCreated() {
        fr.claudegateway.jarvis.thread.JarvisThreadService threads =
                mock(fr.claudegateway.jarvis.thread.JarvisThreadService.class);
        when(threads.idOf(alice)).thenReturn(java.util.Optional.empty());
        builder.setThreads(threads);

        assertThat(builder.build(alice, workspace, 3, null, false)).doesNotContain("fil Jarvis");
        verify(threads, org.mockito.Mockito.never()).open(any());
    }

    @Test
    void carriesEveryBlockWithItsTime() {
        String context = builder.build(alice, workspace, 3, null);

        assertThat(context).contains("## Maintenant").contains("14:32")
                .contains("Sujet courant : Edenred").contains("Chantier courant : 3")
                .contains("## Univers (état à 14:32)").contains("Client CAGIP (poste connecté)")
                .contains("chantier 3 — Migration Keycloak").contains("étape en cours : Tests d'intégration")
                .contains("attend : Paul").contains("Paul Martin (RSSI)")
                .contains("Relancer Paul sur le certificat")
                .contains("1 message(s) à envoyer").contains("« Point Keycloak »")
                .contains("EDN-42 passe en bloqué")
                .contains("Monsieur : Où en est le certificat ?")
                .contains("Opus : Le certificat est en place, Monsieur.")
                .doesNotContain("Message d'un autre chantier");
    }

    @Test
    void eachThreadMessageIsCappedAndTheWholeIsBounded() {
        String context = builder.build(alice, workspace, 3, "Je disais que " + "bla ".repeat(400));

        for (String line : context.split("\n")) {
            if (line.startsWith("- ") && line.contains("Opus : ")) {
                assertThat(line.length()).isLessThanOrEqualTo(JarvisFastContextBuilder.MAX_MESSAGE + 20);
            }
        }
        assertThat(context.length()).isLessThanOrEqualTo(JarvisFastContextBuilder.MAX_TOTAL);
        assertThat(context).contains("Jarvis a été coupé après");
    }

    @Test
    void aFailingSourceIsOmittedNotFatal() {
        when(universe.universe(alice)).thenThrow(new IllegalStateException("panne"));

        String context = builder.build(alice, workspace, 3, null);

        assertThat(context).doesNotContain("## Univers").contains("Relancer Paul");
    }

    @Test
    void readsOnlyUnderTheCallersUserId() {
        builder.build(alice, workspace, 3, null);

        verify(universe).universe(alice);
        verify(actions).list(alice, subject, true);
        verify(returns).toSend(alice, null);
        verify(alerts).findByUserIdAndCreatedAtGreaterThanEqual(eq(alice), any());
        verify(messages).findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(subject, alice);
    }

    @Test
    void listsRunningDelegationsOfTheAccountOnly() {
        delegations.started(alice, UUID.randomUUID(), subject, "Edenred", null, "tickets bloqués d'Edenred");
        delegations.started(UUID.randomUUID(), UUID.randomUUID(), subject, "Autre", null, "secret de Bob");

        String context = builder.build(alice, workspace, null, null);

        assertThat(context).contains("tickets bloqués d'Edenred — sujet Edenred — depuis 14:32")
                .doesNotContain("secret de Bob");
    }

    @Test
    void aDelegationWithoutLiveTurnIsForgottenAfterItsGrace() {
        MovingClock moving = new MovingClock(NOW);
        LiveTurnRegistry live = mock(LiveTurnRegistry.class);
        JarvisDelegationRegistry registry = new JarvisDelegationRegistry(moving, live);
        registry.started(alice, UUID.randomUUID(), subject, "Edenred", null, "x");
        assertThat(registry.running(alice)).hasSize(1);

        moving.now = NOW.plus(Duration.ofMinutes(3));
        when(live.find(alice, subject, null)).thenReturn(java.util.Optional.empty());

        assertThat(registry.running(alice)).isEmpty();
    }

    /** Une horloge qu'on avance à la main. */
    static final class MovingClock extends Clock {
        Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
